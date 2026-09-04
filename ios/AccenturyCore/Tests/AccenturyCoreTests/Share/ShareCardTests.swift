import XCTest
@testable import AccenturyCore

/// 공유 통로 판정과 카드 조립 (KAN-180). 안드로이드 `ResultSharerTest.kt`의 순수 함수 부분과
/// 같은 자리이고, 시뮬레이터·카카오 SDK 없이 `swift test`로 돈다.
final class ShareCardTests: XCTestCase {

    private let payload = SharePayload(
        imageUrl: "https://static.accentury.app/share/tier-3.png",
        text: "나 사투리 3등급 나왔다",
        webTestUrl: "https://accentury.app/t?c=kko_share"
    )

    // MARK: 통로 판정

    /// 두 조건이 **모두** 참일 때만 카카오다. 조합을 다 적는 이유는 이 함수가 카카오 경로
    /// 전체의 스위치이고, `&&`를 `||`로 잘못 고쳐도 세 조합 중 둘은 여전히 통과하기 때문이다.
    func testKakaoOnlyWhenTheKeyIsPresentAndTalkIsInstalled() {
        XCTAssertEqual(.kakao, chooseShareChannel(kakaoEnabled: true, talkAvailable: true))
        XCTAssertEqual(.systemSheet, chooseShareChannel(kakaoEnabled: true, talkAvailable: false))
        XCTAssertEqual(.systemSheet, chooseShareChannel(kakaoEnabled: false, talkAvailable: true))
        XCTAssertEqual(.systemSheet, chooseShareChannel(kakaoEnabled: false, talkAvailable: false))
    }

    // MARK: 카드 조립

    func testCardCarriesThePayloadValuesVerbatim() {
        let card = buildShareCard(payload)
        XCTAssertEqual("나 사투리 3등급 나왔다", card?.title)
        XCTAssertEqual(URL(string: "https://static.accentury.app/share/tier-3.png"), card?.imageUrl)
        XCTAssertEqual(URL(string: "https://accentury.app/t?c=kko_share"), card?.linkUrl)
    }

    /// 카드 본문과 버튼은 같은 곳으로 가야 한다. 다르면 사용자가 어디를 눌렀느냐에 따라
    /// 캠페인 유입 집계가 갈린다 — 안드로이드 `buildFeedTemplate`이 링크 하나를 둘에 나눠
    /// 쓰는 것과 같은 규약이고, 이 테스트가 그 규약의 자리다.
    func testTheCardLinkIsTheOnlyDestination() {
        let card = buildShareCard(payload)
        XCTAssertEqual(URL(string: payload.webTestUrl), card?.linkUrl)
    }

    /// 버튼 문구는 안드로이드와 같은 값이어야 한다. 두 플랫폼의 카드가 달라 보이면 안 된다는
    /// 게 이 티켓의 목적이므로, 문구가 갈리는 순간 티켓이 무의미해진다.
    func testButtonTitleMatchesAndroid() {
        XCTAssertEqual("나도 테스트하기", buildShareCard(payload)?.buttonTitle)
        XCTAssertEqual(shareButtonTitle, buildShareCard(payload)?.buttonTitle)
    }

    /// `URL(string:)`이 받아 주지 않는 값이면 nil이다 — 부르는 쪽이 시트로 내려간다.
    /// 강제 언래핑이었다면 결과 화면에서 앱이 죽는 자리다.
    ///
    /// 여기 쓰는 값은 ``parseSharePayload(_:)``를 통과하지 못하는 것들이라 실제 흐름에서는
    /// 오지 않는다. 그래도 확인하는 이유는 두 함수가 서로 다른 파서 위에 서 있어서다 —
    /// 판정이 어긋나는 날 이 함수가 크래시가 아니라 폴백으로 끝나는지가 여기서 정해진다.
    func testUnparseableUrlsFallThrough() {
        XCTAssertNil(buildShareCard(SharePayload(imageUrl: "", text: "x", webTestUrl: "https://accentury.app/t")))
        XCTAssertNil(buildShareCard(SharePayload(imageUrl: "https://a/b.png", text: "x", webTestUrl: "")))
    }
}
