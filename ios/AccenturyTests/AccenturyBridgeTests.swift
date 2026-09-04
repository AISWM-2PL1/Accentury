import AccenturyCore
import XCTest
@testable import Accentury

/// 안드로이드 `AccenturyBridgeTest`(24건)의 이식본 중 **파싱이 아닌 15건**이다.
/// payload 파싱 자체(guideF0 관대 파싱·필드 검증 등 9건)는 Core `VoiceItemStartTests`가
/// 이미 덮으므로, 여기서는 브리지가 그 파서를 **언제 부르는가**만 본다 — origin 게이팅,
/// 라우팅, 불량 봉투 무시.
///
/// WKWebView 없이 도는 이유는 ``BridgeDispatcher``가 WebKit을 모르기 때문이다.
/// 안드로이드가 `FakeMainQueue`로 `postToMain`을 흉내 내던 자리에는 아무것도 없다 —
/// `WKScriptMessageHandler`는 메인 스레드에서 불려서 그 단계가 사라졌고, 그 단계가 지키던
/// "처리 시점의 URL로 판정한다"만 `isCurrentUrlAllowed` 클로저로 남았다.
/// `@MainActor`인 것은 ``BridgeDispatcher``가 그렇기 때문이다 — 브리지 메시지는 메인 스레드에서
/// 처리된다는 계약이 타입에 적혀 있고, 테스트도 같은 자리에서 부른다.
@MainActor
final class AccenturyBridgeTests: XCTestCase {

    /// 콜백이 받은 것을 모아 두는 상자. 테스트마다 관심 있는 칸만 본다.
    private final class Sink {
        var micPermissionCalls = 0
        var retestCalls = 0
        var starts: [VoiceItemStart] = []
        var shares: [SharePayload] = []
    }

    private func makeDispatcher(
        sink: Sink,
        isCurrentUrlAllowed: @escaping () -> Bool
    ) -> BridgeDispatcher {
        BridgeDispatcher(
            isCurrentUrlAllowed: isCurrentUrlAllowed,
            onRequestMicPermission: { sink.micPermissionCalls += 1 },
            onStartVoiceItem: { sink.starts.append($0) },
            onStartRetest: { sink.retestCalls += 1 },
            onShareResult: { sink.shares.append($0) }
        )
    }

    /// 계약을 채운 payload. 테스트마다 관심 있는 필드만 갈아끼운다 (안드로이드 `payload()` 헬퍼).
    private func voicePayload(
        itemId: String = "item_1",
        prompt: String = "마! 니 어데 가노?",
        itemNumber: Int = 1,
        totalItems: Int = 10,
        maxDurationMs: Int64 = 15_000
    ) -> String {
        """
        {"itemId":"\(itemId)","prompt":"\(prompt)","itemNumber":\(itemNumber),\
        "totalItems":\(totalItems),"maxDurationMs":\(maxDurationMs)}
        """
    }

    private let sharePayloadJson = """
        {"imageUrl":"https://cdn.accentury.app/share/grade-a.png",\
        "text":"내 등급!","webTestUrl":"https://accentury.app/?utm_source=kakao"}
        """

    // MARK: requestMicPermission

    func testAllowedOriginRunsThePermissionGateCallback() {
        let sink = Sink()
        makeDispatcher(sink: sink, isCurrentUrlAllowed: { true })
            .handle(method: "requestMicPermission", payload: nil)
        XCTAssertEqual(1, sink.micPermissionCalls)
    }

    func testOriginOutsideTheAllowlistRunsNothing() {
        let sink = Sink()
        makeDispatcher(sink: sink, isCurrentUrlAllowed: { false })
            .handle(method: "requestMicPermission", payload: nil)
        XCTAssertEqual(0, sink.micPermissionCalls)
    }

    /// 메시지를 보낸 직후 페이지가 allowlist 밖으로 리다이렉트되는 경합. 호출 시점이 아니라
    /// **처리 시점**의 값을 봐야 안전하다 (§8) — 안드로이드가 postToMain 큐로 재현하던 것을,
    /// 여기서는 판정 클로저가 처리 직전에 불린다는 사실로 못박는다.
    func testOriginIsJudgedWhenTheMessageIsHandledNotWhenItWasSent() {
        let sink = Sink()
        var allowedNow = true
        let dispatcher = makeDispatcher(sink: sink, isCurrentUrlAllowed: { allowedNow })

        allowedNow = false // 메시지가 처리되기 전에 allowlist 밖으로 이동
        dispatcher.handle(method: "requestMicPermission", payload: nil)

        XCTAssertEqual(0, sink.micPermissionCalls)
    }

    // MARK: startRetest

    func testAllowedOriginRunsTheRetestCallback() {
        let sink = Sink()
        makeDispatcher(sink: sink, isCurrentUrlAllowed: { true })
            .handle(method: "startRetest", payload: nil)
        XCTAssertEqual(1, sink.retestCalls)
    }

    func testRetestIsIgnoredOutsideTheAllowlist() {
        // 재응시는 서버 쪽 세션·결과를 즉시 폐기시키는 호출이라(KAN-107) origin 검증이 곧 보안 경계다.
        let sink = Sink()
        makeDispatcher(sink: sink, isCurrentUrlAllowed: { false })
            .handle(method: "startRetest", payload: nil)
        XCTAssertEqual(0, sink.retestCalls)
    }

    /// 연타를 브리지가 세지 않는다는 계약을 못박는다 — 진행 중이라는 사실의 주인은
    /// `SessionGateController.retestInFlight` 하나여야 한다. 두 곳에 두면 어긋나는 순간
    /// 막으려던 이중 요청이 정확히 그때 새어 나간다.
    func testDoubleTapIsFilteredBeyondTheBridgeNotInIt() {
        let sink = Sink()
        let dispatcher = makeDispatcher(sink: sink, isCurrentUrlAllowed: { true })
        dispatcher.handle(method: "startRetest", payload: nil)
        dispatcher.handle(method: "startRetest", payload: nil)
        XCTAssertEqual(2, sink.retestCalls)
    }

    // MARK: startVoiceItem

    func testAllowedOriginParsesTheItemContextAndHandsItToTheCallback() {
        let sink = Sink()
        makeDispatcher(sink: sink, isCurrentUrlAllowed: { true })
            .handle(method: "startVoiceItem", payload: voicePayload())

        XCTAssertEqual(
            [
                VoiceItemStart(
                    itemId: "item_1",
                    prompt: "마! 니 어데 가노?",
                    itemNumber: 1,
                    totalItems: 10,
                    maxDurationMs: 15_000
                )
            ],
            sink.starts
        )
    }

    func testAWellFormedPayloadIsStillIgnoredOutsideTheAllowlist() {
        let sink = Sink()
        makeDispatcher(sink: sink, isCurrentUrlAllowed: { false })
            .handle(method: "startVoiceItem", payload: voicePayload())
        XCTAssertTrue(sink.starts.isEmpty)
    }

    /// origin 검증이 파싱보다 먼저다 — allowlist 밖 페이지가 보낸 값은 내용과 무관하게
    /// 처리 대상이 아니다. 파서를 아예 부르지 않았다는 것은 콜백이 비어 있는 것으로 확인한다.
    func testOriginIsCheckedBeforeThePayloadIsParsed() {
        let sink = Sink()
        makeDispatcher(sink: sink, isCurrentUrlAllowed: { false })
            .handle(method: "startVoiceItem", payload: voicePayload(itemId: ""))
        XCTAssertTrue(sink.starts.isEmpty)
    }

    func testMalformedItemPayloadsAreIgnoredSilently() {
        let sink = Sink()
        let dispatcher = makeDispatcher(sink: sink, isCurrentUrlAllowed: { true })
        for json in ["", "{oops", "[]", #"{"itemId":"item_1"}"#] {
            dispatcher.handle(method: "startVoiceItem", payload: json)
        }
        // 화면을 그릴 수 없는 값도 같은 자리에서 걸린다 (판정은 Core parseVoiceItemStart).
        dispatcher.handle(method: "startVoiceItem", payload: voicePayload(itemId: "   "))
        dispatcher.handle(method: "startVoiceItem", payload: voicePayload(itemNumber: 11, totalItems: 10))

        XCTAssertTrue(sink.starts.isEmpty)
    }

    /// 프롬프트의 따옴표·개행·유니코드가 값 그대로 건너온다.
    func testPromptQuotesAndUnicodeSurviveVerbatim() {
        let sink = Sink()
        makeDispatcher(sink: sink, isCurrentUrlAllowed: { true }).handle(
            method: "startVoiceItem",
            payload: #"{"itemId":"item_1","prompt":"\"밥은\" 뭇나?\n마!","itemNumber":2,"totalItems":10,"maxDurationMs":15000}"#
        )
        XCTAssertEqual("\"밥은\" 뭇나?\n마!", sink.starts.first?.prompt)
    }

    // MARK: shareResult

    func testAllowedOriginParsesTheShareCard() {
        let sink = Sink()
        makeDispatcher(sink: sink, isCurrentUrlAllowed: { true })
            .handle(method: "shareResult", payload: sharePayloadJson)

        XCTAssertEqual(
            [
                SharePayload(
                    imageUrl: "https://cdn.accentury.app/share/grade-a.png",
                    text: "내 등급!",
                    webTestUrl: "https://accentury.app/?utm_source=kakao"
                )
            ],
            sink.shares
        )
    }

    func testShareIsIgnoredOutsideTheAllowlist() {
        // 이 payload는 공유 시트를 타고 앱 밖으로 나간다 — allowlist 밖 페이지가 우리 앱 이름으로
        // 링크를 뿌리는 통로가 되면 안 된다.
        let sink = Sink()
        makeDispatcher(sink: sink, isCurrentUrlAllowed: { false })
            .handle(method: "shareResult", payload: sharePayloadJson)
        XCTAssertTrue(sink.shares.isEmpty)
    }

    func testMalformedSharePayloadsAreIgnored() {
        let sink = Sink()
        let dispatcher = makeDispatcher(sink: sink, isCurrentUrlAllowed: { true })
        dispatcher.handle(method: "shareResult", payload: "{oops")
        dispatcher.handle(method: "shareResult", payload: #"{"imageUrl":"https://a/b.png","text":"x"}"#)
        // https가 아닌 링크. 판정은 Core parseSharePayload가 하고 브리지는 결과를 그대로 따른다.
        dispatcher.handle(
            method: "shareResult",
            payload: #"{"imageUrl":"https://a/b.png","text":"x","webTestUrl":"javascript:alert(1)"}"#
        )
        XCTAssertTrue(sink.shares.isEmpty)
    }

    // MARK: 봉투 자체가 우리 모양이 아닐 때 (iOS 고유)

    /// 안드로이드에는 없는 검사다. `@JavascriptInterface`는 시그니처가 곧 타입 검사라 문자열이
    /// 아닌 인자가 애초에 들어올 수 없지만, `postMessage`는 아무 JSON이나 실을 수 있다 —
    /// 게다가 페이지는 우리 브리지 객체를 건너뛰고 `window.webkit.messageHandlers`를 직접
    /// 부를 수 있어, 여기가 유일한 검문소다.
    func testNonStringPayloadsAreIgnored() {
        let sink = Sink()
        let dispatcher = makeDispatcher(sink: sink, isCurrentUrlAllowed: { true })
        dispatcher.handle(method: "startVoiceItem", payload: 42)
        dispatcher.handle(method: "startVoiceItem", payload: nil)
        dispatcher.handle(method: "startVoiceItem", payload: ["itemId": "item_1"])
        dispatcher.handle(method: "shareResult", payload: 42)
        dispatcher.handle(method: "shareResult", payload: nil)

        XCTAssertTrue(sink.starts.isEmpty)
        XCTAssertTrue(sink.shares.isEmpty)
    }

    /// 모르는 메서드는 조용히 버린다. 신버전 웹이 구버전 앱에 보낸 호출일 수도 있고
    /// (메서드 추가는 하위호환이라 계약 버전이 오르지 않는다, §5) 임의 페이지의 장난일 수도 있다.
    func testUnknownMethodsAreIgnored() {
        let sink = Sink()
        let dispatcher = makeDispatcher(sink: sink, isCurrentUrlAllowed: { true })
        dispatcher.handle(method: "", payload: nil)
        dispatcher.handle(method: "getSessionToken", payload: nil)
        dispatcher.handle(method: "evaluate", payload: "anything")

        XCTAssertEqual(0, sink.micPermissionCalls)
        XCTAssertEqual(0, sink.retestCalls)
        XCTAssertTrue(sink.starts.isEmpty)
        XCTAssertTrue(sink.shares.isEmpty)
    }

    /// 각 메서드가 자기 콜백으로만 간다 — 라우팅이 어긋나면 공유 payload로 녹음 화면이 뜬다.
    func testEachMethodRoutesToItsOwnCallbackOnly() {
        let sink = Sink()
        let dispatcher = makeDispatcher(sink: sink, isCurrentUrlAllowed: { true })
        dispatcher.handle(method: "requestMicPermission", payload: nil)
        dispatcher.handle(method: "startRetest", payload: nil)
        dispatcher.handle(method: "startVoiceItem", payload: voicePayload())
        dispatcher.handle(method: "shareResult", payload: sharePayloadJson)

        XCTAssertEqual(1, sink.micPermissionCalls)
        XCTAssertEqual(1, sink.retestCalls)
        XCTAssertEqual(1, sink.starts.count)
        XCTAssertEqual(1, sink.shares.count)
    }
}
