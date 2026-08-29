import XCTest
@testable import AccenturyCore

/// 안드로이드에는 대응물이 없는 iOS 전용 계층이라 이식 테스트가 아니라 새로 쓴 것이다.
/// `AVAudioConverter`가 탭마다 다른 길이를 뱉는 상황에서 샘플이 새거나 겹치지 않는지를 본다 —
/// 실제로 틀리기 쉬운 곳이 전부 여기라, 컨버터 자체는 테스트하지 않는다.
final class Int16RechunkerTests: XCTestCase {

    /// 값이 곧 전역 샘플 인덱스인 램프 신호. 순서가 어긋나거나 값이 빠지면 값으로 잡힌다.
    private func ramp(from: Int, count: Int) -> [Int16] {
        (0..<count).map { Int16(truncatingIfNeeded: from + $0) }
    }

    /// 48kHz 1024프레임을 16kHz로 내리면 341과 342가 번갈아 나온다. 실제 캡처가 주는 모양이다.
    func test임의_길이_입력이_정확히_512씩_나온다() {
        let rechunker = Int16Rechunker()
        let lengths = [341, 342, 341, 342, 341, 300, 700, 1, 1023]

        var produced: [[Int16]] = []
        var cursor = 0
        for length in lengths {
            produced += rechunker.push(ramp(from: cursor, count: length))
            cursor += length
        }

        XCTAssertFalse(produced.isEmpty)
        for chunk in produced { XCTAssertEqual(readChunkSize, chunk.count) }
        // 총 3731샘플 → 512짜리 7개(3584) + 잔여 147.
        XCTAssertEqual(cursor / readChunkSize, produced.count)
        XCTAssertEqual(cursor % readChunkSize, rechunker.pendingCount)
    }

    func test경계에서_샘플_유실도_중복도_없다() {
        let rechunker = Int16Rechunker()
        let lengths = [1, 511, 512, 513, 1024, 7, 2048, 341]
        let total = lengths.reduce(0, +)
        let expected = ramp(from: 0, count: total)

        var produced: [Int16] = []
        var cursor = 0
        for length in lengths {
            produced += rechunker.push(Array(expected[cursor..<(cursor + length)])).flatMap { $0 }
            cursor += length
        }
        produced += rechunker.drain()

        XCTAssertEqual(expected, produced)
    }

    // MARK: - 잔여 처리 3건

    func test청크보다_짧은_입력은_전부_잔여로_남는다() {
        let rechunker = Int16Rechunker()

        let chunks = rechunker.push(ramp(from: 0, count: readChunkSize - 1))

        XCTAssertTrue(chunks.isEmpty)
        XCTAssertEqual(readChunkSize - 1, rechunker.pendingCount)
        XCTAssertEqual(ramp(from: 0, count: readChunkSize - 1), rechunker.drain())
        XCTAssertEqual(0, rechunker.pendingCount)
    }

    func test정확히_배수면_잔여가_없다() {
        let rechunker = Int16Rechunker()

        let chunks = rechunker.push(ramp(from: 0, count: readChunkSize * 3))

        XCTAssertEqual(3, chunks.count)
        XCTAssertEqual(0, rechunker.pendingCount)
        XCTAssertTrue(rechunker.drain().isEmpty)
    }

    func test배수보다_하나_많으면_잔여가_하나_남는다() {
        let rechunker = Int16Rechunker()

        let chunks = rechunker.push(ramp(from: 0, count: readChunkSize * 2 + 1))

        XCTAssertEqual(2, chunks.count)
        XCTAssertEqual(1, rechunker.pendingCount)
        // 잔여는 마지막 샘플 하나여야 한다 - 앞을 깎는 대신 커서로 훑기 때문에 여기서 어긋나기 쉽다.
        XCTAssertEqual([Int16(truncatingIfNeeded: readChunkSize * 2)], rechunker.drain())
    }

    /// `drain()` 뒤에도 상태가 깨지지 않는지 - 잔여를 꺼낸 다음 이어서 밀면 그 지점부터 이어져야 한다.
    func testDrain_뒤에도_이어서_받을_수_있다() {
        let rechunker = Int16Rechunker()
        _ = rechunker.push(ramp(from: 0, count: 100))
        XCTAssertEqual(ramp(from: 0, count: 100), rechunker.drain())

        let chunks = rechunker.push(ramp(from: 1000, count: readChunkSize))

        XCTAssertEqual(1, chunks.count)
        XCTAssertEqual(ramp(from: 1000, count: readChunkSize), chunks[0])
    }
}
