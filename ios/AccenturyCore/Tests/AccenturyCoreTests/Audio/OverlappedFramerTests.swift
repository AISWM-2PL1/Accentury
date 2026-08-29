import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/audio/OverlappedFramerTest.kt`의 1:1 이식본.
final class OverlappedFramerTests: XCTestCase {

    /// 값이 곧 전역 샘플 인덱스인 램프 신호. 창 내용이 어디서 잘려 나왔는지 값으로 검증할 수 있다.
    /// 안드로이드 `(from + it).toShort()`가 Short 범위를 넘으면 하위 16비트만 남기므로
    /// Swift도 `truncatingIfNeeded`로 같은 값을 만든다 (2048샘플 램프는 32767을 넘는다).
    private func ramp(from: Int, size: Int) -> [Int16] {
        (0..<size).map { Int16(truncatingIfNeeded: from + $0) }
    }

    private func assertRampFrame(
        _ frame: AnalysisFrame,
        expectedStart: Int64,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(expectedStart, frame.startSampleIndex, file: file, line: line)
        XCTAssertEqual(chunkSize, frame.samples.count, file: file, line: line)
        for (i, sample) in frame.samples.enumerated() {
            XCTAssertEqual(
                Int16(truncatingIfNeeded: expectedStart + Int64(i)), sample,
                file: file, line: line
            )
        }
    }

    /// 창 길이와 같은 청크는 프레임 1개를 만든다
    func testChunkEqualToWindowSizeProducesOneFrame() {
        let framer = OverlappedFramer()

        let frames = framer.push(ramp(from: 0, size: chunkSize))

        XCTAssertEqual(1, frames.count)
        assertRampFrame(frames[0], expectedStart: 0)
    }

    /// 창 하나 뒤에 hop만큼 더 들어오면 프레임이 하나 더 나온다
    func testOneMoreHopAfterAWindowProducesOneMoreFrame() {
        let framer = OverlappedFramer()
        _ = framer.push(ramp(from: 0, size: chunkSize))

        let frames = framer.push(ramp(from: chunkSize, size: 512))

        XCTAssertEqual(1, frames.count)
        assertRampFrame(frames[0], expectedStart: 512)
    }

    /// hop보다 짧은 청크만으로는 프레임이 나오지 않는다
    func testChunkShorterThanHopProducesNoFrame() {
        let framer = OverlappedFramer()
        _ = framer.push(ramp(from: 0, size: chunkSize))

        XCTAssertTrue(framer.push(ramp(from: chunkSize, size: 300)).isEmpty)
    }

    /// 한 번의 큰 청크가 여러 프레임을 만든다
    func testOneLargeChunkProducesSeveralFrames() {
        let framer = OverlappedFramer()

        let frames = framer.push(ramp(from: 0, size: chunkSize + 512 * 3))

        XCTAssertEqual(4, frames.count)
        for (i, start) in [Int64(0), 512, 1024, 1536].enumerated() {
            assertRampFrame(frames[i], expectedStart: start)
        }
    }

    /// 불규칙한 작은 청크들도 이어 붙여 올바른 프레임을 만든다
    func testIrregularSmallChunksAreStitchedIntoCorrectFrames() {
        // 캡처 API가 요청보다 짧게 돌려주는 경우. 청크 경계와 창 경계가 어긋난다.
        let framer = OverlappedFramer()
        var collected: [AnalysisFrame] = []
        var pushed = 0
        while pushed < chunkSize + 512 * 2 {
            collected += framer.push(ramp(from: pushed, size: 300))
            pushed += 300
        }

        XCTAssertEqual(3, collected.count)
        for (i, start) in [Int64(0), 512, 1024].enumerated() {
            assertRampFrame(collected[i], expectedStart: start)
        }
    }

    /// 창을 채우지 못한 꼬리는 프레임으로 나오지 않는다
    func testTailThatDoesNotFillAWindowProducesNoFrame() {
        let framer = OverlappedFramer()

        XCTAssertTrue(framer.push(ramp(from: 0, size: chunkSize - 1)).isEmpty)
        // 남은 꼬리(2047샘플)는 다음 입력이 올 때까지 그대로 대기한다.
        XCTAssertEqual(1, framer.push(ramp(from: chunkSize - 1, size: 1)).count)
    }

    /// 빈 청크는 프레임을 만들지 않는다
    func testEmptyChunkProducesNoFrame() {
        let framer = OverlappedFramer()

        XCTAssertTrue(framer.push([]).isEmpty)
        XCTAssertEqual(1, framer.push(ramp(from: 0, size: chunkSize)).count)
    }
}
