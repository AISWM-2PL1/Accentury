import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/audio/WavWriterTest.kt`의 1:1 이식본.
///
/// 안드로이드는 `ByteBuffer(LITTLE_ENDIAN)`으로 헤더 필드를 되읽는다. Foundation에는 대응물이
/// 없어 리틀엔디언 정수 판독기를 이 타깃에 다시 구현했다(아래 두 헬퍼) — 호스트 바이트 순서에
/// 기대지 않아야 "리틀엔디언으로 썼는가"를 실제로 검사하는 테스트가 된다.
final class WavWriterTests: XCTestCase {

    private func int32LE(_ bytes: Data, _ offset: Int) -> Int32 {
        var value: UInt32 = 0
        for i in (0..<4).reversed() { value = (value << 8) | UInt32(bytes[bytes.startIndex + offset + i]) }
        return Int32(bitPattern: value)
    }

    private func int16LE(_ bytes: Data, _ offset: Int) -> Int16 {
        var value: UInt16 = 0
        for i in (0..<2).reversed() { value = (value << 8) | UInt16(bytes[bytes.startIndex + offset + i]) }
        return Int16(bitPattern: value)
    }

    private func ascii(_ bytes: Data, _ offset: Int, _ count: Int) -> String {
        String(decoding: bytes[(bytes.startIndex + offset)..<(bytes.startIndex + offset + count)], as: UTF8.self)
    }

    private func restorePcm(_ bytes: Data, count: Int) -> [Int16] {
        (0..<count).map { int16LE(bytes, 44 + $0 * 2) }
    }

    /// 안드로이드 `File.createTempFile("wav_test", ".wav")` 자리. 쓴 뒤 바로 지운다.
    private func writeToTempFile(_ pcm: [Int16]) throws -> Data {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("wav_test_\(UUID().uuidString).wav")
        try WavWriter.write(file: file, pcm: pcm)
        let bytes = try Data(contentsOf: file)
        try? FileManager.default.removeItem(at: file)
        return bytes
    }

    /// 헤더 44바이트가 16kHz mono 16bit 규격대로 생성된다
    func testHeaderIs44BytesOf16kHzMono16Bit() throws {
        let pcm = (0..<sampleRate).map { Int16($0 % 100) }

        let bytes = try writeToTempFile(pcm)

        XCTAssertEqual(44 + sampleRate * 2, bytes.count)
        XCTAssertEqual("RIFF", ascii(bytes, 0, 4))
        XCTAssertEqual("WAVE", ascii(bytes, 8, 4))
        XCTAssertEqual("fmt ", ascii(bytes, 12, 4))
        XCTAssertEqual("data", ascii(bytes, 36, 4))

        XCTAssertEqual(1, Int(int16LE(bytes, 20)))
        XCTAssertEqual(1, Int(int16LE(bytes, 22)))
        XCTAssertEqual(sampleRate, Int(int32LE(bytes, 24)))
        XCTAssertEqual(sampleRate * 2, Int(int32LE(bytes, 28)))
        XCTAssertEqual(16, Int(int16LE(bytes, 34)))
        XCTAssertEqual(sampleRate * 2, Int(int32LE(bytes, 40)))
    }

    /// RIFF 청크 크기·fmt 크기·block align이 규격대로 기록된다
    func testRiffChunkSizeFmtSizeAndBlockAlignAreWrittenToSpec() throws {
        let pcm = [Int16](repeating: 0, count: 100)

        let bytes = try writeToTempFile(pcm)

        XCTAssertEqual(36 + 200, Int(int32LE(bytes, 4)))
        XCTAssertEqual(16, Int(int32LE(bytes, 16)))
        XCTAssertEqual(2, Int(int16LE(bytes, 32)))
    }

    /// 빈 PCM도 유효한 44바이트 헤더를 생성한다
    func testEmptyPcmStillProducesValid44ByteHeader() throws {
        let bytes = try writeToTempFile([])

        XCTAssertEqual(44, bytes.count)
        XCTAssertEqual(36, Int(int32LE(bytes, 4)))
        XCTAssertEqual(0, Int(int32LE(bytes, 40)))
    }

    /// toWavBytes는 파일로 쓴 것과 완전히 같은 바이트를 만든다
    func testToWavBytesProducesExactlyTheSameBytesAsTheWrittenFile() throws {
        let pcm = (0..<1_000).map { Int16(truncatingIfNeeded: $0 * 7 - 500) }

        let fromFile = try writeToTempFile(pcm)

        XCTAssertEqual(fromFile, WavWriter.toWavBytes(pcm))
    }

    /// toWavBytes는 헤더 44바이트 뒤에 PCM을 리틀엔디언으로 담는다
    func testToWavBytesPutsPcmLittleEndianAfterThe44ByteHeader() {
        let pcm: [Int16] = [0, 1000, -1000, Int16.max, Int16.min]

        let bytes = WavWriter.toWavBytes(pcm)

        XCTAssertEqual(44 + pcm.count * 2, bytes.count)
        XCTAssertEqual("RIFF", ascii(bytes, 0, 4))
        XCTAssertEqual("data", ascii(bytes, 36, 4))
        XCTAssertEqual(sampleRate, Int(int32LE(bytes, 24)))
        XCTAssertEqual(pcm.count * 2, Int(int32LE(bytes, 40)))

        XCTAssertEqual(pcm, restorePcm(bytes, count: pcm.count))
    }

    /// PCM 데이터가 손실 없이 기록된다
    func testPcmDataIsWrittenWithoutLoss() throws {
        let pcm: [Int16] = [0, 1000, -1000, Int16.max, Int16.min]

        let bytes = try writeToTempFile(pcm)

        XCTAssertEqual(pcm, restorePcm(bytes, count: pcm.count))
    }
}
