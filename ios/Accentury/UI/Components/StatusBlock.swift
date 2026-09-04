import SwiftUI
import UIKit

/// 상태 블록의 성격. 스크린 리더 통지 여부만 갈린다 — 색으로는 갈리지 않는다 (KAN-161).
enum StatusTone {
    case waiting
    case error
}

/// 대기·오류 문구 블록 (KAN-148). 안드로이드 `ui/components/StatusBlock.kt`의 이식본이고
/// 웹 `StatusBlock`과 같은 구성이다 — 문구 + 부연 + 선택적 복구 동작.
///
/// 오류일 때만 스크린 리더에 스스로 알린다. 화면이 이미 떠 있는 상태에서 나타나는 실패 문구는
/// 읽어 줘야 사용자가 알아챈다. 대기 문구에는 걸지 않는다 — 로딩은 곧 바뀔 상태라 매번 읽어
/// 주면 소음이 된다. 웹이 `role="alert"`를 오류에만 붙이는 것과 같은 판단이다.
///
/// 안드로이드는 `liveRegion = Assertive`를 쓴다. SwiftUI에는 뷰에 거는 라이브 리전이 없어
/// 문구가 바뀔 때 `UIAccessibility.post(.announcement:)`로 알린다 — 같은 뜻이고, 대신 "언제
/// 바뀌었나"를 화면이 직접 알아야 해서 `onChange`가 붙는다.
///
/// **색 면도, 빨강도 없다** (KAN-161 2단계). 무엇이 잘못됐는지는 잉크 문구가 말하고, 부연은
/// 대기든 오류든 흐린 잉크다. 복구 동작은 ``action``에 버튼으로 들어온다.
struct StatusBlock<Action: View>: View {

    let tone: StatusTone
    let message: String
    var detail: String?
    @ViewBuilder var action: () -> Action

    var body: some View {
        VStack(spacing: Papercut.space3) {
            Text(message)
                .papercutType(.body)
                .foregroundColor(Papercut.ink)
                .multilineTextAlignment(.center)
            if let detail {
                Text(detail)
                    .papercutType(.caption)
                    .foregroundColor(Papercut.muted)
                    .multilineTextAlignment(.center)
            }
            action()
        }
        .frame(maxWidth: .infinity)
        .onChange(of: announcement) { announce($0) }
        .onAppear { announce(announcement) }
    }

    /// 오류일 때만 읽어 줄 한 덩어리. 부연까지 함께 실어야 "무엇이 모자랐는지"가 같이 닿는다.
    private var announcement: String? {
        guard tone == .error else { return nil }
        guard let detail else { return message }
        return "\(message). \(detail)"
    }

    private func announce(_ text: String?) {
        guard let text, !text.isEmpty else { return }
        UIAccessibility.post(notification: .announcement, argument: text)
    }
}

extension StatusBlock where Action == EmptyView {
    /// 복구 동작이 없는 블록. 목소리 점검의 듣는 중 문구처럼 읽기만 하는 자리다.
    init(tone: StatusTone, message: String, detail: String? = nil) {
        self.init(tone: tone, message: message, detail: detail, action: { EmptyView() })
    }
}
