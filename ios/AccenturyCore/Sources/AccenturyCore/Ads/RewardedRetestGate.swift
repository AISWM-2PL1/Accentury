import Foundation

/// 보상형 광고 게이트가 재응시 흐름에 내리는 결정 (KAN-196, webview-bridge.md §8.2).
/// 안드로이드 `RewardedGateDecision`의 이식본이다.
///
/// 결과 화면이 물어보는 것은 "광고가 어떻게 됐나"가 아니라 "재응시로 넘어가나, 아니면 이 화면에
/// 남아 무엇을 안내하나"다 — ``RetestOutcome``과 같은 접기다.
public enum RewardedGateDecision: Equatable, Sendable {

    /// 재응시를 진행한다 — 광고를 끝까지 봤거나, 보여줄 광고가 없거나, 표시에 실패했다.
    case proceed

    /// 광고를 띄웠다. 결정은 SDK 콜백(``RewardedRetestGate/onEarnedReward()`` 등)에서 온다.
    case showAd

    /// 광고를 끝까지 보지 않고 닫았다. 웹에 `AD_DISMISSED`를 회신하고 결과 화면은 그대로다.
    case dismissed

    /// 아무 일도 하지 않는다 — 표시 중의 중복 요청, 이미 결정이 난 뒤의 콜백.
    case ignored
}

/// 보상형 광고 → 재응시 상태 머신 (KAN-196). `startRetest` 앞단에 선다 (`TestFlowView.handleRetest`).
/// 안드로이드 `RewardedRetestGate.kt`와 **같은 표**다 — 테스트 케이스도 그대로 옮겼다.
///
/// SDK 콜백(`FullScreenContentDelegate` 등)을 직접 받지 않고 순수 메서드 네 개로 받는 이유는
/// ``SessionGateController``와 같다 — 어느 콜백 조합이 재응시로 이어지고 어느 조합이 `AD_DISMISSED`인지가
/// 이 티켓의 정확성인데, SDK에 붙어 있으면 `swift test`로 검증할 수 없다. SDK 결선은 앱 타깃의
/// `RewardedRetestAd`가 한다.
///
/// ## 네 갈래 (§8.5 표)
///
/// - 로드된 광고가 없다(로드 실패 포함) → ``RewardedGateDecision/proceed``. **광고 로드 실패는 재응시를
///   막지 않는다** — 광고 사정으로 사용자를 붙들 이유가 없다
/// - 광고를 끝까지 봤다(``onEarnedReward()``) → proceed. SDK는 보상 콜백을 닫힘 콜백보다 먼저 주므로
///   재응시(세션 생성·인트로 리로드)는 광고가 닫히기 전에 이미 돌기 시작한다. 닫으면 인트로가
///   기다리고 있다
/// - 보상 없이 닫혔다(``onDismissed()``) → ``RewardedGateDecision/dismissed``
/// - 표시 자체가 실패했다(``onShowFailed()``) → proceed. 로드 실패와 같은 판단이다
///
/// ## 표시 중 중복 요청
///
/// 광고가 떠 있는 동안의 ``request(loaded:)``는 ``RewardedGateDecision/ignored``다. 웹의 pending 잠금이
/// 먼저 막지만(useRetest), 네이티브에도 한 겹 두는 이유는 그 잠금이 웹 상태라서다 — 광고 위에서
/// 웹이 리로드되는 경합이 있으면 잠금이 풀린 채 두 번째 탭이 올 수 있고, 그때 광고를 한 번 더
/// 띄우면 첫 광고의 보상이 갈 곳을 잃는다. `retestInFlight`(SessionGateController)를 여기서
/// 쓰지 않는 이유: 그 플래그는 세션 요청이 나가 있다는 뜻이고 광고 시청은 그 앞 단계다.
/// 완주 뒤에야 `beginRetest()`를 걸어야 광고 도중 요청이 취소돼도 그 플래그가 세션 요청의 진실만 말한다.
public final class RewardedRetestGate {

    /// 광고가 떠 있다. 표시 중 중복 요청을 거르는 근거다.
    public private(set) var showing = false

    /// 이번 표시에서 보상을 받았다. 닫힘 콜백이 이 값을 보고 dismissed 여부를 가른다.
    private var earned = false

    public init() {}

    /// 재응시 요청이 왔다.
    ///
    /// - Parameter loaded: 지금 띄울 수 있는 광고가 있는가. 로드 실패·아직 로드 전·소진 뒤 재로드 전이
    ///   모두 false이고 셋 다 광고 없이 통과한다
    public func request(loaded: Bool) -> RewardedGateDecision {
        if showing { return .ignored }
        if !loaded { return .proceed }
        showing = true
        earned = false
        return .showAd
    }

    /// 사용자가 광고를 끝까지 봤다 (`userDidEarnRewardHandler`).
    public func onEarnedReward() -> RewardedGateDecision {
        // 표시 중이 아닌데 온 보상은 짝이 없는 콜백이다 — 두 번 진행시키지 않는다.
        if !showing || earned { return .ignored }
        earned = true
        return .proceed
    }

    /// 광고가 닫혔다 (`adDidDismissFullScreenContent`). 보상 뒤의 닫힘은 이미 진행 중이라 무시다.
    public func onDismissed() -> RewardedGateDecision {
        if !showing { return .ignored }
        showing = false
        let wasEarned = earned
        earned = false
        return wasEarned ? .ignored : .dismissed
    }

    /// 광고를 띄우지 못했다 (`ad(_:didFailToPresentFullScreenContentWithError:)`). 광고 없이 통과시킨다.
    public func onShowFailed() -> RewardedGateDecision {
        if !showing { return .ignored }
        showing = false
        earned = false
        return .proceed
    }
}
