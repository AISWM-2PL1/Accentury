import Foundation

/// 녹음 카운트다운 계산 (KAN-161 4단계). 안드로이드 `recording/RecordingCountdown.kt`의 이식본.
/// 화면이 "지금 몇 초인가"와 "경고를 띄울 때인가"를 판정하는 데 쓰는 순수 함수 묶음이다.
///
/// 뷰 안에 두지 않은 이유는 경계 때문이다. 여기서 틀리면 화면에는 "8초에 경고가 안 떴다"나
/// "3초 남았다고 나온다"로만 드러나는데, 그 증상을 만드는 것은 부등호 하나와 반올림 방향
/// 하나다 — 사람 눈으로는 매번 스톱워치를 들고 확인해야 하지만 함수로 빼면 경계 네 개를
/// 테스트가 고정한다 (`RecordingCountdownTests`).
///
/// 값과 규칙은 웹 `web/src/progress/WebVoiceRecorder.tsx`·안드로이드와 같다. 한 테스트 안에서
/// 네이티브 녹음 화면과 웹 녹음 패널이 번갈아 나오므로, 경고가 시작하는 순간이 런타임마다
/// 다르면 사용자에게는 같은 문항이 다르게 동작하는 것으로 보인다.

/// 남은 시간이 이 아래로 내려가면 경고 표시로 바꾼다. 10초 상한에서 8초부터라는 뜻이다.
///
/// 값을 상한의 비율이 아니라 **남은 시간**으로 잡았다 — 비율로 두면 상한이 짧은 문항에서
/// 경고가 시작하자마자 녹음이 끝난다. 사람이 문장을 맺는 데 필요한 시간은 상한과 무관하게
/// 2초쯤이다. 웹 `WARN_REMAINING_MS`와 같은 값이다.
public let warnRemainingMs: Int64 = 2_000

/// 상한까지 남은 시간 (ms). 상한을 넘긴 뒤에도 음수로 내려가지 않는다.
func remainingMs(elapsedMs: Int64, maxDurationMs: Int64) -> Int64 {
    max(maxDurationMs - elapsedMs, 0)
}

/// 지금이 경고 구간인가. 경계는 **닿는 순간 포함**이다 — 남은 시간이 정확히 2초인 순간
/// (10초 상한에서 경과 8000ms)부터 경고다. 미만으로 두면 청크가 8000ms에 딱 떨어질 때
/// 경고가 한 청크(32ms)만큼 늦게 뜨는데, 그 어긋남은 재현이 안 돼 버그로 보이지 않는다.
func isCountdownWarning(elapsedMs: Int64, maxDurationMs: Int64) -> Bool {
    remainingMs(elapsedMs: elapsedMs, maxDurationMs: maxDurationMs) <= warnRemainingMs
}

/// 캡슐에 적을 "N초 남음"의 N. **올림**이다 — 1.4초 남았는데 "1초 남음"이라고 적으면 사람은
/// 1초 뒤를 끝으로 잡고 문장을 맺는데 실제로는 0.4초가 더 있다. 올림 쪽이 남은 시간을 실제보다
/// 짧게 말하지 않는다. 웹 `Math.ceil(remainingMs / 1000)`과 같다.
///
/// 상한에 정확히 닿으면 0이다. 그 순간 엔진이 스스로 멈추므로(FR-RC-02) `0초 남음`이 화면에
/// 머무는 시간은 한 프레임 남짓이다.
func remainingSeconds(elapsedMs: Int64, maxDurationMs: Int64) -> Int {
    Int(ceil(Double(remainingMs(elapsedMs: elapsedMs, maxDurationMs: maxDurationMs)) / 1000.0))
}

/// 경과 시간 표기 `00:04` (시안). 초만 적던 것(`4.0초`)을 시계꼴로 바꿨다 — 옆에 붙는 상한이
/// "10초"라 같은 줄에 "초"가 두 번 나오면 어느 쪽이 지금인지 한눈에 안 갈린다.
///
/// 반올림이 아니라 버림이다. 0.9초에서 `00:01`이 뜨면 아직 1초가 안 됐는데 1초로 보이고,
/// 품질 게이트가 1초 미만을 거절하므로(FR-AD-08) 화면과 판정이 어긋난 것처럼 읽힌다.
///
/// 분 자리를 계산하는 것은 상한이 10초라 늘 `00`인데도 웹과 같은 식을 쓰기 위해서다 —
/// 상한이 60초를 넘는 문항이 생기는 날 두 런타임 중 한쪽만 고쳐지는 것을 막는다.
func formatElapsed(_ elapsedMs: Int64) -> String {
    let total = max(elapsedMs, 0) / 1000
    return String(format: "%02d:%02d", total / 60, total % 60)
}
