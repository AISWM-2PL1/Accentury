import XCTest
@testable import AccenturyCore

/// 멀티파트 하네스 자체의 엄격성 테스트.
///
/// 파서가 느슨하면 프레이밍이 깨진 요청도 초록으로 통과한다 — 서버는 400을 주는데
/// 테스트만 통과하는 상태다. 그래서 "무엇을 거부하는가"를 여기서 못 박는다.
final class MultipartParserTests: XCTestCase {

    private let boundary = "Boundary-TEST-0001"

    private var audioHeaders: String {
        "Content-Disposition: form-data; name=\"audio\"; filename=\"recording.wav\"\r\nContent-Type: audio/wav"
    }

    private var metaHeaders: String {
        "Content-Disposition: form-data; name=\"meta\"\r\nContent-Type: application/json"
    }

    private let metaJSON = #"{"durationMs":3210}"#

    /// 결함을 하나씩 심을 수 있는 픽스처 생성기.
    private func fixture(
        opening: String? = nil,
        audioHeaderTerminator: String = "\r\n\r\n",
        terminator: String? = nil,
        audioBytes: Data = Data([0x01, 0x02, 0x03])
    ) -> Data {
        var body = Data((opening ?? "--\(boundary)\r\n").utf8)
        body.append(Data((audioHeaders + audioHeaderTerminator).utf8))
        body.append(audioBytes)
        body.append(Data("\r\n--\(boundary)\r\n".utf8))
        body.append(Data((metaHeaders + "\r\n\r\n" + metaJSON).utf8))
        body.append(Data((terminator ?? "\r\n--\(boundary)--\r\n").utf8))
        return body
    }

    func test제대로_된_본문은_두_파트로_갈린다() throws {
        let audioBytes = Data([0x01, 0x02, 0x03])

        let parts = try MultipartParser.parse(body: fixture(audioBytes: audioBytes), boundary: boundary)

        XCTAssertEqual(["audio", "meta"], parts.map(\.name))
        XCTAssertEqual("recording.wav", parts[0].filename)
        XCTAssertEqual(audioBytes, parts[0].body)
        XCTAssertEqual(Data(metaJSON.utf8), parts[1].body)
    }

    /// CRLF가 LF 하나로 줄어든 본문은 거부한다. 서버 쪽 파서도 같은 이유로 거부하므로,
    /// 여기서 통과시키면 실기기에서만 400이 나는 상태가 된다.
    func testCRLF가_어긋난_본문은_파싱을_거부한다() {
        // ① 첫 구분자 뒤가 LF 하나다
        XCTAssertThrowsError(
            try MultipartParser.parse(body: fixture(opening: "--\(boundary)\n"), boundary: boundary)
        ) { error in
            XCTAssertEqual(
                MultipartParseError.missingOpeningDelimiter.description,
                (error as? MultipartParseError)?.description
            )
        }

        // ② 헤더 블록이 LFLF로 끝난다 — 그 뒤 CRLFCRLF까지가 한 헤더로 붙어 버려
        //    헤더 안에 구분자가 들어온다. 파서는 그것으로 프레이밍이 깨진 것을 알아챈다.
        XCTAssertThrowsError(
            try MultipartParser.parse(body: fixture(audioHeaderTerminator: "\n\n"), boundary: boundary)
        ) { error in
            XCTAssertEqual(
                MultipartParseError.delimiterInsideHeaders(partIndex: 0).description,
                (error as? MultipartParseError)?.description
            )
        }
    }

    /// 종료자는 `\r\n--boundary--\r\n`이다. 대시 둘이 빠지면 그냥 구분자라 파트가 하나 더
    /// 온다는 뜻이 되고, 본문은 그 파트를 채우지 못한 채 끝난다.
    func test종료자에_대시가_없으면_거부한다() {
        XCTAssertThrowsError(
            try MultipartParser.parse(body: fixture(terminator: "\r\n--\(boundary)\r\n"), boundary: boundary)
        ) { error in
            XCTAssertEqual(
                MultipartParseError.missingHeaderTerminator(partIndex: 2).description,
                (error as? MultipartParseError)?.description
            )
        }

        // 종료자가 통째로 없으면 마지막 파트가 구분자 없이 끝난다.
        XCTAssertThrowsError(
            try MultipartParser.parse(body: fixture(terminator: ""), boundary: boundary)
        ) { error in
            XCTAssertEqual(
                MultipartParseError.unterminatedPart(partIndex: 1).description,
                (error as? MultipartParseError)?.description
            )
        }
    }

    /// 종료자 뒤는 본문의 끝이다. 뒤에 바이트가 더 붙어 있으면 우리가 만든 본문이 아니거나
    /// 프레이밍이 깨진 것이라, 조용히 무시하지 않고 거부한다.
    func test종료자_뒤에_바이트가_붙어_있으면_거부한다() {
        XCTAssertThrowsError(
            try MultipartParser.parse(
                body: fixture(terminator: "\r\n--\(boundary)--\r\njunk"),
                boundary: boundary
            )
        ) { error in
            XCTAssertEqual(
                MultipartParseError.trailingBytesAfterTerminator.description,
                (error as? MultipartParseError)?.description
            )
        }
    }

    /// WAV는 임의 바이트열이라 boundary와 같은 시퀀스가 우연히 들어갈 수 있다. 구분자는
    /// **앞뒤 CRLF까지 갖춘 것**만이라, 그런 바이트가 파트를 가르지 못한다.
    func test오디오_바이트에_구분자와_같은_시퀀스가_들어_있어도_정확히_가른다() throws {
        var audioBytes = Data([0xFF, 0x00])
        audioBytes.append(Data("--\(boundary)".utf8))          // CRLF 없이 맨몸으로
        audioBytes.append(Data([0x10]))
        audioBytes.append(Data("\r\n--\(boundary)".utf8))      // CRLF는 있는데 뒤가 CRLF가 아니다
        audioBytes.append(Data([0x00, 0x7F]))

        let parts = try MultipartParser.parse(body: fixture(audioBytes: audioBytes), boundary: boundary)

        XCTAssertEqual(2, parts.count)
        XCTAssertEqual(audioBytes.count, parts[0].body.count)
        XCTAssertEqual(audioBytes, parts[0].body)
        XCTAssertEqual(Data(metaJSON.utf8), parts[1].body)
    }
}
