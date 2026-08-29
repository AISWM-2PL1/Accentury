import XCTest
@testable import AccenturyCore

/// 안드로이드 `recording/UserCurveTest.kt`의 1:1 이식본 (37개).
final class UserCurveTests: XCTestCase {

    private static let frameMs: Int64 = 32
    private static let centerHz: Float = 200
    private static let windowMs: Int64 = 2000
    private static let longGapMs: Int64 = 500

    /// 앞뒤 가장자리가 무성이고, 32ms(200Hz)와 224ms(800Hz) 사이에 192ms짜리 구멍이 있다.
    /// 두 옥타브 차이라 한가운데의 기하평균이 400Hz로 딱 떨어진다.
    private static let hole192Ms: [RecordingEngine.PitchFrame] = [
        RecordingEngine.PitchFrame(timestampMs: 0, pitchHz: nil),
        RecordingEngine.PitchFrame(timestampMs: 32, pitchHz: 200),
        RecordingEngine.PitchFrame(timestampMs: 64, pitchHz: nil),
        RecordingEngine.PitchFrame(timestampMs: 96, pitchHz: nil),
        RecordingEngine.PitchFrame(timestampMs: 128, pitchHz: nil),
        RecordingEngine.PitchFrame(timestampMs: 160, pitchHz: nil),
        RecordingEngine.PitchFrame(timestampMs: 192, pitchHz: nil),
        RecordingEngine.PitchFrame(timestampMs: 224, pitchHz: 800),
        RecordingEngine.PitchFrame(timestampMs: 256, pitchHz: nil),
    ]

    private var frameMs: Int64 { Self.frameMs }
    private var centerHz: Float { Self.centerHz }
    private var windowMs: Int64 { Self.windowMs }
    private var longGapMs: Int64 { Self.longGapMs }

    private func frame(_ timestampMs: Int64, _ hz: Float?) -> RecordingEngine.PitchFrame {
        RecordingEngine.PitchFrame(timestampMs: timestampMs, pitchHz: hz)
    }

    /// 실제 엔진과 같은 32ms 간격으로 프레임을 만든다. nil은 무성 프레임이다.
    private func frames(_ hz: [Float?], startMs: Int64 = 0) -> [RecordingEngine.PitchFrame] {
        hz.enumerated().map { i, v in frame(startMs + Int64(i) * frameMs, v) }
    }

    /// 중심 잠금에 필요한 최소 유성 프레임. 전부 같은 값이라 중심이 곧 `hz`다.
    private func centerFrames(_ hz: Float? = nil) -> [RecordingEngine.PitchFrame] {
        (0..<centerMinVoicedFrames).map { frame(Int64($0) * frameMs, hz ?? centerHz) }
    }

    /// 중심에서 `st` semitone 떨어진 Hz
    private func semitone(_ st: Double, center: Float? = nil) -> Float {
        Float(Double(center ?? centerHz) * pow(2.0, st / 12.0))
    }

    /// 중심 프레임 다음에 오는 프레임의 시각
    private func after(_ gapMs: Int64) -> Int64 {
        Int64(centerMinVoicedFrames - 1) * frameMs + gapMs
    }

    // MARK: - 창 길이

    /// `가이드 길이는 간격 곱하기 구간 수고 알 수 없으면 0이다`
    func testGuideDurationIsIntervalTimesSpanCount() {
        XCTAssertEqual(1000, guideDurationMs(frameIntervalMs: 10, valueCount: 101))
        XCTAssertEqual(320, guideDurationMs(frameIntervalMs: 32, valueCount: 11))
        XCTAssertEqual(0, guideDurationMs(frameIntervalMs: nil, valueCount: nil))
        XCTAssertEqual(0, guideDurationMs(frameIntervalMs: 10, valueCount: 1))
        XCTAssertEqual(0, guideDurationMs(frameIntervalMs: 0, valueCount: 101))
    }

    /// `창 길이는 가이드 길이의 두 배다`
    func testWindowIsTwiceTheGuideLength() {
        XCTAssertEqual(2000, userCurveWindowMs(frameIntervalMs: 10, valueCount: 101))
        XCTAssertEqual(640, userCurveWindowMs(frameIntervalMs: 32, valueCount: 11))
    }

    /// `가이드를 쓸 수 없으면 창 길이는 폴백 1초의 두 배다`
    func testUnusableGuideFallsBackToTwoSeconds() {
        XCTAssertEqual(2000, userCurveWindowMs(frameIntervalMs: nil, valueCount: nil))
        XCTAssertEqual(2000, userCurveWindowMs(frameIntervalMs: 10, valueCount: 1))
        XCTAssertEqual(2000, userCurveWindowMs(frameIntervalMs: 10, valueCount: 0))
        XCTAssertEqual(2000, userCurveWindowMs(frameIntervalMs: 0, valueCount: 101))
        XCTAssertEqual(2000, userCurveWindowMs(frameIntervalMs: -5, valueCount: 101))
    }

    /// `Review 창은 라이브 창보다 긴 녹음을 통째로 담는다`
    func testReviewWindowHoldsARecordingLongerThanTheLiveWindow() {
        // 3.168초짜리 녹음이면 2초 라이브 창으로는 앞부분이 잘린다
        let long = (0..<100).map { frame(Int64($0) * frameMs, centerHz) }
        let lastMs = 99 * frameMs
        XCTAssertGreaterThan(lastMs, windowMs, "전제: 녹음이 라이브 창보다 길다")
        XCTAssertEqual(lastMs + frameMs, reviewWindowMs(long, liveWindowMs: windowMs))
    }

    /// `라이브 창 안에 들어오는 녹음이면 Review도 라이브 창을 쓴다`
    func testShortRecordingKeepsTheLiveWindowInReview() {
        // 창을 녹음 길이에 맞춰 줄이면 짧은 발화가 레인 폭을 억지로 채워 늘어져 보인다
        XCTAssertEqual(windowMs, reviewWindowMs(centerFrames(), liveWindowMs: windowMs))
    }

    /// `프레임이 없으면 Review 창은 라이브 창 그대로다`
    func testNoFramesKeepsTheLiveWindow() {
        XCTAssertEqual(windowMs, reviewWindowMs([], liveWindowMs: windowMs))
    }

    /// `Review 창으로 그리면 첫 프레임부터 마지막 프레임까지 다 들어온다`
    func testReviewWindowDrawsEveryFrame() throws {
        let total = 100
        let long = (0..<total).map { frame(Int64($0) * frameMs, centerHz) }
        let window = reviewWindowMs(long, liveWindowMs: windowMs)
        let points = try single(userCurveDisplayPoints(long, windowMs: window))

        XCTAssertEqual(total, points.count)
        XCTAssertEqual(
            Float(long.first!.timestampMs) / Float(window),
            points.first!.x,
            accuracy: 1e-5,
            "첫 점은 첫 프레임 시각 자리다"
        )
        XCTAssertLessThan(points.last!.x, 1, "마지막 점은 오른쪽 모서리에 붙지 않는다: \(points.last!.x)")
    }

    // MARK: - 그릴 게 없는 경우

    /// `그릴 프레임이 없으면 빈 목록이다`
    func testNoFramesDrawNothing() {
        XCTAssertEqual([], userCurveDisplayPoints([], windowMs: windowMs))
    }

    /// `전부 무성이면 그릴 점이 없다`
    func testAllUnvoicedDrawsNothing() {
        XCTAssertEqual([], userCurveDisplayPoints(frames([nil, nil, nil]), windowMs: windowMs))
    }

    /// `창 길이가 0 이하면 그리지 않는다`
    func testNonPositiveWindowDrawsNothing() {
        XCTAssertEqual([], userCurveDisplayPoints(centerFrames(), windowMs: 0))
    }

    // MARK: - 중심 잠금

    /// `유성 프레임이 모자라면 축이 없어 그리지 않는다`
    func testTooFewVoicedFramesMeanNoAxis() {
        let notEnough = (0..<(centerMinVoicedFrames - 1)).map { frame(Int64($0) * frameMs, centerHz) }
        XCTAssertNil(userCurveCenterHz(notEnough))
        XCTAssertEqual([], userCurveDisplayPoints(notEnough, windowMs: windowMs))
    }

    /// `유성 프레임이 채워지는 순간부터 그려진다`
    func testDrawingStartsWhenVoicedFramesAreEnough() throws {
        let enough = centerFrames()
        XCTAssertEqual(centerHz, try XCTUnwrap(userCurveCenterHz(enough)), accuracy: 1e-3)
        let segments = userCurveDisplayPoints(enough, windowMs: windowMs)
        XCTAssertEqual(1, segments.count)
        XCTAssertEqual(centerMinVoicedFrames, segments[0].count)
    }

    /// `중심은 처음 여덟 프레임으로 잠긴다 - 뒤에 뭐가 와도 안 변한다`
    func testCenterLocksOnTheFirstEightFrames() throws {
        let locked = try XCTUnwrap(userCurveCenterHz(centerFrames()))
        let more = centerFrames() + frames([400, 400, 400, 400], startMs: 8 * frameMs)
        XCTAssertEqual(locked, try XCTUnwrap(userCurveCenterHz(more)), accuracy: 1e-3)
    }

    /// `중앙값이라 옥타브 오류 한 프레임에 중심이 안 밀린다`
    func testMedianResistsOneOctaveError() throws {
        // 여덟 중 하나가 두 배로 튄 경우 - 평균이면 12퍼센트 넘게 밀리지만 중앙값은 그대로다
        let withOctaveError = frames([
            centerHz, centerHz, centerHz, centerHz * 2,
            centerHz, centerHz, centerHz, centerHz,
        ])
        XCTAssertEqual(centerHz, try XCTUnwrap(userCurveCenterHz(withOctaveError)), accuracy: 1e-3)
    }

    /// `무성 프레임은 중심 계산에서 세지 않는다`
    func testUnvoicedFramesDoNotCountTowardTheCenter() throws {
        let sparse = frames([
            centerHz, nil, centerHz, nil, centerHz, nil, centerHz, nil,
            centerHz, nil, centerHz, nil, centerHz, nil, centerHz,
        ])
        XCTAssertEqual(centerHz, try XCTUnwrap(userCurveCenterHz(sparse)), accuracy: 1e-3)
    }

    // MARK: - y축 스케일

    /// `중심 음높이는 레인 한가운데다`
    func testCenterPitchSitsInTheMiddleOfTheLane() throws {
        let points = try single(userCurveDisplayPoints(centerFrames(), windowMs: windowMs))
        for point in points {
            XCTAssertEqual(0.5, point.y, accuracy: 1e-4)
        }
    }

    /// `중심에서 위아래 7 semitone이 레인 끝이다`
    func testSevenSemitonesEitherWayReachTheLaneEdge() throws {
        // 긴 구멍 뒤에 두어 EMA가 초기화되게 한다 - 스무딩이 섞이지 않은 순수 좌표를 본다
        let up = centerFrames() + [frame(after(longGapMs), semitone(7.0))]
        XCTAssertEqual(0, try lastPoint(up).y, accuracy: 1e-4)

        let down = centerFrames() + [frame(after(longGapMs), semitone(-7.0))]
        XCTAssertEqual(1, try lastPoint(down).y, accuracy: 1e-4)
    }

    /// `창을 벗어난 값은 레인 안으로 눌러 담는다`
    func testValuesOutsideTheWindowAreClamped() throws {
        let up = centerFrames() + [frame(after(longGapMs), semitone(20.0))]
        XCTAssertEqual(0, try lastPoint(up).y, accuracy: 1e-4)

        let down = centerFrames() + [frame(after(longGapMs), semitone(-20.0))]
        XCTAssertEqual(1, try lastPoint(down).y, accuracy: 1e-4)
    }

    /// `높은 음이 위로 간다 - Hz가 클수록 y가 작다`
    func testHigherPitchGoesUp() throws {
        let rising = centerFrames() + frames(
            [semitone(1.0), semitone(3.0), semitone(6.0)],
            startMs: Int64(centerMinVoicedFrames) * frameMs
        )
        let points = try single(userCurveDisplayPoints(rising, windowMs: windowMs))
        let tail = Array(points.suffix(3))
        for k in 0..<(tail.count - 1) {
            XCTAssertGreaterThan(tail[k].y, tail[k + 1].y, "y는 단조 감소해야 한다: \(tail)")
        }
    }

    /// `centerHz를 주면 자동 계산을 쓰지 않는다`
    func testExplicitCenterOverridesTheAutomaticOne() throws {
        // 유성 프레임이 셋뿐이라 자동 계산은 nil인데, 중심을 받았으니 그려진다
        let short = frames([centerHz, centerHz, centerHz])
        XCTAssertNil(userCurveCenterHz(short))
        let points = try single(userCurveDisplayPoints(short, windowMs: windowMs, centerHz: centerHz))
        XCTAssertEqual(3, points.count)
        for point in points {
            XCTAssertEqual(0.5, point.y, accuracy: 1e-4)
        }

        // 중심을 위로 올려 주면 같은 프레임이 레인 아래쪽에 놓인다
        let higherCenter = userCurveDisplayPoints(short, windowMs: windowMs, centerHz: semitone(7.0))
        XCTAssertEqual(1, try single(higherCenter).first!.y, accuracy: 1e-4)
    }

    // MARK: - EMA 스무딩

    /// `EMA는 튀는 한 프레임을 알파배로 눌러 준다`
    func testEmaDampsASingleSpikeByAlpha() throws {
        let spike = centerFrames() + [frame(after(frameMs), semitone(7.0))]
        let points = try single(userCurveDisplayPoints(spike, windowMs: windowMs))
        // 스무딩이 없었다면 y=0(레인 끝)이라 중앙에서 0.5만큼 움직였을 값이다
        let displacement = 0.5 - points.last!.y
        XCTAssertEqual(0.5 * userCurveEmaAlpha, displacement, accuracy: 1e-3)
    }

    /// `선분 첫 프레임은 지연 없이 제 값 그대로다`
    func testTheFirstFrameOfASegmentIsNotLagged() throws {
        // 첫 프레임부터 중심에서 떨어져 있어도 0에서 끌려오지 않는다
        let offset = (0..<centerMinVoicedFrames).map { frame(Int64($0) * frameMs, semitone(3.5)) }
        // 중심이 곧 이 값이므로 자동 계산에서는 항상 0.5다 - 중심을 명시해 상대 위치를 본다
        let points = try single(userCurveDisplayPoints(offset, windowMs: windowMs, centerHz: centerHz))
        XCTAssertEqual(0.5 - 3.5 / Float(userCurveSpanSemitone), points.first!.y, accuracy: 1e-3)
    }

    // MARK: - 무성 구간

    /// `짧은 구멍은 직전 값을 유지해 선이 이어진다`
    func testShortHolesHoldThePreviousValue() throws {
        let gapMs = holdMaxGapMs // 경계값 포함
        let withHole = centerFrames() + [frame(after(frameMs), nil), frame(after(gapMs), semitone(2.0))]
        let segments = userCurveDisplayPoints(withHole, windowMs: windowMs)
        XCTAssertEqual(1, segments.count, "구멍이 짧으면 선분이 갈라지지 않는다")

        let points = try single(segments)
        // 구멍 자리에도 점이 있다 - 프레임 수만큼 점이 나온다
        XCTAssertEqual(centerMinVoicedFrames + 2, points.count)
        let held = points[centerMinVoicedFrames]
        XCTAssertEqual(points[centerMinVoicedFrames - 1].y, held.y, accuracy: 1e-6, "유지 값은 직전 점과 같다")
    }

    /// `긴 구멍은 선을 끊고 EMA를 초기화한다`
    func testLongHolesBreakTheLineAndResetTheEma() {
        let withPause = centerFrames() + [frame(after(longGapMs), semitone(7.0))]
        let segments = userCurveDisplayPoints(withPause, windowMs: windowMs)
        XCTAssertEqual(2, segments.count)
        // 새 선분 첫 점은 직전 선분의 값(중앙 0.5)에 끌리지 않고 제 값(레인 끝)에서 시작한다
        XCTAssertEqual(0, segments[1][0].y, accuracy: 1e-4)
    }

    /// `유지는 직전 유성 프레임 기준이라 구멍이 길어지면 멈춘다`
    func testHoldingStopsOnceTheHoleGrowsPastTheLimit() throws {
        // 무성이 계속되면 holdMaxGapMs를 넘는 순간부터는 점을 두지 않는다
        let longHole = centerFrames() + frames(
            [nil, nil, nil, nil, nil, nil, nil, nil, nil, nil],
            startMs: Int64(centerMinVoicedFrames) * frameMs
        )
        let points = try single(userCurveDisplayPoints(longHole, windowMs: windowMs))
        let heldCount = points.count - centerMinVoicedFrames
        // 마지막 유성 시각에서 32ms씩 떨어진 프레임 중 250ms 이하인 일곱(32~224ms)만 유지되고,
        // 256ms부터는 끊긴다
        XCTAssertEqual(Int(holdMaxGapMs / frameMs), heldCount)
        XCTAssertEqual(7, heldCount)
    }

    // MARK: - 시간 가중 EMA

    /// `구멍이 길수록 옛 값의 몫이 줄어든다`
    func testLongerHolesLeaveLessOfTheOldValue() throws {
        // 100ms 구멍은 프레임 3개어치라 0.7^3 = 34%가 남는다
        XCTAssertEqual(0.343, try residualAfterGap(100), accuracy: 0.005)
        // 250ms(유지 한계)는 프레임 8개어치라 0.7^8 = 6%다 - 옛 값에 끌려가지 않는다
        XCTAssertEqual(0.058, try residualAfterGap(250), accuracy: 0.005)
    }

    /// `유지 한계 안의 구멍은 선분을 가르지 않는다`
    func testHolesWithinTheLimitDoNotSplitTheSegment() {
        let segments = userCurveDisplayPoints(gapThenJump(250), windowMs: windowMs)
        XCTAssertEqual(1, segments.count, "250ms는 holdMaxGapMs 이하라 한 선분이다")
    }

    /// `연속 프레임의 EMA는 시간 가중 전후가 같다`
    func testConsecutiveFramesMatchThePlainEma() throws {
        // gapFrames=1이면 retain=0.7이라 `직전*0.7 + 현재*0.3`과 정확히 같다
        XCTAssertEqual(1.0 - Double(userCurveEmaAlpha), try residualAfterGap(frameMs), accuracy: 1e-3)
    }

    /// `유지 한계를 넘는 구멍은 선을 끊고 새 값 그대로 시작한다`
    func testHolesPastTheLimitStartFromTheNewValue() {
        let jumpSt = 3.5
        let over = centerFrames() + [frame(after(300), semitone(jumpSt))]
        let segments = userCurveDisplayPoints(over, windowMs: windowMs)
        XCTAssertEqual(2, segments.count, "300ms는 holdMaxGapMs를 넘어 선분이 갈린다")
        let expectedY = 0.5 - Float(jumpSt / userCurveSpanSemitone)
        XCTAssertEqual(expectedY, segments[1][0].y, accuracy: 1e-4)
    }

    // MARK: - 실시간성

    /// `프레임이 더 쌓여도 이미 그린 점은 그대로다`
    func testAlreadyDrawnPointsNeverMove() throws {
        let all = centerFrames() + frames(
            [
                semitone(1.0), semitone(4.0), semitone(-2.0), semitone(5.0),
                semitone(2.0), semitone(-3.0), semitone(6.0), semitone(0.0),
            ],
            startMs: Int64(centerMinVoicedFrames) * frameMs
        )
        let earlier = try single(userCurveDisplayPoints(Array(all.prefix(12)), windowMs: windowMs))
        let later = try single(userCurveDisplayPoints(all, windowMs: windowMs))
        XCTAssertGreaterThan(later.count, earlier.count)
        for (i, p) in earlier.enumerated() {
            XCTAssertEqual(p.x, later[i].x, accuracy: 1e-6, "점 \(i) 의 x가 변했다")
            XCTAssertEqual(p.y, later[i].y, accuracy: 1e-6, "점 \(i) 의 y가 변했다")
        }
    }

    /// `창이 차기 전에는 왼쪽부터 자란다`
    func testTheCurveGrowsFromTheLeftUntilTheWindowFills() throws {
        // 최신이 창의 절반쯤이면 곡선도 절반까지만 그려진다
        let points = try single(userCurveDisplayPoints(centerFrames(), windowMs: windowMs))
        XCTAssertEqual(0, points.first!.x, accuracy: 1e-5)
        let lastMs = Int64(centerMinVoicedFrames - 1) * frameMs
        XCTAssertEqual(Float(lastMs) / Float(windowMs), points.last!.x, accuracy: 1e-5)
    }

    /// `창 길이를 넘기면 창이 미끄러지고 밀려난 프레임은 버린다`
    func testTheWindowSlidesAndDropsPushedOutFrames() throws {
        let total = 40
        let long = (0..<total).map { frame(Int64($0) * frameMs, centerHz) }
        let window: Int64 = 1000
        let points = try single(userCurveDisplayPoints(long, windowMs: window))

        let newestMs = Int64(total - 1) * frameMs
        let windowStartMs = newestMs - window
        let expected = long.filter { $0.timestampMs >= windowStartMs }.count
        XCTAssertEqual(expected, points.count)
        XCTAssertLessThan(points.first!.x, 0.05, "가장 오래된 점은 창 왼쪽에 붙는다: \(points.first!)")
        XCTAssertEqual(1, points.last!.x, accuracy: 1e-5, "최신 점은 오른쪽 끝이다")
    }

    // MARK: - Review 구멍 보간

    /// `짧은 구멍은 semitone 선형으로 메워진다`
    func testShortHolesAreFilledLinearlyInSemitones() throws {
        let filled = fillShortGaps(Self.hole192Ms)

        XCTAssertEqual(Self.hole192Ms.count, filled.count, "개수가 보존된다")
        XCTAssertEqual(
            Self.hole192Ms.map { $0.timestampMs },
            filled.map { $0.timestampMs },
            "시각과 순서가 보존된다"
        )
        // 양 끝 유성 값은 그대로다
        XCTAssertEqual(200, try XCTUnwrap(filled[1].pitchHz), accuracy: 1e-3)
        XCTAssertEqual(800, try XCTUnwrap(filled[7].pitchHz), accuracy: 1e-3)
        // 구멍 한가운데(t=128, 비율 0.5)는 산술평균 500이 아니라 기하평균 400이다
        XCTAssertEqual(400, try XCTUnwrap(filled[4].pitchHz), accuracy: 1e-2)
        // 나머지 구멍 자리도 전부 채워졌고, 단조 증가한다
        let inside = try (2...6).map { try XCTUnwrap(filled[$0].pitchHz) }
        XCTAssertTrue(inside.allSatisfy { $0 > 0 }, "구멍이 다 채워져야 한다: \(inside)")
        for k in 0..<(inside.count - 1) {
            XCTAssertLessThan(inside[k], inside[k + 1], "보간값은 단조 증가한다: \(inside)")
        }
    }

    /// `앞뒤 가장자리 구멍은 그대로 둔다`
    func testEdgeHolesAreLeftAlone() {
        let filled = fillShortGaps(Self.hole192Ms)
        XCTAssertNil(filled.first!.pitchHz, "녹음 시작 전 무성")
        XCTAssertNil(filled.last!.pitchHz, "녹음이 끝난 뒤 무성")
    }

    /// `긴 구멍은 진짜 쉼이라 메우지 않는다`
    func testLongHolesAreRealPausesAndStayEmpty() {
        // 유성 두 개 사이가 608ms라 reviewFillMaxGapMs(500)를 넘는다
        let hole = [frame(0, 200)]
            + (0..<18).map { frame(Int64($0 + 1) * frameMs, nil) }
            + [frame(19 * frameMs, 800)]
        let filled = fillShortGaps(hole)

        XCTAssertEqual(hole.count, filled.count)
        XCTAssertEqual(hole, filled)
        XCTAssertTrue(filled[1..<19].allSatisfy { $0.pitchHz == nil }, "구멍이 그대로 null이다")
    }

    /// `메울 짝이 없으면 원본 그대로다`
    func testNothingToPairWithLeavesTheInputUntouched() {
        XCTAssertEqual([], fillShortGaps([]))
        let allUnvoiced = frames([nil, nil, nil])
        XCTAssertEqual(allUnvoiced, fillShortGaps(allUnvoiced))
        let onlyOneVoiced = frames([nil, 200, nil])
        XCTAssertEqual(onlyOneVoiced, fillShortGaps(onlyOneVoiced))
    }

    /// `메운 프레임은 곡선을 한 선분으로 잇는다`
    func testFilledFramesJoinTheCurveIntoOneSegment() {
        // 608ms 구멍은 실시간 곡선이라면 선분을 가르지만, 한계를 늘려 메우면 한 선분이 된다
        let hole = centerFrames()
            + (0..<18).map { frame(after(Int64($0 + 1) * frameMs), nil) }
            + [frame(after(19 * frameMs), semitone(3.0))]
        XCTAssertEqual(2, userCurveDisplayPoints(hole, windowMs: windowMs).count)
        let filled = fillShortGaps(hole, maxGapMs: 1000)
        XCTAssertEqual(1, userCurveDisplayPoints(filled, windowMs: windowMs).count)
    }

    // MARK: - 보조

    /// 코틀린 `single()` 자리. 선분이 하나가 아니면 **실패**다 - 강제 언래핑이 내는 크래시
    /// 대신 어느 테스트가 몇 개를 봤는지 남긴다.
    private struct NotSingle: Error { let count: Int }

    private func single(_ segments: [[CurvePoint]]) throws -> [CurvePoint] {
        guard segments.count == 1 else { throw NotSingle(count: segments.count) }
        return segments[0]
    }

    /// 마지막 선분의 유일한 점 (코틀린 `.last().single()`)
    private func lastPoint(_ frames: [RecordingEngine.PitchFrame]) throws -> CurvePoint {
        let segments = userCurveDisplayPoints(frames, windowMs: windowMs)
        guard let last = segments.last, last.count == 1 else {
            throw NotSingle(count: segments.last?.count ?? 0)
        }
        return last[0]
    }

    /// 중심 프레임 뒤 `gapMs` 만큼 떨어진 곳에 7 semitone 점프를 두었을 때, 남은 옛 값의 비율
    private func residualAfterGap(_ gapMs: Int64) throws -> Double {
        let jumpSt = 7.0
        let segments = userCurveDisplayPoints(gapThenJump(gapMs), windowMs: windowMs)
        guard let points = segments.last, let lastPoint = points.last else {
            throw NotSingle(count: segments.count)
        }
        let onScreenSt = (0.5 - Double(lastPoint.y)) * userCurveSpanSemitone
        // 옛 값이 0 semitone(중심)이었으므로, 목표에 못 미친 몫이 곧 옛 값의 잔존 비율이다
        return (jumpSt - onScreenSt) / jumpSt
    }

    private func gapThenJump(_ gapMs: Int64) -> [RecordingEngine.PitchFrame] {
        centerFrames() + [frame(after(gapMs), semitone(7.0))]
    }
}
