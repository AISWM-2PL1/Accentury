import Foundation

/// WebView 생성·해제 통지를 **언제 발행할지** 정하는 판정 (KAN-108 §8).
///
/// 통지를 한 틱 미루는 것이 이 층의 전제다. `makeUIView`·`dismantleUIView`는 SwiftUI 갱신
/// 사이클 안이라 그 자리에서 상위의 `@State`를 쓰면 값이 반영되지 않는다(§8에서 실제로
/// 겪었다 — 상위가 WebView 참조를 영영 nil로 들어 문항 결과 주입이 통째로 막혔다).
///
/// 미루는 순간 **"미룬 사이에 무슨 일이 있었는가"가 규칙이 된다.** 미뤄 둔 생성 통지가 돌기
/// 전에 그 WebView가 해체되면, 그대로 발행할 경우 상위가 이미 죽은 인스턴스를 받아 든다 —
/// 거기에 `evaluateJavaScript`를 걸면 결과가 아무 데도 가지 않는다. 그래서 해체는 **동기로**
/// 기록하고, 미뤄 둔 통지는 발행 직전에 다시 묻는다.
///
/// 판정이 여기 따로 있는 이유는 검증이다. WebKit·SwiftUI 없이 순서를 순열로 만들어 단언할 수
/// 있어야 하고(`AccenturyTests/WebViewLifecycleNotifierTests`), 그러려면 큐가 주입 가능해야 한다.
///
/// ## 규칙 셋
///
/// 1. 생성 통지는 **아직 살아 있을 때만** 발행한다 (해체가 먼저 오면 조용히 버린다).
/// 2. 해제 통지는 **생성을 알린 적이 있을 때만** 발행한다. 상위가 존재조차 모르는 인스턴스의
///    해제를 알리면, 신원 대조가 걸리지 않는 통지가 하나 늘 뿐이다.
/// 3. 상위의 신원 대조(`held === released`일 때만 놓는다)는 그대로 둔다 — WebView가 갈릴 때
///    새것이 먼저 등록되고 옛것이 뒤에 해제되는 순서를 그 대조가 받는다.
@MainActor
final class WebViewLifecycleNotifier {

    /// 미룬 일을 실제로 도는 자리. 기본값이 메인 큐이고, 테스트는 손으로 흘리는 큐를 넣는다.
    ///
    /// 블록에 `@MainActor @Sendable`을 함께 붙인 이유는 큐를 넘어가기 때문이다. 격리 표기가
    /// 없으면 `self`·WebView 같은 비-Sendable 값을 잡은 블록을 `DispatchQueue`에 넘기는 자리가
    /// 경고로 남고, 그 경고는 "이 블록이 어느 스레드에서 도는가"라는 실제 질문을 가린다.
    typealias Schedule = (@escaping @MainActor @Sendable () -> Void) -> Void

    /// 앱이 쓰는 인스턴스.
    ///
    /// Coordinator마다 두지 않고 하나로 두는 이유는 규칙 2가 **Coordinator 경계를 넘기** 때문이다.
    /// 로드 실패 재시도(`.id(model.attempt)`)는 새 Coordinator와 새 WebView를 만들고 옛것을
    /// 해체하는데, 그 둘의 통지가 한 큐에서 뒤섞인다. 상태가 인스턴스별로 갈리면 "이 WebView의
    /// 생성을 알린 적이 있는가"를 서로 모른다.
    static let shared = WebViewLifecycleNotifier()

    private let schedule: Schedule

    /// 만들어졌고 아직 해체되지 않은 WebView.
    private var live: Set<ObjectIdentifier> = []
    /// 상위에게 실제로 "생겼다"고 알린 WebView.
    private var announced: Set<ObjectIdentifier> = []

    /// - Parameter schedule: 미룬 일을 도는 자리. 기본값이 메인 큐의 **다음 회차**이고,
    ///   `updateUIView`가 상태 변경을 미루는 자리와 같은 방식이다.
    init(schedule: @escaping Schedule = { block in
        DispatchQueue.main.async { MainActor.assumeIsolated { block() } }
    }) {
        self.schedule = schedule
    }

    /// `makeUIView`가 부른다. 등록은 지금 하고 통지는 미룬다.
    ///
    /// 미뤄 둔 블록이 `webView`를 강하게 잡는다 — 발행할 때 넘겨줘야 하는 값이라 그렇고,
    /// 붙잡는 구간은 한 틱이다.
    func noteCreated(_ webView: AnyObject, publish: @escaping @MainActor (AnyObject) -> Void) {
        let id = ObjectIdentifier(webView)
        live.insert(id)
        schedule { [weak self] in
            guard let self else { return }
            // 규칙 1 — 미룬 사이에 해체됐으면 상위는 이 인스턴스를 아예 몰라야 한다.
            guard self.live.contains(id) else { return }
            self.announced.insert(id)
            publish(webView)
        }
    }

    /// `dismantleUIView`가 부른다. **기록은 동기다** — 이 한 줄이 미뤄 둔 생성 통지를 무효로 만든다.
    func noteDismantled(_ webView: AnyObject, publish: @escaping @MainActor (AnyObject) -> Void) {
        let id = ObjectIdentifier(webView)
        live.remove(id)
        schedule { [weak self] in
            guard let self else { return }
            // 규칙 2 — 알린 적 없는 것의 해제는 알리지 않는다.
            guard self.announced.remove(id) != nil else { return }
            publish(webView)
        }
    }
}
