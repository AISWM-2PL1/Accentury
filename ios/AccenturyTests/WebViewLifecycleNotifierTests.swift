import XCTest
@testable import Accentury

/// 미뤄 둔 WebView 수명 통지의 순서 규칙 (KAN-108 §8, Codex 지적 2).
///
/// 통지를 한 틱 미루면서 "미룬 사이에 무슨 일이 있었는가"가 규칙이 됐는데, 그 순서는 실기기도
/// 시뮬레이터도 마음대로 만들 수 없다. 큐를 손으로 흘리면 순열을 그대로 세울 수 있다.
///
/// 상위(`TestFlowView`)의 신원 대조까지 함께 재현한다 — 규칙이 둘로 나뉘어 있어서(발행 판정은
/// 여기, 참조 교체는 상위) 어느 한쪽만 봐서는 "부모가 결국 무엇을 들고 있는가"를 말할 수 없다.
@MainActor
final class WebViewLifecycleNotifierTests: XCTestCase {

    /// 손으로 흘리는 큐. `drain()`을 부를 때까지 아무것도 돌지 않는다.
    private final class ManualQueue {
        private var blocks: [() -> Void] = []

        func schedule(_ block: @escaping () -> Void) { blocks.append(block) }

        /// 쌓인 것을 넣은 순서대로 흘린다. 도는 도중 새로 쌓인 것은 다음 호출 몫이다.
        func drain() {
            let pending = blocks
            blocks = []
            for block in pending { block() }
        }
    }

    /// 상위가 든 참조 한 칸. `TestFlowView`의 두 클로저와 같은 규칙이다.
    private final class Parent {
        private(set) var held: AnyObject?

        func onCreated(_ webView: AnyObject) { held = webView }

        /// 내가 들고 있는 인스턴스일 때만 놓는다 — 새것이 먼저 등록된 뒤 옛것이 해제되는
        /// 순서에서 방금 받은 참조를 지키는 자리다.
        func onReleased(_ webView: AnyObject) { if held === webView { held = nil } }
    }

    private func makeSubject() -> (WebViewLifecycleNotifier, ManualQueue, Parent) {
        let queue = ManualQueue()
        let notifier = WebViewLifecycleNotifier(schedule: { queue.schedule($0) })
        return (notifier, queue, Parent())
    }

    /// 생성 통지가 돌기 **전에** 해체되면 상위는 그 인스턴스를 아예 모른다.
    ///
    /// 이것이 Codex가 짚은 경합이다. 그대로 발행하면 상위가 죽은 WebView를 들고,
    /// 거기 건 `evaluateJavaScript`는 아무 데도 가지 않는다.
    func testDismantleBeforeDrainPublishesNothing() {
        let (notifier, queue, parent) = makeSubject()
        let webView = NSObject()
        var created = 0
        var released = 0

        notifier.noteCreated(webView) { created += 1; parent.onCreated($0) }
        notifier.noteDismantled(webView) { released += 1; parent.onReleased($0) }
        queue.drain()

        XCTAssertEqual(created, 0, "해체된 인스턴스의 생성 통지가 발행됐다")
        XCTAssertEqual(released, 0, "상위가 모르는 인스턴스의 해제를 알렸다")
        XCTAssertNil(parent.held)
    }

    /// 옛것이 해체되기 전에 새것이 등록된 순서 — 상위는 **새것**을 들고 있어야 한다.
    ///
    /// 로드 실패 재시도(`.id(model.attempt)`)가 만드는 순서다. 셋이 한 큐에 쌓인 뒤 함께 도는데,
    /// 옛 WebView는 자기 생성 통지가 돌기 전에 해체되므로 규칙 1이 그것을 버린다.
    func testNewCreateBeforeOldDismantleLeavesParentHoldingNew() {
        let (notifier, queue, parent) = makeSubject()
        let old = NSObject()
        let new = NSObject()

        notifier.noteCreated(old) { parent.onCreated($0) }
        notifier.noteCreated(new) { parent.onCreated($0) }
        notifier.noteDismantled(old) { parent.onReleased($0) }
        queue.drain()

        XCTAssertTrue(parent.held === new, "옛 WebView의 해제가 새 참조를 걷어냈다")
    }

    /// 옛것의 생성 통지가 **이미 발행된 뒤** 새것이 등록되고 옛것이 해제되는 순서.
    ///
    /// 앞 둘과 갈리는 지점은 여기서 옛것의 해제가 실제로 발행된다는 것이다 — 상위가 그것을 알고
    /// 있었으므로 규칙 2가 버리지 않는다. 새 참조를 지키는 것은 그래서 **상위의 신원 대조**이고,
    /// 이 순열만이 그 대조를 실제로 걸리게 한다(앞 둘은 발행 자체가 없어 대조에 닿지 않는다).
    func testAnnouncedDismantleDoesNotClearTheNewerReference() {
        let (notifier, queue, parent) = makeSubject()
        let old = NSObject()
        let new = NSObject()

        notifier.noteCreated(old) { parent.onCreated($0) }
        queue.drain()
        XCTAssertTrue(parent.held === old)

        var released = 0
        notifier.noteCreated(new) { parent.onCreated($0) }
        notifier.noteDismantled(old) { released += 1; parent.onReleased($0) }
        queue.drain()

        XCTAssertEqual(released, 1, "알린 적 있는 인스턴스의 해제는 발행돼야 한다")
        XCTAssertTrue(parent.held === new, "옛 WebView의 해제가 새 참조를 걷어냈다")
    }
}
