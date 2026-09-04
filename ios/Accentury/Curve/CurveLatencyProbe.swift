#if DEBUG
import Foundation

/// 곡선의 **체감 지연 계측기** (NFR-PF-02: 100ms 이하). 디버그 빌드에만 있다.
///
/// 재는 구간은 파이프라인 전체가 아니라 그 **꼬리**다 — 마이크 청크가 YIN을 지나
/// `RecordingEngine`이 진행 콜백을 부른 순간부터, 그 프레임이 들어간 곡선을 캔버스가 실제로
/// 그리는 순간까지. 앞쪽 구간(창 채우기 128ms · EMA 107ms · 캡처 버퍼)은 파라미터가 정하는
/// 값이고 `docs/wiki/pitch-curve.md` §3이 이미 분해해 두었다. 여기서 재는 것은 그 표에 없는
/// 것, 곧 **이 런타임이 새로 얹는 몫**이다: 메인 액터로 넘어가는 hop, SwiftUI 무효화,
/// 좌표 재계산, `Path` 만들기.
///
/// 시각을 콜백 안에서(메인으로 넘기기 **전에**) 찍는 이유가 그것이다 — hop 자체가 재려는
/// 대상이라, 메인에 도착한 뒤에 찍으면 없애고 싶은 구간이 측정에서 빠진다.
///
/// ## 짝짓기
///
/// 표본 하나는 "N번째 프레임을 받았다"와 "N개 이상이 담긴 곡선을 그렸다"의 짝이다. 그리기가
/// 밀려 진행이 두 번 연달아 오면 **먼저 온 표시를 그대로 둔다** — 나중 것으로 덮으면 밀린
/// 만큼이 측정에서 사라져 지연이 실제보다 짧게 나온다. 그래서 이 값은 상한 쪽으로 치우친
/// 보수적인 수치이고, 표본 수(`n`)가 진행 횟수보다 적은 것도 같은 이유다.
///
/// 스레드: 표시는 캡처 쪽에서, 그리기는 메인에서 온다. SwiftUI의 렌더 클로저는 액터로
/// 격리돼 있지 않아 `@MainActor`로 잠글 수 없으므로 락 하나로 막는다.
final class CurveLatencyProbe: @unchecked Sendable {

    static let shared = CurveLatencyProbe()

    private let lock = NSLock()
    private var pendingFrameCount: Int?
    private var pendingAt: CFAbsoluteTime = 0
    private var samplesMs: [Double] = []

    /// 진행 콜백이 새 프레임을 실어 왔다. `at`은 **콜백이 불린 순간**이어야 한다.
    func progressReceived(frameCount: Int, at: CFAbsoluteTime) {
        lock.lock()
        defer { lock.unlock() }
        // 아직 안 그려진 표시가 있으면 그것을 지킨다 (위 "짝짓기" 문단).
        guard pendingFrameCount == nil else { return }
        pendingFrameCount = frameCount
        pendingAt = at
    }

    /// 캔버스가 `frameCount`개의 프레임이 담긴 곡선을 그렸다.
    func canvasRendered(frameCount: Int) {
        lock.lock()
        defer { lock.unlock() }
        guard let pending = pendingFrameCount, frameCount >= pending else { return }
        samplesMs.append((CFAbsoluteTimeGetCurrent() - pendingAt) * 1000)
        pendingFrameCount = nil
    }

    /// 새 녹음이 시작됐다. 지난 녹음의 표본이 섞이면 분위수가 두 녹음의 혼합이 된다.
    func reset() {
        lock.lock()
        defer { lock.unlock() }
        pendingFrameCount = nil
        samplesMs.removeAll()
    }

    /// `LATENCY: p50=… p95=… n=…` 한 줄. 표본이 없으면 nil이다.
    func report() -> String? {
        lock.lock()
        let samples = samplesMs.sorted()
        lock.unlock()
        guard !samples.isEmpty else { return nil }
        return String(
            format: "LATENCY: p50=%.1fms p95=%.1fms max=%.1fms n=%d",
            percentile(samples, 0.50),
            percentile(samples, 0.95),
            samples[samples.count - 1],
            samples.count
        )
    }

    /// 가장 가까운 순위(nearest-rank). 표본이 100개 안팎이라 보간할 값이 아니다.
    private func percentile(_ sorted: [Double], _ q: Double) -> Double {
        let index = Int((Double(sorted.count - 1) * q).rounded())
        return sorted[min(max(index, 0), sorted.count - 1)]
    }
}
#endif
