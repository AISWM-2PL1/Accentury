import XCTest
@testable import AccenturyCore

/// `app/src/testDebug/java/com/accentury/app/audio/FilePcmSourceTest.kt`의 1:1 이식본.
final class FilePcmSourceTests: XCTestCase {

    /// 테스트용 WAV 바이트. `extraChunks`는 fmt 와 data 사이에 끼워 넣을 (id, payload) 쌍이다 -
    /// 편집기가 만든 파일에는 LIST 같은 청크가 그 자리에 흔히 들어간다.
    private func wavBytes(
        samples: [Int16],
        rate: Int = sampleRate,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        extraChunks: [(String, [UInt8])] = []
    ) -> Data {
        let dataBytes = samples.count * 2
        let extraBytes = extraChunks.reduce(0) { $0 + 8 + $1.1.count + ($1.1.count & 1) }
        var out = Data()
        func putInt32(_ v: Int32) {
            let bits = UInt32(bitPattern: v)
            for shift in stride(from: 0, through: 24, by: 8) {
                out.append(UInt8(truncatingIfNeeded: bits >> UInt32(shift)))
            }
        }
        func putInt16(_ v: Int16) {
            let bits = UInt16(bitPattern: v)
            out.append(UInt8(truncatingIfNeeded: bits))
            out.append(UInt8(truncatingIfNeeded: bits >> 8))
        }
        out.append(contentsOf: Array("RIFF".utf8))
        putInt32(Int32(36 + extraBytes + dataBytes))
        out.append(contentsOf: Array("WAVE".utf8))
        out.append(contentsOf: Array("fmt ".utf8))
        putInt32(16)
        putInt16(1)
        putInt16(Int16(channels))
        putInt32(Int32(rate))
        putInt32(Int32(rate * channels * bitsPerSample / 8))
        putInt16(Int16(channels * bitsPerSample / 8))
        putInt16(Int16(bitsPerSample))
        for (id, payload) in extraChunks {
            out.append(contentsOf: Array(id.utf8))
            putInt32(Int32(payload.count))
            out.append(contentsOf: payload)
            if payload.count & 1 == 1 { out.append(0) }
        }
        out.append(contentsOf: Array("data".utf8))
        putInt32(Int32(dataBytes))
        for sample in samples { putInt16(sample) }
        return out
    }

    private func collect(_ bytes: Data, chunkSize size: Int = chunkSize) async throws -> [[Int16]] {
        var chunks: [[Int16]] = []
        let source = FilePcmSource(open: { bytes }, chunkSize: size, realtime: false)
        for try await chunk in source.recordingStream() { chunks.append(chunk) }
        return chunks
    }

    func test마지막_청크만_짧고_총_샘플_수가_data_길이와_같다() async throws {
        let samples = (0..<(chunkSize * 3 + 100)).map { Int16($0 % 1000) }

        let chunks = try await collect(wavBytes(samples: samples))

        XCTAssertEqual(4, chunks.count)
        for chunk in chunks.dropLast() { XCTAssertEqual(chunkSize, chunk.count) }
        XCTAssertEqual(100, chunks.last?.count)
        XCTAssertEqual(samples.count, chunks.reduce(0) { $0 + $1.count })
    }

    func test기본_청크는_마이크와_같은_readChunkSize다_KAN_105() async throws {
        // 가짜 마이크가 실제 마이크와 다른 페이스로 흘리면 곡선이 자라는 모습도 달라져,
        // 파일로 눈으로 다듬은 결과가 실기기에서 그대로 재현되지 않는다.
        let samples = (0..<(readChunkSize * 2)).map { Int16($0 % 1000) }
        let bytes = wavBytes(samples: samples)

        var chunks: [[Int16]] = []
        let source = FilePcmSource(open: { bytes }, realtime: false)
        for try await chunk in source.recordingStream() { chunks.append(chunk) }

        XCTAssertEqual(2, chunks.count)
        for chunk in chunks { XCTAssertEqual(readChunkSize, chunk.count) }
    }

    func test샘플_값이_리틀엔디언_그대로_전달된다() async throws {
        // 부호·상하위 바이트가 갈리는 값들 - 엔디언이 뒤집히면 바로 어긋난다.
        let samples: [Int16] = [0, 1, -1, 256, -256, 32767, -32768, 4660]

        let chunks = try await collect(wavBytes(samples: samples), chunkSize: 4)

        XCTAssertEqual(2, chunks.count)
        XCTAssertEqual(samples, chunks.flatMap { $0 })
    }

    func testFmt와_data_사이에_다른_청크가_있어도_data를_찾는다() async throws {
        let samples = (0..<10).map { Int16($0 * 7) }
        // 홀수 길이 페이로드까지 넣어 패딩 1바이트 건너뛰기도 함께 확인한다.
        let bytes = wavBytes(
            samples: samples,
            extraChunks: [("LIST", [UInt8](repeating: 0x41, count: 9)), ("fact", [UInt8](repeating: 0, count: 4))]
        )

        let chunks = try await collect(bytes)

        XCTAssertEqual(samples, chunks.flatMap { $0 })
    }

    func test스테레오는_거부한다() async {
        await assertWavRejected(wavBytes(samples: [Int16](repeating: 0, count: 100), channels: 2))
    }

    func test8bit는_거부한다() async {
        await assertWavRejected(wavBytes(samples: [Int16](repeating: 0, count: 100), bitsPerSample: 8))
    }

    func test다른_샘플레이트는_거부한다_리샘플하지_않는다() async {
        await assertWavRejected(wavBytes(samples: [Int16](repeating: 0, count: 100), rate: 44_100))
    }

    func testWAV가_아니면_거부한다() async {
        await assertWavRejected(Data([UInt8](repeating: 0x30, count: 64)))
    }

    private func assertWavRejected(
        _ bytes: Data,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        do {
            _ = try await collect(bytes)
            XCTFail("WavFormatError를 던져야 한다", file: file, line: line)
        } catch is WavFormatError {
            // 기대한 경로
        } catch {
            XCTFail("WavFormatError가 아님: \(error)", file: file, line: line)
        }
    }

    func test엔진에_물리면_파일_길이만큼의_Success가_나온다() async {
        let samples = (0..<(sampleRate * 2)).map { Int16($0 % 500) }
        let bytes = wavBytes(samples: samples)
        let engine = RecordingEngine(source: FilePcmSource(open: { bytes }, realtime: false))

        let outcome = await engine.record { _ in }

        guard case let .success(pcm, durationMs, _) = outcome else {
            return XCTFail("Success가 아님: \(outcome)")
        }
        XCTAssertEqual(samples.count, pcm.count)
        XCTAssertEqual(2_000, durationMs)
        XCTAssertEqual(samples, pcm)
    }

    /// 안드로이드 디버그 소스셋의 `fake_mic.wav`를 그대로 물려 본다. 같은 레포에 있는 같은
    /// 파일이라 두 플랫폼의 가짜 마이크가 같은 음성을 흘린다는 확인이 된다.
    /// (안드로이드 원본은 `src/debug/assets`를 cwd 기준으로 찾는다. 이쪽은 소스 파일 위치에서
    ///  레포 루트를 거슬러 올라간다 - `swift test`의 cwd는 패키지 폴더라 기준이 다르다.)
    func test배치된_fake_mic_asset이_그대로_재생된다() async throws {
        guard let asset = Self.androidFakeMicAsset() else {
            throw XCTSkip("app/src/debug/assets/fake_mic.wav를 찾지 못했다 - 안드로이드 트리 밖에서 도는 중")
        }
        let bytes = try Data(contentsOf: asset)
        let engine = RecordingEngine(source: FilePcmSource(open: { bytes }, realtime: false))

        let outcome = await engine.record { _ in }

        guard case let .success(_, durationMs, _) = outcome else {
            return XCTFail("Success가 아님: \(outcome)")
        }
        // 2.5초 파일. 마지막 청크가 짧아 청크 하나(32ms)만큼의 오차는 허용한다.
        XCTAssertLessThanOrEqual(
            abs(durationMs - 2_500), Int64(readChunkSize) * 1000 / Int64(sampleRate),
            "durationMs=\(durationMs)"
        )
    }

    private static func androidFakeMicAsset() -> URL? {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("app/src/debug/assets/fake_mic.wav")
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        return nil
    }
}
