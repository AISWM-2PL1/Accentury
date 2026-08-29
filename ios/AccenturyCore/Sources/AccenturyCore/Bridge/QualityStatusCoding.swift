import Foundation

/// ``QualityStatus``를 브리지·저장 JSON에 싣기 위한 표기.
///
/// 안드로이드는 `@Serializable enum`이라 kotlinx가 **enum 이름 문자열**("NORMAL", "TOO_QUIET")을
/// 그대로 내보낸다. 웹은 그 문자열을 계약으로 읽으므로(KAN-89 5필드), 스위프트 쪽 케이스 이름
/// (`tooQuiet`)이 아니라 안드로이드 이름을 값으로 쓴다 — 한 서버·한 웹이 두 앱을 받는다.
extension QualityStatus: Codable {

    /// 안드로이드 enum 상수 이름. 이 문자열이 계약이다.
    var wireName: String {
        switch self {
        case .normal: return "NORMAL"
        case .tooShort: return "TOO_SHORT"
        case .tooQuiet: return "TOO_QUIET"
        case .clipped: return "CLIPPED"
        }
    }

    init?(wireName: String) {
        switch wireName {
        case "NORMAL": self = .normal
        case "TOO_SHORT": self = .tooShort
        case "TOO_QUIET": self = .tooQuiet
        case "CLIPPED": self = .clipped
        default: return nil
        }
    }

    public init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        guard let value = QualityStatus(wireName: raw) else {
            throw DecodingError.dataCorrupted(
                DecodingError.Context(
                    codingPath: decoder.codingPath,
                    debugDescription: "알 수 없는 qualityStatus: \(raw)"
                )
            )
        }
        self = value
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(wireName)
    }
}
