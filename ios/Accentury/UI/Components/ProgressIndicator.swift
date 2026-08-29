import SwiftUI

/// 도트 하나의 상태. 안드로이드 `ProgressDotState`의 이식본이다.
enum ProgressDotState: Equatable {
    case done
    case current
    case todo
}

/// 몇 번째 칸이 어떤 상태인가. 계산을 화면에서 떼어 둔 이유는 경계다 — `position == current`가
/// 현재 칸이고 그보다 앞이 완료인데, 부등호를 한 칸 잘못 쓰면 진행이 통째로 밀린다. 화면에서는
/// "진행이 한 칸 밀렸다"로만 드러나 원인을 찾기 어려운 종류라 테스트가 경계를 고정한다
/// (`ProgressDotStateTests`, 안드로이드 `ui/ProgressDotStateTest.kt`).
func progressDotState(position: Int, current: Int) -> ProgressDotState {
    if position < current { return .done }
    if position == current { return .current }
    return .todo
}

/// 진척도 (KAN-148, 형태는 KAN-161 2단계). 안드로이드 `ui/components/ProgressIndicator.kt`의
/// 이식본이고 웹 `ProgressIndicator`와 같은 구성이다 — 도트 줄과 "3 / 10" 표기를 한 덩어리로
/// 묶는다. 둘이 떨어져 있으면 한쪽만 고쳐 숫자와 도트가 어긋나는 날이 온다.
///
/// 세 상태를 색이 아니라 **형태**로 가른다 (정본 §7·§8): 완료는 잉크로 찬 캡슐, 현재는 테두리가
/// 두꺼워지고 왼쪽 절반만 찬 캡슐, 미완료는 빈 캡슐이다. ``Papercut/paperShadow``(#cfc5aa)로
/// 남은 칸을 칠하지 않는다 — 크림 위 1.46:1이라 안 보인다.
///
/// ``current``가 1부터 시작하는 건 호출자 몫이자 의도다 — 첫 문항을 0/10으로 보이면 아직
/// 시작도 안 한 느낌이라 이탈이 는다 (`ux-ui.md` §3 Goal-Gradient).
struct ProgressIndicator: View {

    let current: Int
    let total: Int
    var label: String = "문항 진행률"
    /// 웹 캡션이 "3 / 10 · 음성"이라 여기서만 종류를 빼면 같은 자리의 같은 줄이 화면을
    /// 넘어갈 때마다 길어졌다 짧아진다.
    var note: String?

    var body: some View {
        VStack(alignment: .trailing, spacing: Papercut.space2) {
            HStack(spacing: Papercut.space1) {
                ForEach(0..<max(total, 0), id: \.self) { index in
                    dot(progressDotState(position: index + 1, current: current))
                }
            }
            .frame(maxWidth: .infinity)
            /*
             * 값을 읽는 것은 이 줄 하나다. 도트 열 개가 각각 읽히면 스크린 리더가 같은 정보를
             * 열 번 말하므로 줄 전체에 "3 / 10"을 통째로 실어 한 번만 읽히게 하고, 아래 숫자는
             * 의미론에서 뺀다(시각적으로는 남는다).
             */
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(label) \(current) / \(total)")

            Text(note == nil ? "\(current) / \(total)" : "\(current) / \(total) · \(note!)")
                .papercutType(.caption)
                .foregroundColor(Papercut.muted)
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity)
    }

    private func dot(_ state: ProgressDotState) -> some View {
        let shape = Capsule()
        return shape
            .fill(state == .done ? Papercut.ink : Papercut.cream)
            /*
             * 현재 칸만 왼쪽 절반이 차 있다. 배경 위에 사각형 하나를 얹을 뿐이라 번지는 면이
             * 아니고, 바깥의 clipShape가 캡슐 모양으로 잘라 준다 — 웹이 50%에서 딱 끊기는
             * linear-gradient로 그리는 것과 같은 결과다.
             */
            .overlay(alignment: .leading) {
                if state == .current {
                    GeometryReader { proxy in
                        Papercut.ink.frame(width: proxy.size.width / 2)
                    }
                }
            }
            .clipShape(shape)
            .overlay(shape.stroke(Papercut.ink, lineWidth: state == .current ? Papercut.borderStrong : Papercut.borderHairline))
            .frame(height: Papercut.progressDotHeight)
    }
}
