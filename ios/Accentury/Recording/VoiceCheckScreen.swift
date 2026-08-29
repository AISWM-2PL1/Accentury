import AccenturyCore
import SwiftUI

/// 목소리 점검 화면 (KAN-105 2단계). 안드로이드 `recording/VoiceCheckScreen.kt`의 이식본이다.
/// 시작 게이트의 두 번째 칸이다 — 마이크 권한(KAN-98) 뒤, 세션 생성(KAN-34) 앞.
///
/// "안녕하세요" 한 마디로 두 가지를 끝낸다. 하나는 이 화자의 중심 음높이다 — 이후 모든 문항의
/// '내 억양' 곡선이 이 값을 y축 중심으로 쓰므로, 미리 재 두면 첫 문항의 첫 음절부터 곡선이
/// 제자리에서 그려진다. 문항마다 그 녹음의 앞부분으로 중심을 잡으면 문항끼리 축이 달라져
/// "내 억양"이 문항마다 다른 높이에 놓인다. 다른 하나는 볼륨 확인이다 — 마이크가 멀거나 막혀
/// 소리가 작은 상태를, 결과에 반영되는 첫 문항이 아니라 여기서 알아채게 한다.
///
/// 화면이 이 자리에 서는 이유: 마이크가 방금 열려 확인할 것이 바로 앞에 있고, 아직 네트워크를
/// 쓰기 전이라 실패할 구석이 없다(전부 기기 안에서 끝난다). 세션 뒤로 밀면 이미 발급된 세션을
/// 든 채 점검에 붙들리는 구간이 생긴다.
///
/// 판정은 전부 ``AccenturyCore/VoiceCheckController``가 하고 여기는 그 상태를 그리기만 한다.
struct VoiceCheckScreen: View {

    @ObservedObject var model: VoiceCheckModel

    /// 잰 중심 음높이를 호출자에게 넘긴다 — 이 값이 문항 화면의 centerHz가 된다.
    let onDone: (Float) -> Void

    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(spacing: 0) {
            Text("목소리를 확인할게요")
                .papercutType(.headline)
                .foregroundColor(Papercut.ink)
                .multilineTextAlignment(.center)

            Spacer().frame(height: Papercut.space2)
            Text("아래 말을 편하게 해 주세요")
                .papercutType(.bodySmall)
                .foregroundColor(Papercut.muted)
                .multilineTextAlignment(.center)

            Spacer().frame(height: Papercut.space4)
            // 문항 화면과 같은 카드다 — 여기서 말한 방식 그대로 문항에서도 말하면 된다는 뜻이 된다.
            PromptCard(caption: "목소리 점검", prompt: "안녕하세요")

            Spacer().frame(height: Papercut.space4)
            /*
             * 레인은 하나다. 점검에는 따라 할 가이드가 없으므로(자기 목소리만 재는 자리)
             * 빈 가이드 레인을 함께 세우면 사용자는 없는 곡선을 찾게 된다.
             * 레인 하나여도 상자는 문항 화면과 같다 — 테두리·모서리는 상자가 갖는다.
             */
            CurveLaneGroup {
                CurveLaneView(label: "내 억양", variant: .user)
            }

            Spacer().frame(height: Papercut.space4)
            InputLevelBar(level: listeningLevel)

            Spacer().frame(height: Papercut.space4)
            StatusBlock(
                // 실패에만 error를 준다 — 그래야 스크린 리더가 스스로 읽는다.
                // 듣는 중 문구는 청크마다 바뀌므로 읽어 주면 소음이 된다.
                tone: isFailed ? .error : .waiting,
                message: Self.statusMessage(model.state),
                // 시간이 다 됐을 때만 무엇이 모자랐는지 덧붙인다 — "잡히지 않았어요"만으로는
                // 다음에 무엇을 다르게 해야 하는지 알 수 없다.
                detail: timedOutHint
            )

            Spacer(minLength: 0)

            action

            Spacer().frame(height: Papercut.space4)
            Text("이 소리는 저장하거나 보내지 않아요")
                .papercutType(.caption)
                .foregroundColor(Papercut.muted)
                .multilineTextAlignment(.center)
            Spacer().frame(height: Papercut.space8)
        }
        .padding(Papercut.space4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Papercut.cream.ignoresSafeArea())
        // 권한은 앞 칸에서 받았으니 버튼 없이 진입 즉시 듣는다 — 여기서 한 번 더 누르게 하면
        // "말하세요"라는 안내와 "시작하세요"라는 버튼이 서로를 가린다.
        .onAppear { model.start() }
        // 화면이 걷히면 마이크를 놓는다. 모델은 이 화면보다 오래 살아서 스스로는 안 끝난다.
        .onDisappear { model.leave() }
        /*
         * 앱이 뒤로 가도 마이크를 놓는다. 점검은 사용자가 화면을 보며 말하는 절차라, 화면이
         * 가려진 동안 마이크를 쥐고 있을 이유가 없다 — iOS는 백그라운드 오디오 권한 없이도
         * 잠깐은 세션이 살아 있어서, 안 놓으면 상태 표시줄에 마이크 표시만 남는다.
         * 돌아오면 다시 듣는다 (`start()`는 끝난 판정을 되돌리지 않으므로 안전하다).
         */
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.start() } else { model.leave() }
        }
        #if DEBUG
        /*
         * `-AutoGateSmoke 1` — [다음]을 실행 인자로 대신 누른다. 시뮬레이터에는 좌표 입력이
         * 없어서(`xcrun simctl`) 이 화면을 손으로 넘길 방법이 없다.
         *
         * 잰 값이 있으면 그 값을 넘긴다 — 가짜 마이크 WAV를 물린 빌드는 스스로 통과하므로
         * 실제 측정치가 뒤 화면까지 흐른다. 통과하지 못하면(마이크가 없는 시뮬레이터, 무음
         * 소스) 자리 표시 0으로 넘겨 뒤 화면(세션 게이트·테스트 진입)만 확인한다. 0은 "중심
         * 없음"과 같은 뜻이라 곡선이 첫 유성 프레임으로 축을 다시 잡는다 (§7).
         */
        .task {
            guard UserDefaults.standard.bool(forKey: "AutoGateSmoke") else { return }
            let deadline = Date().addingTimeInterval(5)
            while Date() < deadline {
                if case .ready(_, let centerHz) = model.state {
                    onDone(centerHz)
                    return
                }
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            onDone(0)
        }
        #endif
    }

    // MARK: - 조각

    @ViewBuilder
    private var action: some View {
        switch model.state {
        case .ready(_, let centerHz):
            AccenturyButton(text: "다음", fillsWidth: true) { onDone(centerHz) }

        case .timedOut, .failed:
            AccenturyButton(text: "다시 시도", fillsWidth: true) { model.restart() }

        // 듣는 중에는 버튼이 없다 — 지금 사용자가 할 일은 말하는 것 하나뿐이라,
        // 누를 것을 주면 말하기를 멈추고 그걸 누른다.
        case .listening:
            EmptyView()
        }
    }

    private var listeningLevel: Double {
        if case .listening(let listening) = model.state { return listening.level }
        return 0
    }

    private var isFailed: Bool {
        switch model.state {
        case .timedOut, .failed: return true
        default: return false
        }
    }

    private var timedOutHint: String? {
        if case .timedOut(_, let hint) = model.state { return Self.hintMessage(hint) }
        return nil
    }

    /// 상태 한 줄. 비난 없이, 지금 할 일 하나만 말한다.
    private static func statusMessage(_ state: VoiceCheckState) -> String {
        switch state {
        case .listening(let listening): return hintMessage(listening.hint)
        case .ready: return "좋아요, 목소리가 잘 들려요"
        case .timedOut: return "목소리가 잡히지 않았어요"
        // 엔진이 준 문구를 그대로 쓴다 — 마이크가 왜 안 열렸는지는 앱이 지어낼 수 없다.
        case .failed(let reason): return reason
        }
    }

    private static func hintMessage(_ hint: VoiceCheckHint) -> String {
        switch hint {
        case .sayIt: return "'안녕하세요'라고 말해 주세요"
        case .keepGoing: return "조금만 더요"
        case .tooQuiet: return "조금 더 크게 말해 주세요"
        }
    }
}

/// 입력 레벨 바. 곡선이 "무엇을 말했는가"라면 이건 "얼마나 크게 말했는가"다 —
/// 볼륨 부족은 곡선만 봐서는 알 수 없다(작게 말해도 F0는 잡힌다).
///
/// 눈금은 통과선(``AccenturyCore/AudioQuality/quietRmsThreshold``)이다. "조금 더 크게"라는 말만
/// 으로는 얼마나 더인지 알 수 없어서, 넘어야 할 자리를 눈에 보이게 둔다.
private struct InputLevelBar: View {

    let level: Double

    var body: some View {
        let fraction = Self.barFraction(level)
        let threshold = Self.barFraction(AudioQuality.quietRmsThreshold)

        /*
         * 그릇은 면이 아니라 **테두리**로 그린다 (KAN-161 4단계, 웹 `.level-bar`와 같은 규격).
         * 종이 그림자 색(#cfc5aa) 면이었는데 크림 위 1.46:1이라 바의 오른쪽 끝이 어디인지
         * 보이지 않았다 — 채운 만큼만 보이고 전체 길이가 안 보이면 "얼마나 더 크게"를 알 수 없다.
         */
        return GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Papercut.cream)
                Capsule()
                    .fill(Papercut.ink)
                    .frame(width: proxy.size.width * fraction)
                // 눈금은 채움 위에 그린다 — 채움이 눈금을 넘어선 순간에도 선이 보여야 통과가 읽힌다.
                Papercut.muted
                    .frame(width: 2)
                    .offset(x: proxy.size.width * threshold - 2)
            }
            .clipShape(Capsule())
            .overlay(Capsule().stroke(Papercut.ink, lineWidth: Papercut.borderHairline))
        }
        .frame(height: Papercut.progressBarHeight)
        // 값이 아니라 뜻만 읽힌다 — 시시각각 바뀌는 숫자를 읽어 주면 화면을 못 쓴다.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("입력 레벨")
    }

    /// 원 스케일 rms를 바의 0..1 비율로. **로그 스케일**이다 — 사람이 느끼는 크기가 로그라서,
    /// 선형으로 그리면 일상적인 발화(rms 수백)가 전체 스케일(32768) 대비 바 왼쪽 끝에 붙어 버려
    /// 커졌는지 작아졌는지가 안 보인다.
    private static func barFraction(_ rms: Double) -> CGFloat {
        guard rms > 1 else { return 0 }
        return CGFloat(min(max(log10(rms) / log10(AudioQuality.fullScale), 0), 1))
    }
}
