import Foundation

/// 목소리 점검 화면의 판정기 (KAN-105 2단계). 안드로이드 `recording/VoiceCheckController.kt`의 이식본.
///
/// 시작 게이트에서 "안녕하세요" 한 마디를 듣고 두 가지를 정한다 — 이 화자의 중심 음높이
/// (``userCurveCenterHz(_:)``)와, 마이크에 목소리가 충분히 크게 닿는가. 앞엣것은 이후 모든 문항의
/// '내 억양' 곡선이 쓸 y축 중심이고, 뒤엣것은 첫 문항에서야 "소리가 너무 작아요"를 만나는 일을 없앤다.
///
/// **화면(§6b)이 아니라 여기 있는 이유**: 준비 판정("중심이 잡혔고 볼륨이 충분한가")이 이 화면의
/// 정확성을 통째로 좌우하는데, 뷰 안에 두면 시뮬레이터 없이 검증할 수 없다.
/// ``TestFlowController``·``SessionGateController``를 화면에서 떼어 둔 것과 같은 판단이다.
///
/// 상태는 불변 스냅샷 하나(``state``)다 — 화면이 읽는 값이 여러 프로퍼티로 흩어져 있으면
/// 청크 하나를 처리하는 도중의 반쪽 상태가 화면에 새어 나간다.
public final class VoiceCheckController {

    private var frames: [RecordingEngine.PitchFrame] = []

    /// 지금까지 들어온 청크 rms의 **최댓값**. 마지막 값이 아니다 — 조용히 시작해 중심만 잡히고
    /// 볼륨이 모자란 사람은 "조금 더 크게"를 보고 한 번 더 말하는데, 그 뒤 말끝이 잦아들었다고
    /// 통과가 취소되면 영영 못 지나간다. 한 번 크게 말할 수 있었다는 사실이 판정의 근거다.
    private var peakRms = 0.0

    /// 레벨 바가 보여줄 값. 이건 반대로 **최근** 청크다 — 지금 말하고 있는지가 보여야 한다.
    private var lastRms = 0.0

    public private(set) var state: VoiceCheckState

    public init() {
        state = .listening(VoiceCheckState.Listening(
            frames: [],
            level: 0,
            voicedCount: 0,
            loudEnough: false,
            centerHz: nil,
            hint: .sayIt
        ))
    }

    /// 청크 하나를 누적한다. **true를 돌려주면 판정이 끝났다는 뜻**이라 호출자가 엔진을 세운다.
    ///
    /// 정지를 여기서 직접 부르지 않는 것이 이 타입에 캡처 의존이 없는 이유다 — 엔진은
    /// 구동부(``VoiceCheckRunner``)의 것이고, 여기는 "언제 그만 들어도 되는가"만 안다.
    @discardableResult
    public func onProgress(rms: Double, newFrames: [RecordingEngine.PitchFrame]) -> Bool {
        // 이미 준비됐거나 끝난 뒤에 도착한 청크는 버린다 — 정지 요청과 실제 정지 사이에도
        // 청크가 한둘 더 오는데, 그것 때문에 방금 잠근 판정이 흔들리면 안 된다.
        guard case .listening = state else { return false }

        lastRms = rms
        if rms > peakRms { peakRms = rms }
        frames.append(contentsOf: newFrames)

        let listening = snapshot()
        // 준비 = 중심이 잠겼고 + 볼륨이 충분하다. 둘은 서로 독립이라 순서가 없다:
        // 음높이는 크기와 무관하므로, 조용히 말해 중심만 먼저 잡혀도 그 값은 유효하고
        // 이후 크게 다시 말해 볼륨만 채우면 된다(중심은 처음 8개로 잠겨 안 바뀐다).
        if let centerHz = listening.centerHz, listening.loudEnough {
            state = .ready(frames: listening.frames, centerHz: centerHz)
            return true
        }
        state = .listening(listening)
        return false
    }

    /// 듣기가 끝났다 — 10초 자동 종료(``RecordingEngine/maxDurationMs``)이거나 소스가 스스로
    /// 끝난 경우(디버그의 가짜 마이크 WAV 소진)다. 아직 준비가 아니면 ``VoiceCheckState/timedOut(frames:hint:)``이다.
    ///
    /// 두 경우를 구분해 받지 않는다 — 어느 쪽이든 "마이크는 닫혔는데 판정은 못 냈다"이고
    /// 사용자가 할 일도 [다시 시도] 하나로 같다.
    public func onStopped() {
        guard case let .listening(listening) = state else { return }
        state = .timedOut(frames: listening.frames, hint: listening.hint)
    }

    public func onFailed(_ reason: String) {
        state = .failed(reason: reason)
    }

    /// 처음부터 다시 듣는다. 쌓인 프레임·볼륨 기록을 전부 버린다.
    public func restart() {
        frames.removeAll()
        peakRms = 0
        lastRms = 0
        state = .listening(snapshot())
    }

    /// 지금까지의 누적으로 만든 ``VoiceCheckState/Listening`` 한 장.
    ///
    /// 프레임 목록은 값 복사다 — 안드로이드가 `frames.toList()`로 막던 "다음 청크의 `+=`가 이미
    /// 내보낸 스냅샷의 내용까지 바꾼다"는 사고는 Swift 배열에서 대입 자체로 막힌다.
    private func snapshot() -> VoiceCheckState.Listening {
        let centerHz = userCurveCenterHz(frames)
        let voicedCount = frames.filter { $0.voicedHz != nil }.count
        return VoiceCheckState.Listening(
            frames: frames,
            level: lastRms,
            voicedCount: voicedCount,
            loudEnough: peakRms >= AudioQuality.quietRmsThreshold,
            centerHz: centerHz,
            hint: Self.hintFor(centerHz: centerHz, voicedCount: voicedCount)
        )
    }

    /// 지금 사용자에게 건넬 한마디를 고른다.
    ///
    /// 볼륨을 마지막에 두는 순서가 핵심이다 — 중심이 안 잡힌 단계에서 "더 크게"라고 하면
    /// 크게 말해도 통과가 안 되고(프레임이 모자라서), 사용자는 자기가 뭘 잘못하는지 모른 채
    /// 소리만 키운다. 말을 더 하면 풀리는 단계에서는 말을 더 하라고 한다.
    private static func hintFor(centerHz: Float?, voicedCount: Int) -> VoiceCheckHint {
        if voicedCount == 0 { return .sayIt }
        if centerHz == nil { return .keepGoing }
        // 중심이 잡혔는데도 여기 왔다는 건 볼륨만 모자란다는 뜻이다 — 둘 다 됐으면 Ready였다.
        return .tooQuiet
    }
}

/// 점검 화면의 상태. 화면은 이 넷 중 하나만 그리고, 어느 것도 "듣는 중이면서 실패"처럼
/// 겹치지 않는다.
public enum VoiceCheckState: Equatable, Sendable {

    case listening(Listening)

    /// 중심도 잡혔고 볼륨도 충분하다. 이 상태가 되는 순간 엔진 정지가 요청된다 —
    /// 더 들어 봐야 판정이 달라지지 않는데 마이크만 잡고 있을 이유가 없다.
    case ready(frames: [RecordingEngine.PitchFrame], centerHz: Float)

    /// 마이크가 닫힐 때까지 조건을 못 채웠다. `hint`가 무엇이 모자랐는지 말한다.
    case timedOut(frames: [RecordingEngine.PitchFrame], hint: VoiceCheckHint)

    /// 녹음 자체가 실패했다(권한 회수·마이크 점유). `reason`은 엔진이 준 문구다.
    case failed(reason: String)

    /// 듣는 중. 아직 준비 조건을 못 채웠다.
    ///
    /// - `frames`: 지금까지 누적된 F0 프레임 (시각 순). 곡선을 그리는 데 쓴다
    /// - `level`: 가장 최근 청크의 rms (원 스케일) — 입력 레벨 바가 읽는다
    /// - `voicedCount`: 유성 프레임 수. ``centerMinVoicedFrames``에 닿으면 중심이 잠긴다
    /// - `loudEnough`: 지금까지의 최대 볼륨이 ``AudioQuality/quietRmsThreshold``를 넘겼는가
    /// - `centerHz`: 잠긴 중심 음높이. 아직 프레임이 모자라면 nil이다
    public struct Listening: Equatable, Sendable {
        public let frames: [RecordingEngine.PitchFrame]
        public let level: Double
        public let voicedCount: Int
        public let loudEnough: Bool
        public let centerHz: Float?
        public let hint: VoiceCheckHint

        public init(
            frames: [RecordingEngine.PitchFrame],
            level: Double,
            voicedCount: Int,
            loudEnough: Bool,
            centerHz: Float?,
            hint: VoiceCheckHint
        ) {
            self.frames = frames
            self.level = level
            self.voicedCount = voicedCount
            self.loudEnough = loudEnough
            self.centerHz = centerHz
            self.hint = hint
        }
    }
}

/// 지금 부족한 것 하나. 화면 문구가 여기서 갈린다.
///
/// 상태가 아니라 따로 있는 이유: 듣는 중에도, 시간이 다 된 뒤에도 같은 이유로 못 지나갈 수 있어
/// 두 상태가 같은 어휘를 나눠 쓴다.
public enum VoiceCheckHint: Sendable {
    /// 유성 프레임이 하나도 없다 — 아직 말을 안 했거나 목소리가 마이크에 닿지 않았다
    case sayIt

    /// 유성은 잡히는데 중심을 잠글 만큼은 아니다 — 조금만 더 말하면 된다
    case keepGoing

    /// 중심은 잡혔는데 볼륨이 모자란다 — 더 크게 한 번만 말하면 통과다
    case tooQuiet
}
