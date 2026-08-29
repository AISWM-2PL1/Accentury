import Foundation

/// multipart 본문을 파트로 갈라 이름·파일명·Content-Type·바이트를 꺼낸다.
///
/// boundary는 요청마다 새로 만들어지므로 테스트가 그 **값**에 기대면 안 된다. 그래서 파서가
/// `Content-Type` 헤더에서 boundary를 읽고 시작한다 — 안드로이드 테스트가 본문 문자열을
/// `contains`로 훑던 자리를, 여기서는 실제로 파싱해 파트 이름을 단언한다.
///
/// **엄격 파서다.** 느슨하게 훑으면 프레이밍이 깨진 본문도 통과해, 서버가 400을 줄 요청을
/// 테스트가 초록으로 통과시킨다. RFC 2046 §5.1.1의 골격만 그대로 요구한다:
///
/// - 첫 구분자는 `--boundary\r\n` — 본문이 정확히 이것으로 시작해야 한다
/// - 파트 헤더 블록은 `\r\n\r\n`으로 끝난다 (LF 하나로 끝나면 거부)
/// - 이후 구분자는 `\r\n--boundary\r\n`, 종료자는 `\r\n--boundary--\r\n`
/// - 종료자 뒤에는 아무것도 없다
///
/// 본문 바이트 안에 우연히 `--boundary`가 들어 있어도 갈리지 않는다 — 구분자는 **앞의 CRLF와
/// 뒤의 CRLF(또는 `--CRLF`)까지 갖춘 것**만 인정하고, 조건을 못 채운 일치는 건너뛰고 계속 찾는다.
struct MultipartPart {
    let name: String?
    let filename: String?
    let contentType: String?
    let body: Data
}

enum MultipartParseError: Error, CustomStringConvertible {
    /// 본문이 `--boundary\r\n`으로 시작하지 않는다.
    case missingOpeningDelimiter
    /// 파트 헤더 블록이 `\r\n\r\n`으로 끝나지 않는다.
    case missingHeaderTerminator(partIndex: Int)
    /// 헤더 블록 안에 구분자가 들어 있다 = 앞 파트의 프레이밍이 깨졌다는 뜻이다.
    case delimiterInsideHeaders(partIndex: Int)
    /// `Content-Disposition`에 `name`이 없다.
    case missingPartName(partIndex: Int)
    /// 다음 구분자도 종료자도 나오지 않은 채 본문이 끝났다.
    case unterminatedPart(partIndex: Int)
    /// 종료자 뒤에 바이트가 더 있다.
    case trailingBytesAfterTerminator

    var description: String {
        switch self {
        case .missingOpeningDelimiter: return "첫 구분자가 --boundary CRLF가 아니다"
        case let .missingHeaderTerminator(index): return "파트 \(index)의 헤더가 CRLFCRLF로 끝나지 않는다"
        case let .delimiterInsideHeaders(index): return "파트 \(index)의 헤더 안에 구분자가 있다"
        case let .missingPartName(index): return "파트 \(index)에 name이 없다"
        case let .unterminatedPart(index): return "파트 \(index)가 구분자 없이 끝났다"
        case .trailingBytesAfterTerminator: return "종료자 뒤에 바이트가 더 있다"
        }
    }
}

enum MultipartParser {

    private static let crlf = Data("\r\n".utf8)
    private static let headerTerminator = Data("\r\n\r\n".utf8)
    private static let closingSuffix = Data("--\r\n".utf8)

    static func boundary(fromContentType contentType: String) -> String? {
        guard let range = contentType.range(of: "boundary=") else { return nil }
        return String(contentType[range.upperBound...]).trimmingCharacters(in: CharacterSet(charactersIn: "\""))
    }

    static func parse(body: Data, boundary: String) throws -> [MultipartPart] {
        let dashBoundary = Data("--\(boundary)".utf8)
        let opening = dashBoundary + crlf
        guard body.starts(with: opening) else { throw MultipartParseError.missingOpeningDelimiter }

        var parts: [MultipartPart] = []
        var cursor = body.index(body.startIndex, offsetBy: opening.count)

        while true {
            let index = parts.count
            guard let headerEnd = body.range(of: headerTerminator, in: cursor..<body.endIndex) else {
                throw MultipartParseError.missingHeaderTerminator(partIndex: index)
            }
            let headerData = body[cursor..<headerEnd.lowerBound]
            // 헤더 안에 구분자가 보인다면 앞 파트가 CRLF를 빠뜨려 두 파트가 한 덩어리로 붙은 것이다.
            if headerData.range(of: crlf + dashBoundary) != nil {
                throw MultipartParseError.delimiterInsideHeaders(partIndex: index)
            }

            let contentStart = headerEnd.upperBound
            guard let (delimiter, isTerminator) = nextDelimiter(
                in: body,
                from: contentStart,
                dashBoundary: dashBoundary
            ) else {
                throw MultipartParseError.unterminatedPart(partIndex: index)
            }

            let headerText = String(decoding: headerData, as: UTF8.self)
            guard let name = value(of: "name", in: headerText) else {
                throw MultipartParseError.missingPartName(partIndex: index)
            }
            parts.append(
                MultipartPart(
                    name: name,
                    filename: value(of: "filename", in: headerText),
                    contentType: headerValue("Content-Type", in: headerText),
                    body: Data(body[contentStart..<delimiter.lowerBound])
                )
            )

            if isTerminator {
                guard delimiter.upperBound == body.endIndex else {
                    throw MultipartParseError.trailingBytesAfterTerminator
                }
                return parts
            }
            cursor = delimiter.upperBound
        }
    }

    /// 다음 구분자의 범위(앞 CRLF부터 뒤 CRLF까지)와 그것이 종료자인지.
    ///
    /// 뒤가 CRLF도 `--CRLF`도 아니면 **본문 바이트 안의 우연한 일치**라 건너뛰고 계속 찾는다.
    private static func nextDelimiter(
        in body: Data,
        from start: Data.Index,
        dashBoundary: Data
    ) -> (range: Range<Data.Index>, isTerminator: Bool)? {
        let marker = crlf + dashBoundary
        var searchFrom = start
        while let candidate = body.range(of: marker, in: searchFrom..<body.endIndex) {
            let after = candidate.upperBound
            let rest = body[after...]
            if rest.starts(with: crlf) {
                return (candidate.lowerBound..<body.index(after, offsetBy: crlf.count), false)
            }
            if rest.starts(with: closingSuffix) {
                return (candidate.lowerBound..<body.index(after, offsetBy: closingSuffix.count), true)
            }
            searchFrom = candidate.upperBound
        }
        return nil
    }

    /// `name="audio"`처럼 따옴표로 감싼 파라미터 하나를 꺼낸다.
    private static func value(of key: String, in headers: String) -> String? {
        guard let range = headers.range(of: "\(key)=\"") else { return nil }
        let rest = headers[range.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return nil }
        return String(rest[rest.startIndex..<end])
    }

    private static func headerValue(_ name: String, in headers: String) -> String? {
        for line in headers.split(separator: "\r\n") {
            let parts = line.split(separator: ":", maxSplits: 1)
            guard parts.count == 2, parts[0].caseInsensitiveCompare(name) == .orderedSame else { continue }
            return parts[1].trimmingCharacters(in: .whitespaces)
        }
        return nil
    }
}
