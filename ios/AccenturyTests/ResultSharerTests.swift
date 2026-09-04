import AccenturyCore
import KakaoSDKTemplate
import XCTest
@testable import Accentury

/// 공유 통로의 **순서**를 못박는다 (KAN-180). 안드로이드 `ResultSharerTest.kt`와 같은 자리다.
///
/// 카카오 SDK도 `UIApplication.open`도 여기서 돌지 않는다 — 협력자를 전부 클로저로 받는
/// 설계가 그래서 있고, 검증 대상은 "어느 경우에 시트가 뜨는가" 하나다. 그 판단이 틀리면
/// 사용자는 [친구에게 공유하기]를 눌렀는데 아무 일도 일어나지 않는 화면을 보게 된다.
final class ResultSharerTests: XCTestCase {

    private let payload = SharePayload(
        imageUrl: "https://static.accentury.app/share/tier-3.png",
        text: "나 사투리 3등급 나왔다",
        webTestUrl: "https://accentury.app/t?c=kko_share"
    )

    /// 부수효과를 모아 두는 자리. 각 테스트는 "무엇이 몇 번 일어났는가"만 본다.
    private final class Spy {
        var talkQueried = 0
        var templates: [FeedTemplate] = []
        var opened: [URL] = []
        var sheets: [SharePayload] = []
    }

    /// - Parameters:
    ///   - kakaoEnabled: 앱 키가 있는가
    ///   - talkAvailable: 카톡이 깔려 있는가
    ///   - kakaoResult: 카카오가 돌려줄 값. nil이면 실패(템플릿 거부·서버 오류)다
    ///   - openSucceeds: 카톡 전환이 실제로 성공하는가
    private func makeSharer(
        spy: Spy,
        kakaoEnabled: Bool = true,
        talkAvailable: Bool = true,
        kakaoResult: URL? = URL(string: "kakaolink://send?a=b"),
        openSucceeds: Bool = true
    ) -> ResultSharer {
        ResultSharer(
            kakaoEnabled: kakaoEnabled,
            isTalkAvailable: { spy.talkQueried += 1; return talkAvailable },
            shareViaKakao: { template, onResult in
                spy.templates.append(template)
                onResult(kakaoResult, kakaoResult == nil ? TestError.rejected : nil)
            },
            openUrl: { url, completion in
                spy.opened.append(url)
                completion(openSucceeds)
            },
            presentSheet: { spy.sheets.append($0) }
        )
    }

    private enum TestError: Error { case rejected }

    // MARK: 통로 선택

    func testKakaoPathOpensTalkAndNeverShowsTheSheet() {
        let spy = Spy()
        makeSharer(spy: spy).share(payload)

        XCTAssertEqual(1, spy.templates.count)
        XCTAssertEqual([URL(string: "kakaolink://send?a=b")], spy.opened)
        XCTAssertTrue(spy.sheets.isEmpty, "카카오로 나갔는데 시트까지 떴다")
    }

    /// 키가 없으면 카톡 설치 조회조차 하지 않는다. 초기화되지 않은 SDK를 건드리는 것 자체가
    /// 사고이므로, 이 호출 횟수가 0이라는 것이 1단계 스위치가 여기까지 이어졌다는 증거다.
    func testNoKeyMeansTheSdkIsNeverTouched() {
        let spy = Spy()
        makeSharer(spy: spy, kakaoEnabled: false).share(payload)

        XCTAssertEqual(0, spy.talkQueried, "키가 없는데 카카오 SDK를 조회했다")
        XCTAssertTrue(spy.templates.isEmpty)
        XCTAssertEqual([payload], spy.sheets)
    }

    func testNoKakaoTalkFallsBackToTheSheet() {
        let spy = Spy()
        makeSharer(spy: spy, talkAvailable: false).share(payload)

        XCTAssertTrue(spy.templates.isEmpty)
        XCTAssertEqual([payload], spy.sheets)
    }

    // MARK: 폴백

    /// 카카오 쪽 실패는 공유의 끝이 아니라 통로 하나가 막힌 것이다 (템플릿 거부, 카카오 서버
    /// 오류, 앱 키·콘솔 설정 불일치). 사용자가 누른 건 "공유"지 "카톡 공유"가 아니다.
    func testKakaoFailureFallsBackToTheSheet() {
        let spy = Spy()
        makeSharer(spy: spy, kakaoResult: nil).share(payload)

        XCTAssertEqual(1, spy.templates.count, "카카오를 부르기는 했다")
        XCTAssertTrue(spy.opened.isEmpty)
        XCTAssertEqual([payload], spy.sheets)
    }

    /// 카톡 설치 조회가 참이었다고 전환까지 보장되지는 않는다 — 조회 이후에 지워졌거나
    /// 전환이 거부될 수 있다. 성공 여부를 무시하면 아무 일도 안 일어난 화면이 남는다.
    func testFailedTalkSwitchFallsBackToTheSheet() {
        let spy = Spy()
        makeSharer(spy: spy, openSucceeds: false).share(payload)

        XCTAssertEqual(1, spy.opened.count, "열기를 시도는 했다")
        XCTAssertEqual([payload], spy.sheets)
    }

    /// 카드를 만들 수 없으면 카카오를 부르지 않고 바로 시트다 (``buildShareCard(_:)`` 주석).
    /// 이 payload는 `parseSharePayload`를 통과하지 못하는 값이라 실제 흐름에서는 오지 않는다.
    func testUnbuildableCardGoesStraightToTheSheet() {
        let spy = Spy()
        let broken = SharePayload(imageUrl: "", text: "x", webTestUrl: "https://accentury.app/t")
        makeSharer(spy: spy).share(broken)

        XCTAssertTrue(spy.templates.isEmpty, "카드도 못 만들었는데 카카오를 불렀다")
        XCTAssertEqual([broken], spy.sheets)
    }

    // MARK: 템플릿 조립

    /// 안드로이드(KAN-30)와 같은 카드여야 한다는 게 이 티켓의 목적이다. 값이 어떻게 옮겨
    /// 붙는지는 Core `ShareCardTests`가 보고, 여기서는 그 값이 실제 카카오 템플릿의 어느 칸에
    /// 들어갔는지를 본다 — 본문 링크와 버튼 링크가 같다는 규약도 이 자리에서 확인된다.
    func testFeedTemplateCarriesTheCardIntoKakaoFields() throws {
        let card = try XCTUnwrap(buildShareCard(payload))
        let template = kakaoFeedTemplate(from: card)

        XCTAssertEqual("나 사투리 3등급 나왔다", template.content.title)
        XCTAssertEqual(URL(string: "https://static.accentury.app/share/tier-3.png"), template.content.imageUrl)
        XCTAssertEqual(URL(string: "https://accentury.app/t?c=kko_share"), template.content.link.webUrl)
        XCTAssertEqual(template.content.link.webUrl, template.content.link.mobileWebUrl)

        let button = try XCTUnwrap(template.buttons?.first)
        XCTAssertEqual(1, template.buttons?.count)
        XCTAssertEqual("나도 테스트하기", button.title)
        XCTAssertEqual(template.content.link.webUrl, button.link.webUrl, "본문과 버튼의 도착지가 갈렸다")
    }

    /// 안드로이드가 비워 둔 칸은 여기서도 비어 있어야 한다 — 부제(description)에 넣을 값이
    /// 점수뿐이라 두지 않았고, 이미지 규격은 확정 전이라 숫자를 박으면 실제 자산과 어긋난
    /// 비율로 잘려 나온다.
    func testFeedTemplateLeavesTheSameFieldsEmptyAsAndroid() throws {
        let card = try XCTUnwrap(buildShareCard(payload))
        let template = kakaoFeedTemplate(from: card)

        XCTAssertNil(template.content.description)
        XCTAssertNil(template.content.imageWidth)
        XCTAssertNil(template.content.imageHeight)
    }
}
