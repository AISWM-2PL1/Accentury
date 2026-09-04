import AccenturyCore
import SwiftUI

/// 세션 확보 화면 (KAN-34). 안드로이드 `session/SessionGateScreen.kt`의 이식본이다.
/// 목소리 점검(KAN-105)을 지난 뒤 테스트 진입 URL을 열기 전에 선다.
///
/// `WebViewHost`의 로딩·실패 화면과 같은 자리를 차지한다 — WebView는 아래에서 인트로를 든 채
/// 살아 있고, 이 화면은 그 위를 덮을 뿐이다. 세션을 받으면 화면이 걷히고 같은 WebView가 테스트
/// 진입 URL로 이어 로드한다.
///
/// ## 요청을 화면이 거는 이유
///
/// 세션 생성은 사용자가 기다리는 화면과 수명이 같다. 화면 밖에서 걸면 화면이 사라진 뒤에도
/// 도는 요청과 그 결과를 받을 자리를 따로 관리해야 하는데, 여기서는 이탈이 곧 취소이고
/// (SwiftUI `.task`의 취소가 `URLSession`까지 내려간다) 다시 들어오면 다시 건다.
///
/// 상태와 요청은 ``TestFlowModel``이 든 Core ``AccenturyCore/SessionGateController``가 갖는다 —
/// 안드로이드는 컨트롤러와 클라이언트를 화면에 직접 넘기지만, iOS에서는 그 둘이 이미 모델에
/// 모여 있어(진입 URL·브리지 토큰이 같은 세션에서 나온다) 화면은 모델만 본다.
///
/// ## 안드로이드와 갈리는 두 지점
///
/// 1. **[처음으로]가 재시도 가능한 실패에도 선다.** 안드로이드는 재시도 불가(`unsupported`)에만
///    인트로 복귀를 주고 나머지에는 [다시 시도] 하나만 준다. 그런데 429·서버 오류가 이어지면
///    이 게이트 뒤에는 인트로로 돌아갈 길이 없어 사용자가 갇힌다 — 앱을 지우지 않는 한. 그래서
///    복귀를 늘 두되 무게를 낮춰(텍스트 버튼) 어느 쪽을 권하는지는 그대로 남긴다.
/// 2. **429에는 남은 시간을 세어 보여준다.** 안드로이드는 "N초 뒤에 다시 눌러 주세요"라고 적어
///    두기만 해서, 사용자가 곧바로 눌러 같은 429를 한 번 더 받는다. 서버가 알려준 시간이 있는데
///    그걸 문장으로만 쓰는 것은 아까운 자리라, 그동안 버튼을 잠그고 숫자를 줄인다.
///    올림은 Core가 이미 해 뒀다 (``AccenturyCore/SessionGateState/failed(reason:retryAfterSeconds:)``의
///    `retryAfterSeconds`가 `ceilSeconds`를 지난 값이다).
struct SessionGateScreen: View {

    @ObservedObject var model: TestFlowModel

    /// 다시 시도해도 소용없는 실패에서 인트로로 돌려보낸다.
    let onBackToIntro: () -> Void

    var body: some View {
        Group {
            switch model.gateState {
            case .failed(let reason, let retryAfterSeconds):
                FailureScreen(
                    reason: reason,
                    retryAfterSeconds: retryAfterSeconds,
                    onRetry: { model.retrySession() },
                    onBackToIntro: onBackToIntro
                )

            // 확보 직후 한 프레임은 여기로 올 수 있다 — 상위가 세션을 보고 이 화면을 걷어내기
            // 직전이라, 준비 중 표시를 그대로 두는 것이 화면이 덜컥거리지 않는 쪽이다.
            case .creating, .ready:
                PreparingScreen()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // 색을 명시한다 — 그대로 두면 이 화면만 시스템 배경이 되어 바로 앞뒤 WebView 화면
        // (background #f3ecd9)과 어긋난다 (녹음 오버레이와 같은 이유).
        .background(Papercut.cream.ignoresSafeArea())
        // 게이트가 화면에 서 있는 동안 요청을 건다. attempt가 바뀌면(=[다시 시도]) 다시 건다 —
        // 상태만 되돌리면 이미 한 번 돈 이펙트가 다시 돌 이유가 없어 준비 중인 채로 멈춘다.
        .task(id: model.gateAttempt) { await model.createSessionIfNeeded() }
    }
}

/// 준비 중 화면. WebView 로딩 화면(`webview-layer.md` §10 Q5)과 같은 구성이다 — 빈 화면·흰
/// 플래시를 노출하지 않는 것이 목적이라 문구와 스피너 한 쌍이면 충분하다.
private struct PreparingScreen: View {

    var body: some View {
        VStack(spacing: Papercut.space3) {
            Text("테스트를 준비하고 있어요")
                .papercutType(.title)
                .foregroundColor(Papercut.ink)
            ProgressView()
                .progressViewStyle(.circular)
                .tint(Papercut.ink)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// 실패 화면. 웹 로드 실패 화면과 같은 ``StatusBlock`` 구성이다 — 비난 없는 문구 + 지금 할 수
/// 있는 동작 하나. 문구는 안드로이드 `FailureScreen`을 그대로 옮겼다.
private struct FailureScreen: View {

    let reason: SessionFailureReason
    let retryAfterSeconds: Int64?
    let onRetry: () -> Void
    let onBackToIntro: () -> Void

    /// 남은 대기 시간(초). 서버가 알려준 값에서 1초씩 줄어든다.
    @State private var remaining: Int64 = 0

    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: Papercut.space3) {
            StatusBlock(tone: .error, message: message, detail: detail) {
                VStack(spacing: Papercut.space2) {
                    if !isUnsupported {
                        AccenturyButton(text: retryLabel, enabled: remaining <= 0, action: onRetry)
                    }
                    AccenturyButton(
                        text: "처음으로",
                        variant: isUnsupported ? .primary : .text,
                        action: onBackToIntro
                    )
                }
            }
        }
        .padding(Papercut.space4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // 실패가 바뀔 때마다(다른 이유·다른 대기 시간) 카운트다운을 새로 잡는다.
        .onAppear { remaining = retryAfterSeconds ?? 0 }
        .onChange(of: retryAfterSeconds) { remaining = $0 ?? 0 }
        .onReceive(tick) { _ in if remaining > 0 { remaining -= 1 } }
    }

    /// 서버가 재시도 불가로 못박은 거절. 같은 요청을 다시 보내도 같은 답이 오므로 버튼이
    /// 거짓말이 된다 — 업로드 상태 바가 재시도 불가 실패에 버튼을 주지 않는 것과 같은 판단이다.
    private var isUnsupported: Bool { reason == .unsupported }

    private var retryLabel: String {
        remaining > 0 ? "다시 시도 (\(remaining)초)" : "다시 시도"
    }

    private var message: String {
        switch reason {
        case .rateLimited: return "잠시 뒤에 시작할 수 있어요"
        case .network: return "연결이 불안정해요"
        case .server: return "테스트를 시작하지 못했어요"
        case .unsupported: return "지금은 테스트를 시작할 수 없어요"
        }
    }

    private var detail: String {
        switch reason {
        case .rateLimited:
            if remaining > 0 {
                return "접속이 몰리고 있어요 · \(remaining)초 뒤에 다시 눌러 주세요"
            }
            return "접속이 몰리고 있어요 · 잠시 뒤에 다시 눌러 주세요"
        case .network: return "네트워크를 확인하고 다시 시도해 주세요"
        case .server: return "잠시 뒤에 다시 시도해 주세요"
        case .unsupported: return "앱을 최신 버전으로 업데이트한 뒤 다시 열어 주세요"
        }
    }
}
