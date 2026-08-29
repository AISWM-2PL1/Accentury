import Foundation

/// 공통 오류 봉투 (API 명세서 §2.4). 업로드와 세션 생성이 같은 모양을 받는다.
///
/// 안드로이드는 파일마다 private `@Serializable data class`를 하나씩 뒀지만, 같은 모듈 안에서는
/// 이름이 겹칠 수 없어 한 자리로 모았다 — 필드가 갈리면 두 클라이언트가 같은 응답을 다르게
/// 읽게 되므로 오히려 여기 있는 편이 안전하다.
///
/// `retryable`만 기본값 없는 필수 필드다. 그 필드가 빠진 JSON은 디코딩 자체가 실패해
/// 상태 코드 폴백(5xx·408·429는 재시도 가능, 그 외 4xx는 불가)을 타게 한다.
struct ErrorEnvelope: Decodable {
    let code: String?
    let message: String?
    let retryable: Bool
    let retryAfterMs: Int64?
    let correlationId: String?
}

extension String {
    /// 코틀린 `takeIf { it.isNotBlank() }` 자리.
    var nonBlank: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
