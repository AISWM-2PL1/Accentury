import AccenturyCore
import XCTest
@testable import Accentury

/// 앱 계층 테스트. 링크를 읽는 판정 자체는 `AccenturyCoreTests/Web/AppLinkTests`가 덮으므로,
/// 여기서 확인하는 것은 그 위에 얹힌 결선이다 (KAN-32 3단계) — 링크가 준 계측 코드가
/// **진입 URL과 저장 양쪽에** 남는가, 그리고 링크가 아닌 URL이 그 값을 지우지 않는가.
///
/// 안드로이드는 이 자리를 테스트하지 않는다. 그쪽은 OS가 VIEW Intent를 다시 배달해 주므로
/// 저장이 없고, `applyAppLink`가 하는 일이 `parseAppLink` 호출 한 줄이기 때문이다. iOS는
/// 진입 URL을 다시 주지 않아 값을 직접 적어야 하고(``TestFlowModel/campaignToken``),
/// 그 저장·복원이 여기서 생긴 새 코드다.
@MainActor
final class TestFlowModelAppLinkTests: XCTestCase {

    private var suiteName = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        // 테스트마다 새 스위트를 판다 — `.standard`를 쓰면 시뮬레이터에 남은 앞 실행의
        // 진행 저장이 결과를 흔든다 (`PermissionGateModelTests`와 같은 방식).
        suiteName = "TestFlowModelAppLinkTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    /// 세션 클라이언트는 주입하지 않는다 — 이 테스트가 보는 것은 링크에서 URL까지의 구간이라
    /// 서버를 부를 일이 없고, 기본값을 쓰면 실제 `URLSession` 클라이언트가 만들어진다.
    private func model() -> TestFlowModel {
        TestFlowModel(defaults: defaults, sessionClient: nil, isMicGranted: { true })
    }

    /// (a) 공유 링크가 준 계측 코드는 웹 진입 URL에 실려 나간다.
    func testShareLinkCampaignTokenRidesIntoTheWebUrl() {
        let model = model()

        model.applyAppLink(URL(string: "https://accentury.app/t?c=kko_share"))

        XCTAssertEqual("kko_share", model.campaignToken)
        XCTAssertTrue(model.webUrl.contains("&c=kko_share"), model.webUrl)
    }

    /// (b) 링크가 아닌 URL은 이미 든 코드를 지우지 않는다.
    ///
    /// 링크로 들어와 응시하다 결과 화면의 `/privacy`를 눌러 사파리에 갔다 오는 흐름이 정확히
    /// 이 경우다 — 거기서 값을 비우면 앱을 잠깐 벗어난 것만으로 유입 계측이 사라진다.
    /// 안드로이드 `applyAppLink`가 런처 탭의 MAIN Intent에 값을 비우지 않는 것과 같은 판단이다.
    func testNonEntryUrlDoesNotClearTheCampaignToken() {
        let model = model()
        model.applyAppLink(URL(string: "https://accentury.app/t?c=kko_share"))

        model.applyAppLink(URL(string: "https://accentury.app/privacy"))

        XCTAssertEqual("kko_share", model.campaignToken)
    }

    /// (c) 프로세스가 죽었다 떠도 코드가 돌아온다.
    ///
    /// iOS는 앱을 띄운 `NSUserActivity`를 다시 주지 않는데, 세션과 흐름은 같은 저장에서
    /// 복원된다 — 코드만 사라지면 복원된 응시가 링크로 들어온 사람의 것인데도 유입 없는
    /// 것으로 남는다. 저장이 안드로이드에 없는 이 자리에만 있는 이유다.
    func testCampaignTokenSurvivesProcessDeath() {
        model().applyAppLink(URL(string: "https://accentury.app/t?c=kko_share"))

        let restored = TestFlowModel(defaults: defaults, sessionClient: nil, isMicGranted: { true })

        XCTAssertEqual("kko_share", restored.campaignToken)
        XCTAssertTrue(restored.webUrl.contains("&c=kko_share"), restored.webUrl)
    }

    /// (d) allowlist 밖 호스트가 지어낸 링크는 진입이 아니다 — §7의 보안 경계가 이 입구에도 선다.
    func testHostOutsideTheAllowlistNeverSetsACampaignToken() {
        let model = model()

        model.applyAppLink(URL(string: "https://evil.example/t?c=x"))

        XCTAssertNil(model.campaignToken)
        XCTAssertFalse(model.webUrl.contains("&c="), model.webUrl)
    }
}
