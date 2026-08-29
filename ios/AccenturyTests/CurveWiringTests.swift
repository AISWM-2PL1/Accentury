import AccenturyCore
import XCTest
@testable import Accentury

/// 곡선이 화면에 닿기까지의 결선 (KAN-108 §7b).
///
/// 좌표 규칙은 Core(`AccenturyCoreTests/Curve/*`)가 전부 덮고, 그리기는
/// `CurvePathBuilderTests`·`HalftoneGridTests`가 덮는다. 그 사이에 남은 것이 여기다 —
/// **어느 프레임을, 어떤 창으로 그릴지 고르는 한 자리**(``RecordingModel/curvePitchFrames``·
/// ``RecordingModel/isReviewing``). 틀려도 곡선은 그려지므로 화면만 봐서는 안 걸린다:
/// 녹음 중에 Review 규칙(구멍 메우기)이 걸리면 이미 그린 과거가 매 청크 다시 그려지고,
/// 반대면 다시 볼 수 있게 된 시점에 앞부분이 잘려 나간다.
@MainActor
final class CurveWiringTests: XCTestCase {

    /// 아직 녹음 전에는 그릴 것이 없다. 빈 레인은 정상 상태다.
    func testIdleDrawsNothing() {
        let model = RecordingModel(engine: RecordingEngine(source: SpyPcmSource()))
        XCTAssertTrue(model.curvePitchFrames.isEmpty)
        XCTAssertFalse(model.isReviewing)
    }

    /// 녹음 중에는 누적된 프레임을 **그대로** 쓴다 — 구멍을 메우면 인과성이 깨진다.
    func testRecordingUsesTheAccumulatedFramesAsIs() async {
        let model = RecordingModel(engine: RecordingEngine(source: SpyPcmSource()))
        defer { model.reset() }

        model.start()
        await RecordingModelTests.waitForAudio(model)
        await waitUntil("피치 프레임이 한 개도 안 쌓였다") {
            if case let .recording(recording) = model.uiState { return !recording.pitchFrames.isEmpty }
            return false
        }

        guard case let .recording(recording) = model.uiState else {
            return XCTFail("녹음 중이 아니다")
        }
        XCTAssertFalse(model.isReviewing, "녹음 중인데 Review 창이 걸렸다")
        XCTAssertEqual(recording.pitchFrames, model.curvePitchFrames)
    }

    /// 녹음이 끝나면 Review 규칙으로 갈아탄다. 길이·시각은 그대로고(``AccenturyCore/fillShortGaps(_:maxGapMs:)``가
    /// 같은 길이의 새 목록을 준다) 창 선택만 바뀐다.
    func testReviewSwitchesToTheFullRecordingWindow() async {
        let model = RecordingModel(engine: RecordingEngine(source: SpyPcmSource()))
        defer { model.reset() }

        model.start()
        await RecordingModelTests.waitForAudio(model)
        model.stop()
        await waitUntil("정지했는데 검토로 넘어가지 않았다") { model.isReviewing }

        guard case let .review(review) = model.uiState else {
            return XCTFail("검토 상태가 아니다")
        }
        let frames = model.curvePitchFrames
        XCTAssertEqual(review.pitchFrames.count, frames.count)
        XCTAssertEqual(review.pitchFrames.map(\.timestampMs), frames.map(\.timestampMs))

        // Review 창은 녹음 전체가 들어오도록 라이브 창보다 짧지 않다.
        let liveWindowMs = userCurveWindowMs(frameIntervalMs: 10, valueCount: 120)
        XCTAssertGreaterThanOrEqual(reviewWindowMs(frames, liveWindowMs: liveWindowMs), liveWindowMs)
    }

    /// 화면을 떠나면 곡선도 사라진다 — 남겨 두면 다음 문항의 빈 레인에 지난 곡선이 비친다.
    func testLeavingClearsTheCurve() async {
        let model = RecordingModel(engine: RecordingEngine(source: SpyPcmSource()))

        model.start()
        await RecordingModelTests.waitForAudio(model)
        model.reset()

        // 되감기는 동기지만 화면에 닿는 것은 한 hop 뒤다 — 상태 갱신이 메인 액터로 넘어온다
        // (`RecordingModel`의 `onStateChange`). 시간이 아니라 조건으로 기다린다.
        await waitUntil("떠난 뒤에도 곡선이 남아 있다") {
            model.curvePitchFrames.isEmpty && !model.isReviewing
        }
    }

    // MARK: - 가이드 좌표 메모이즈

    /// 같은 문항이면 가이드 좌표를 **한 번만** 계산한다 (Codex 지적, 안드로이드
    /// `remember(guideF0)`의 자리). 청크마다 뷰가 다시 평가되므로, 없으면 녹음 10초에
    /// 같은 계산이 300번 넘게 돈다.
    func testGuidePointsAreComputedOncePerItem() {
        let cache = GuideCurveCache()
        let guideF0 = GuideF0(unit: "semitone", frameIntervalMs: 10, values: [0, 1.5, -2, nil, 3])

        let first = cache.points(for: guideF0)
        let second = cache.points(for: guideF0)

        XCTAssertFalse(first.isEmpty)
        XCTAssertEqual(first, second)
        XCTAssertEqual(1, cache.rebuildCount)
    }

    /// 문항이 바뀌면 다시 계산한다 — payload가 곧 키다.
    func testGuidePointsRecomputeWhenTheItemChanges() {
        let cache = GuideCurveCache()
        _ = cache.points(for: GuideF0(unit: "semitone", frameIntervalMs: 10, values: [0, 1]))
        _ = cache.points(for: GuideF0(unit: "semitone", frameIntervalMs: 10, values: [0, 2]))
        XCTAssertEqual(2, cache.rebuildCount)
    }

    /// nil 가이드(구버전 웹)도 캐시된다 — 빈 결과를 되풀이해 만들지 않는다.
    func testMissingGuideIsCachedToo() {
        let cache = GuideCurveCache()
        XCTAssertTrue(cache.points(for: nil).isEmpty)
        XCTAssertTrue(cache.points(for: nil).isEmpty)
        XCTAssertEqual(1, cache.rebuildCount)
    }

    /// semitone이 아닌 단위는 그리지 않는다. "0은 무성이 아니다" 규칙이 그 단위에서만
    /// 참이라, 모르는 단위는 그럴듯하게 그려지면서 무성 판정만 조용히 틀린다.
    func testUnknownUnitDrawsNothing() {
        let cache = GuideCurveCache()
        let hz = GuideF0(unit: "hz", frameIntervalMs: 10, values: [180, 200, 220])
        XCTAssertTrue(cache.points(for: hz).isEmpty)
    }

    // MARK: - 지연 계측 (NFR-PF-02)

    /// 표시와 그리기가 짝을 이뤄야 표본이 된다. 짝이 없으면 아무것도 안 남는다.
    func testProbeRecordsNothingWithoutARender() {
        let probe = CurveLatencyProbe.shared
        probe.reset()
        probe.progressReceived(frameCount: 1, at: CFAbsoluteTimeGetCurrent())
        XCTAssertNil(probe.report())
    }

    /// 그리기가 밀려 진행이 두 번 연달아 와도 **먼저 온 표시**를 지킨다 — 나중 것으로 덮으면
    /// 밀린 만큼이 측정에서 사라져 지연이 실제보다 짧게 나온다.
    func testProbeKeepsTheOldestPendingMark() {
        let probe = CurveLatencyProbe.shared
        probe.reset()
        let old = CFAbsoluteTimeGetCurrent() - 0.2 // 200ms 전에 받은 진행
        probe.progressReceived(frameCount: 1, at: old)
        probe.progressReceived(frameCount: 2, at: CFAbsoluteTimeGetCurrent())
        probe.canvasRendered(frameCount: 2)

        let line = probe.report()
        XCTAssertNotNil(line)
        XCTAssertTrue(line?.contains("n=1") == true, line ?? "리포트가 없다")
        // 200ms 전 표시를 지켰다면 p50이 100ms를 넘는다. 덮었다면 0에 가깝다.
        XCTAssertTrue(line?.contains("p50=2") == true || line?.contains("p50=1") == true, line ?? "")
        probe.reset()
    }

    /// 아직 안 그린 프레임 번호로는 짝이 안 맞는다 — 곡선이 뒤처져 있는 동안은 표본이 안 는다.
    func testProbeIgnoresRendersBehindThePendingMark() {
        let probe = CurveLatencyProbe.shared
        probe.reset()
        probe.progressReceived(frameCount: 10, at: CFAbsoluteTimeGetCurrent())
        probe.canvasRendered(frameCount: 9)
        XCTAssertNil(probe.report())

        probe.canvasRendered(frameCount: 10)
        XCTAssertTrue(probe.report()?.contains("n=1") == true)
        probe.reset()
    }

    /// 새 녹음이 시작되면 지난 표본을 버린다 — 섞이면 분위수가 두 녹음의 혼합이 된다.
    func testProbeResetDropsSamples() {
        let probe = CurveLatencyProbe.shared
        probe.reset()
        probe.progressReceived(frameCount: 1, at: CFAbsoluteTimeGetCurrent())
        probe.canvasRendered(frameCount: 1)
        XCTAssertNotNil(probe.report())

        probe.reset()
        XCTAssertNil(probe.report())
    }
}
