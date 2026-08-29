import Foundation

/// 업로드 상태 바가 그릴 실패 행 하나. 안드로이드 `Pair<String, UploadState.Failed>` 자리다.
public struct UploadFailureRow: Equatable, Sendable {
    public let attemptId: String
    public let failure: UploadState.Failure

    public init(attemptId: String, failure: UploadState.Failure) {
        self.attemptId = attemptId
        self.failure = failure
    }
}

/// 상태 바가 읽는 요약. 성공(Done)은 조용히 넘어가고, 진행 중 개수와 실패 건만 남는다.
///
/// 복구 경로는 [재시도] 하나다 - 이탈 UX는 KAN-39 디자인 때 정한다 (KAN-147).
/// SwiftUI 바 자체는 §6b 몫이고, 여기는 안드로이드 `UploadStatusBar.kt`의 순수한 부분만이다.
public struct UploadSummary: Equatable, Sendable {
    public let inFlight: Int
    public let failed: [UploadFailureRow]

    public init(inFlight: Int, failed: [UploadFailureRow]) {
        self.inFlight = inFlight
        self.failed = failed
    }

    /// 보여줄 것이 하나도 없는가. 안드로이드가 Composable 첫 줄에서 하던 판정이다.
    public var isEmpty: Bool { inFlight == 0 && failed.isEmpty }
}

/// 표시 로직의 순수한 부분. 실패 목록은 업로드를 넣은 순서를 그대로 따른다.
///
/// 안드로이드는 `Map<String, UploadState>`를 받고 `LinkedHashMap`의 순서에 기댔다. Swift
/// `Dictionary`에는 순서가 없어서 순서를 아는 쪽(``UploadManager/entries``)이 배열로 넘긴다 —
/// 이 함수가 "넣은 순서"를 스스로 알 방법이 없다는 사실을 시그니처에 드러내는 편이 안전하다.
public func summarize(_ entries: [UploadEntry]) -> UploadSummary {
    var inFlight = 0
    var failed: [UploadFailureRow] = []
    for entry in entries {
        switch entry.state {
        case .inFlight:
            inFlight += 1
        case let .failed(failure):
            failed.append(UploadFailureRow(attemptId: entry.attemptId, failure: failure))
        case .done:
            continue
        }
    }
    return UploadSummary(inFlight: inFlight, failed: failed)
}
