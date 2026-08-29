import Foundation

/// 녹음 화면이 그리는 상태 하나. 안드로이드 `recording/RecordingUiState.kt`의 이식본이다.
///
/// 재생(플레이백)이 없는 것은 빠진 것이 아니라 결정이다 (FR-AD-09) — 검토 화면의 선택지는
/// [재녹음]과 [다음] 둘뿐이다.
public enum RecordingUiState: Equatable, Sendable {

    case idle

    case recording(Recording)

    case review(Review)

    case failed(reason: String)

    /// 녹음 중.
    ///
    /// - `pitchFrames`: 녹음 시작부터 지금까지 누적된 F0 프레임 (시각 순).
    ///   청크마다 새로 온 몇 개가 아니라 전부인 이유는 곡선을 매번 다시 그리기 때문이다.
    public struct Recording: Equatable, Sendable {
        public let elapsedMs: Int64
        public let rms: Double
        public let pitchFrames: [RecordingEngine.PitchFrame]

        public init(elapsedMs: Int64, rms: Double, pitchFrames: [RecordingEngine.PitchFrame] = []) {
            self.elapsedMs = elapsedMs
            self.rms = rms
            self.pitchFrames = pitchFrames
        }

        /// 지금이 8초 경고 구간인가. 판정은 ``isCountdownWarning(elapsedMs:maxDurationMs:)``이 하고
        /// 여기는 상한만 채운다 — 경계와 반올림이 웹과 같은 값이어야 해서 규칙을 한 자리에 뒀다
        /// (`RecordingCountdown.swift`).
        ///
        /// `elapsedMs >= 8_000`을 직접 적던 것을 "남은 시간 2초 이하"로 바꿨다. 값이 같아
        /// 동작은 그대로지만, 상한이 문항마다 달라지는 날 비율도 절대값도 아닌 **남은 시간**이
        /// 옳은 기준이라는 것이 식에 남는다.
        public var countdownActive: Bool {
            isCountdownWarning(elapsedMs: elapsedMs, maxDurationMs: RecordingEngine.maxDurationMs)
        }

        /// 캡슐에 적을 "N초 남음"의 N.
        public var remainingSeconds: Int {
            AccenturyCore.remainingSeconds(elapsedMs: elapsedMs, maxDurationMs: RecordingEngine.maxDurationMs)
        }

        /// 경과 표기 `00:04`.
        public var elapsedLabel: String { formatElapsed(elapsedMs) }
    }

    /// 검토 중. 선택지는 [재녹음]과 [다음]뿐이다 (FR-AD-09 — 재생 없음).
    ///
    /// - `pitchFrames`: 방금 끝난 녹음의 F0 프레임 전체. 재녹음과 다음을 고르는 화면이
    ///   자기 억양을 가이드와 비교할 순간이라, 곡선을 지우지 않고 남겨 둔다.
    public struct Review: Equatable, Sendable {
        public let attemptId: String
        public let durationMs: Int64
        public let quality: QualityStatus
        public let autoStopped: Bool
        public let pitchFrames: [RecordingEngine.PitchFrame]

        public init(
            attemptId: String,
            durationMs: Int64,
            quality: QualityStatus,
            autoStopped: Bool,
            pitchFrames: [RecordingEngine.PitchFrame] = []
        ) {
            self.attemptId = attemptId
            self.durationMs = durationMs
            self.quality = quality
            self.autoStopped = autoStopped
            self.pitchFrames = pitchFrames
        }

        /// [다음]을 세울지 여부 (FR-AD-08). 품질 판정이 통과일 때만 앞으로 갈 수 있다.
        public var canProceed: Bool { quality == .normal }
    }
}
