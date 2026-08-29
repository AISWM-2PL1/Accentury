import Foundation

/// 업로드 한 건의 상태. 안드로이드 `upload/UploadState.kt`의 1:1 이식본이다.
///
/// 코틀린은 `sealed interface` + `data class`이고 이쪽은 열거형이다. ``Failure``만 중첩 구조체로
/// 두는 이유는 불변식을 만드는 자리에서 막기 위해서다 — 열거형 case에는 생성 시점 검사를 걸 수 없다.
public enum UploadState: Equatable, Sendable {

    case inFlight

    case done(analysisJobId: String)

    case failed(Failure)

    /// 업로드 한 건이 실패로 확정된 상태.
    ///
    /// - `retryable`: 같은 바이트를 다시 보낼 값어치가 있는가. 화면의 [재시도] 버튼이 이 값 하나로 선다.
    /// - `message`: 사용자에게 보일 이유. 서버 거절이면 서버가 준 문구고, 전송 실패면
    ///   예외 종류로 고른 안내 문구다 (KAN-147) — 네트워크 스택 문구가 화면에 새지 않는다.
    /// - `rerecord`: 같은 바이트를 다시 보내봐야 소용없고 녹음을 새로 해야 한다는 뜻 (KAN-147).
    ///   서버가 녹음 자체를 거절한 코드(길이·용량·음량)에만 붙는다. 호출자가 이 값을 보고
    ///   업로드를 폐기하고 그 문항의 녹음 화면을 다시 연다.
    ///
    /// `retryable`과 `rerecord`는 동시에 true가 되지 않는다 — 재전송과 재녹음은 서로 다른 복구
    /// 경로라, 둘을 함께 세우면 화면이 어느 쪽을 권하는지 말할 수 없다.
    public struct Failure: Equatable, Sendable {
        public let retryable: Bool
        public let message: String?
        public let rerecord: Bool

        /// 문서로만 둔 불변식은 리팩터링 한 번에 깨진다. 만드는 자리에서 막아 두 복구 경로가
        /// 한 화면에 겹치는 상태 자체가 생기지 않게 한다.
        ///
        /// 안드로이드는 `require`로 `IllegalArgumentException`을 던지고, 이쪽은 `precondition`으로
        /// 중단한다 — 둘 다 "프로그래머 실수"라는 같은 판정이다. 다만 트랩은 테스트가 잡을 수
        /// 없어서, 같은 판정을 ``isRepresentable(retryable:rerecord:)``로 한 번 더 노출한다.
        public init(retryable: Bool, message: String?, rerecord: Bool = false) {
            precondition(
                UploadState.isRepresentable(retryable: retryable, rerecord: rerecord),
                "재전송(retryable)과 재녹음(rerecord)은 함께 설 수 없다"
            )
            self.retryable = retryable
            self.message = message
            self.rerecord = rerecord
        }
    }

    /// 안드로이드 `UploadState.Failed(...)` 호출 자리를 그대로 옮기기 위한 편의 생성자.
    public static func failed(retryable: Bool, message: String?, rerecord: Bool = false) -> UploadState {
        .failed(Failure(retryable: retryable, message: message, rerecord: rerecord))
    }

    /// ``Failure``의 불변식. 생성 자리(트랩)와 검증 자리(테스트)가 같은 판정을 읽게 한다.
    public static func isRepresentable(retryable: Bool, rerecord: Bool) -> Bool {
        !(retryable && rerecord)
    }
}
