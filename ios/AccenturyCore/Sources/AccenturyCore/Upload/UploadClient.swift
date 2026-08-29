import Foundation

/// 통신 한 번의 결과. 안드로이드 `upload/UploadClient.kt`의 `sealed interface UploadResult`다.
///
/// ``UploadState``와 헷갈리기 쉬운 자리라 정본 위키(`docs/wiki/upload-client.md`)의 구분을 그대로
/// 옮겨 둔다 — Result는 "통신 1회의 결과", State는 "업로드 1건의 현재 상태"다.
/// ``UploadManager``가 Result를 State로 번역한다.
public enum UploadResult: Equatable, Sendable {

    case accepted(analysisJobId: String)

    case rejected(code: String?, message: String?, retryable: Bool, retryAfterMs: Int64?)

    /// 응답이 아예 오지 않은 전송 실패. 의미상 항상 재시도 가능.
    ///
    /// - `failure`: 사용자에게 뭐라고 말할지 정하는 갈래 (KAN-147).
    /// - `reason`: 원본 오류 문구. 로그·디버깅용이라 화면에 그대로 내보내지 않는다.
    case transportError(failure: TransportFailure, reason: String)
}

/// 녹음 한 건을 올리는 경계. 구현은 ``URLSessionUploadClient``이고, 테스트는 가짜를 끼운다.
///
/// 인자 순서가 안드로이드(`upload(sessionId, sessionToken, request)`)와 다르다 — Swift는 첫 인자
/// 레이블이 메서드 이름의 일부처럼 읽혀서, 무엇을 올리는지가 앞에 오는 편이 호출부에서 자연스럽다.
public protocol UploadClient: Sendable {
    func upload(_ request: UploadRequest, sessionId: String, sessionToken: String) async -> UploadResult
}
