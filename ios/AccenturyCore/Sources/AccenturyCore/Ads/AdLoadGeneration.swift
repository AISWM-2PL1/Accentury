import Foundation

/// 광고 로드의 세대 카운터 (KAN-196 리뷰 P1-2). 안드로이드 `AdLoadGeneration.kt`의 이식본이다. 로드 요청은
/// 나가는 순간의 세대 토큰을 들고 가고, 완료 콜백은 그 토큰이 아직 현재 세대일 때만 결과를 받아들인다.
///
/// ## 왜 필요한가
///
/// 동의가 바뀌면 게이트는 받아 둔 광고를 버린다(`discard()`). 그런데 **로드가 진행 중일 때** 바뀌면
/// 버릴 것이 아직 없다 — 참조만 비우고 끝나면 옛 조건(예: 맞춤형)으로 나간 요청이 잠시 뒤 완료돼
/// `loaded`로 들어오고, 거부 뒤 첫 광고가 맞춤형으로 나간다. 게다가 `loading`이 true인 채라 새 조건의
/// `preload()`는 "받는 중"으로 보고 물러난다. 그래서 `discard()`는 세대를 올리고 `loading`도 내리며,
/// 옛 세대의 콜백은 성공이든 실패든 버린다 — SDK 로드는 취소 API가 없어 결과를 버리는 것이 유일한 길이다.
///
/// 순수 클래스로 뺀 이유는 두 게이트(전면·보상형)가 같은 규칙을 쓰고 `swift test`가 못박아야 해서다.
/// 스레드 안전하지 않다 — 게이트와 같이 메인 스레드(`@MainActor`) 전용이다.
public final class AdLoadGeneration {
    private var current = 0

    public init() {}

    /// 로드를 시작한다. 돌려준 토큰을 콜백까지 들고 가서 ``isCurrent(_:)``로 판정한다.
    public func begin() -> Int {
        current
    }

    /// 지금까지 나간 로드를 전부 옛 세대로 만든다. 그 콜백은 ``isCurrent(_:)``가 false다.
    public func invalidate() {
        current += 1
    }

    /// ``begin()``에서 받은 토큰이 아직 현재 세대인가.
    public func isCurrent(_ token: Int) -> Bool {
        token == current
    }
}
