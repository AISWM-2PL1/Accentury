import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/audio/RecordingEngineTest.kt`의 1:1 이식본 (10개) +
/// Codex 검증에서 지적된 회귀 3건.
final class RecordingEngineTests: XCTestCase {

    /// 안드로이드 테스트의 `FakeSource(flow { ... })` 자리.
    ///
    /// 생성기를 바로 들지 않고 **만드는 함수**를 드는 이유: Kotlin `flow { }`는 콜드라
    /// 수집할 때마다 처음부터 다시 흐른다. 생성기 하나를 재사용하면 두 번째 녹음이 빈
    /// 스트림을 받아, 재녹음 동작을 검증할 수 없다.
    ///
    /// `unfolding` 생성자를 쓰는 게 또 하나의 핵심이다 — 소비자가 요구할 때만 `produce`가
    /// 불리므로 무한 소스도 엔진이 멈추는 순간 같이 멈춘다. continuation 방식으로 짜면
    /// 생산 Task가 앞서 달려 테스트가 메모리를 태우거나 영영 끝나지 않는다.
    private struct FakeSource: PcmSource {
        let makeProduce: () -> () async throws -> [Int16]?

        func recordingStream() -> AsyncThrowingStream<[Int16], Error> {
            AsyncThrowingStream(unfolding: makeProduce())
        }
    }

    /// 방출 횟수를 세는 상자. 클로저가 여러 번 불리는 사이 살아 있어야 한다.
    private final class Counter {
        private var value = 0
        func next() -> Int {
            defer { value += 1 }
            return value
        }
    }

    private func infiniteSource() -> FakeSource {
        FakeSource { { [Int16](repeating: 1000, count: chunkSize) } }
    }

    private func finiteSource(chunkCount: Int, chunkSize size: Int = chunkSize) -> FakeSource {
        FakeSource {
            let counter = Counter()
            return { counter.next() < chunkCount ? [Int16](repeating: 0, count: size) : nil }
        }
    }

    func test10초_도달_시_자동_종료되고_정확히_10초로_잘린다() async {
        let engine = RecordingEngine(source: infiniteSource())

        let outcome = await engine.record { _ in }

        guard case let .success(pcm, durationMs, autoStopped) = outcome else {
            return XCTFail("Success가 아님: \(outcome)")
        }
        XCTAssertTrue(autoStopped)
        XCTAssertEqual(10_000, durationMs)
        XCTAssertEqual(RecordingEngine.maxSamples, pcm.count)
    }

    func test수동_정지_시_그때까지_캡처된_PCM만_반환한다() async {
        let engine = RecordingEngine(source: infiniteSource())
        var chunkCount = 0

        let outcome = await engine.record { _ in
            chunkCount += 1
            if chunkCount == 5 { engine.requestStop() }
        }

        guard case let .success(pcm, durationMs, autoStopped) = outcome else {
            return XCTFail("Success가 아님: \(outcome)")
        }
        XCTAssertFalse(autoStopped)
        XCTAssertEqual(5 * chunkSize, pcm.count)
        XCTAssertEqual(Int64(5 * chunkSize) * 1000 / Int64(sampleRate), durationMs)
    }

    func test진행_리포트의_경과_시간이_샘플_수_기준으로_계산된다() async {
        let engine = RecordingEngine(source: infiniteSource())
        var elapsed: [Int64] = []

        _ = await engine.record { progress in
            elapsed.append(progress.elapsedMs)
            if elapsed.count == 3 { engine.requestStop() }
        }

        XCTAssertEqual(
            [
                Int64(chunkSize) * 1000 / Int64(sampleRate),
                Int64(2 * chunkSize) * 1000 / Int64(sampleRate),
                Int64(3 * chunkSize) * 1000 / Int64(sampleRate),
            ],
            elapsed
        )
    }

    func test녹음_시작_전의_정지_요청은_새_녹음에_영향을_주지_않는다() async {
        let engine = RecordingEngine(source: finiteSource(chunkCount: 3))
        engine.requestStop()

        let outcome = await engine.record { _ in }

        guard case let .success(pcm, _, autoStopped) = outcome else {
            return XCTFail("Success가 아님: \(outcome)")
        }
        XCTAssertFalse(autoStopped)
        XCTAssertEqual(3 * chunkSize, pcm.count)
    }

    func test진행_리포트_경과_시간이_10초를_넘지_않는다() async {
        let engine = RecordingEngine(source: infiniteSource())
        var maxElapsed: Int64 = 0

        _ = await engine.record { maxElapsed = max(maxElapsed, $0.elapsedMs) }

        XCTAssertEqual(RecordingEngine.maxDurationMs, maxElapsed)
    }

    /// 청크 경계에서도 위상이 이어지는 220Hz 사인. 창이 경계를 걸쳐도 파형이 온전하다.
    private func sine220Source(chunkSize size: Int, chunkCount: Int) -> FakeSource {
        FakeSource {
            let counter = Counter()
            return {
                let c = counter.next()
                if c >= chunkCount { return nil }
                return (0..<size).map { i in
                    let n = c * size + i
                    return Int16(truncatingIfNeeded: Int(8000 * sin(2 * Double.pi * 220.0 * Double(n) / Double(sampleRate))))
                }
            }
        }
    }

    func test진행_리포트에_겹침_프레임별_F0_추정값이_실린다() async {
        let engine = RecordingEngine(source: sine220Source(chunkSize: chunkSize, chunkCount: 3))
        var reports: [[RecordingEngine.PitchFrame]] = []

        _ = await engine.record { reports.append($0.pitchFrames) }

        XCTAssertEqual(3, reports.count)
        // 첫 청크는 창을 막 채워 1개, 이후에는 hop(512) 기준으로 청크당 4개가 나온다.
        XCTAssertEqual(1, reports[0].count)
        XCTAssertEqual(4, reports[1].count)
        for frame in reports.flatMap({ $0 }) {
            guard let f0 = frame.pitchHz else { return XCTFail("무성음 판정: \(frame)") }
            XCTAssertLessThan(abs(f0 - 220), 3)
        }
    }

    func test연속_프레임_간격이_32ms로_유지된다_NFR_PF_02() async {
        let engine = RecordingEngine(source: sine220Source(chunkSize: chunkSize, chunkCount: 3))
        var timestamps: [Int64] = []

        _ = await engine.record { progress in
            timestamps.append(contentsOf: progress.pitchFrames.map(\.timestampMs))
        }

        XCTAssertGreaterThanOrEqual(timestamps.count, 5)
        // 시각은 창 중앙이다 - 첫 창(0..2047)의 중앙은 1024샘플 = 64ms.
        XCTAssertEqual(Int64(chunkSize / 2) * 1000 / Int64(sampleRate), timestamps.first)
        XCTAssertEqual(64, timestamps.first)
        for (prev, next) in zip(timestamps, timestamps.dropFirst()) {
            XCTAssertEqual(32, next - prev)
        }
    }

    func testHop보다_짧은_청크가_이어져도_F0_프레임이_나온다() async {
        // 캡처 API가 짧게 돌려주면 청크 단위 추정은 전부 nil이 되던 케이스.
        let engine = RecordingEngine(source: sine220Source(chunkSize: 300, chunkCount: 20))
        var frames: [RecordingEngine.PitchFrame] = []

        _ = await engine.record { frames.append(contentsOf: $0.pitchFrames) }

        XCTAssertFalse(frames.isEmpty)
        for frame in frames {
            guard let f0 = frame.pitchHz else { return XCTFail("무성음 판정: \(frame)") }
            XCTAssertLessThan(abs(f0 - 220), 3)
        }
    }

    func test읽기_청크가_hop과_같으면_청크마다_프레임이_정확히_1개씩_나온다_KAN_105() async {
        // 실제 마이크의 방출 단위(readChunkSize = 512). 곡선이 4점씩 계단으로 자라지 않고
        // 청크마다 1점씩 이어져야 32ms 주기로 갱신된다.
        let chunkCount = 10
        let engine = RecordingEngine(source: sine220Source(chunkSize: readChunkSize, chunkCount: chunkCount))
        var reports: [[RecordingEngine.PitchFrame]] = []

        _ = await engine.record { reports.append($0.pitchFrames) }

        XCTAssertEqual(chunkCount, reports.count)
        // 창(2048)을 채우는 동안은 빈 리포트고, 채운 뒤로는 hop이 곧 청크라 매번 1개다.
        let warmupChunks = chunkSize / readChunkSize - 1
        for report in reports.prefix(warmupChunks) { XCTAssertTrue(report.isEmpty) }
        for report in reports.dropFirst(warmupChunks) { XCTAssertEqual(1, report.count) }
    }

    func test캡처_예외는_Failure로_변환된다() async {
        let failing = FakeSource { { throw CaptureError("녹음 중 권한 회수") } }
        let engine = RecordingEngine(source: failing)

        let outcome = await engine.record { _ in }

        guard case let .failure(reason) = outcome else {
            return XCTFail("Failure가 아님: \(outcome)")
        }
        XCTAssertTrue(reason.contains("권한"))
    }

    // MARK: - Codex 검증 회귀 (안드로이드 원본에 없는 추가분)

    /// 소스가 곧바로 끝나면 Success(빈 PCM)가 아니라 Failure다. 빈 WAV를 서버로 올리는 경로를 막는다.
    func test빈_소스는_캡처된_오디오가_없음_실패다() async {
        let engine = RecordingEngine(source: FakeSource { { nil } })

        let outcome = await engine.record { _ in }

        guard case let .failure(reason) = outcome else {
            return XCTFail("Failure가 아님: \(outcome)")
        }
        XCTAssertEqual("캡처된 오디오가 없음", reason)
    }

    /// 진행 리포트의 rms는 화면 볼륨 막대의 입력이라 값이 틀리면 눈에 띄지 않게 어긋난다.
    /// 전부 1000인 청크의 RMS는 정의상 정확히 1000.0이다.
    func test진행_리포트의_rms가_청크의_실제_RMS다() async {
        let engine = RecordingEngine(source: infiniteSource())
        var reported: [Double] = []

        _ = await engine.record { progress in
            reported.append(progress.rms)
            if reported.count == 3 { engine.requestStop() }
        }

        XCTAssertEqual(3, reported.count)
        for rms in reported { XCTAssertEqual(1000.0, rms, accuracy: 1e-9) }
    }

    /// 같은 엔진으로 다시 녹음하면 프레이머가 새로 만들어져야 한다. 재사용하면 지난 녹음의
    /// 잔여 샘플이 남아 두 번째 녹음의 첫 창이 64ms보다 이른 시각으로 찍힌다(재녹음·재응시 경로).
    func test재녹음하면_프레이머가_리셋된다() async {
        let engine = RecordingEngine(source: sine220Source(chunkSize: readChunkSize, chunkCount: 10))

        var firstRun: [Int64] = []
        _ = await engine.record { firstRun.append(contentsOf: $0.pitchFrames.map(\.timestampMs)) }
        var secondRun: [Int64] = []
        _ = await engine.record { secondRun.append(contentsOf: $0.pitchFrames.map(\.timestampMs)) }

        XCTAssertEqual(64, firstRun.first)
        XCTAssertEqual(64, secondRun.first, "프레이머가 리셋되지 않아 두 번째 녹음의 시간축이 앞당겨졌다")
        XCTAssertEqual(firstRun, secondRun)
    }
}
