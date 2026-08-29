import Foundation

/// WAV 헤더가 우리 스펙(16kHz 모노 16bit PCM)과 다르거나 파일이 깨졌다는 신호.
///
/// 안드로이드는 `require(...)`가 던지는 `IllegalArgumentException`이고, 그건
/// `RecordingEngine`의 catch 절에 걸리지 않아 호출부까지 올라가 앱을 죽인다 —
/// 개발자가 넣은 파일이 스펙 밖이라는 건 즉시 알아야 할 설정 실수이기 때문이다.
/// Swift에서는 `record`가 throws가 아니라 이 오류도 Failure로 접히는데, 그래도
/// 타입을 따로 두는 이유는 테스트가 "포맷 거부"와 "캡처 실패"를 구분해야 해서다.
public struct WavFormatError: Error, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// WAV 파일을 마이크 대신 흘려보내는 `PcmSource`. 안드로이드
/// `app/src/debug/java/com/accentury/app/audio/FilePcmSource.kt`의 이식본이다.
///
/// 시뮬레이터에는 마이크가 없고(맥 입력을 그대로 받지도 않는다) 실기기라도 매번 같은 발화를
/// 낼 수는 없다. 피치 곡선을 눈으로 다듬으려면 같은 음성이 같은 속도로 들어와야 해서,
/// 개발 중에만 이 소스를 마이크 자리에 끼운다.
///
/// **이 타입은 Core(릴리스에도 실리는 계층)에 있지만 배선은 앱 쪽 `defaultPcmSource()`가
/// `#if DEBUG` 안에서만 한다.** 안드로이드는 `src/debug` 소스셋으로 클래스 자체를 릴리스에서
/// 지우지만, SwiftPM 패키지에는 그런 구성별 소스셋이 없다. 대신 "릴리스 바이너리에 파일
/// 재생 **경로**가 없다"를 두 겹으로 보장한다 — 호출부가 `#if DEBUG`이고, 파일명을 넘기는
/// `FAKE_MIC_ASSET` 키가 Info-Release.plist에 아예 없다.
///
/// `open`이 `Data` 자체가 아니라 여는 클로저인 이유: 녹음은 여러 번 일어나고(재녹음·재응시)
/// 소스 하나가 매번 처음부터 흘려야 한다. 안드로이드가 `InputStream`을 매번 새로 연 것과
/// 같은 이유이고, 다만 Swift에서는 통째로 읽은 `Data`에 커서를 두는 쪽을 골랐다 —
/// 이 파일은 길어야 10초(320KB)이고, `InputStream`의 부분 읽기 규약을 흉내 내는 것보다
/// 커서 하나가 읽기 쉽다.
///
/// 리샘플·다운믹스는 하지 않는다. 엔진 전체가 16kHz 모노 16bit를 가정하고 있어, 다른 포맷을
/// 조용히 변환해 주면 실제 마이크와 다른 조건에서 곡선을 보게 된다.
public final class FilePcmSource: PcmSource {

    private let open: () throws -> Data
    /// 마이크와 같은 단위로 흘린다 - 청크 길이가 다르면 곡선이 자라는 페이스도 달라진다.
    private let chunkSize: Int
    /// 청크 하나가 실제로 담는 시간만큼 쉬어 가며 흘릴지 여부.
    ///
    /// 마이크와 같은 페이스여야 스무딩 계수나 체감 지연을 평가할 수 있다. 테스트에서는 파일을
    /// 통째로 즉시 흘려야 하므로 false로 준다.
    private let realtime: Bool

    public init(
        open: @escaping () throws -> Data,
        chunkSize: Int = readChunkSize,
        realtime: Bool = true
    ) {
        self.open = open
        self.chunkSize = chunkSize
        self.realtime = realtime
    }

    public func recordingStream() -> AsyncThrowingStream<[Int16], Error> {
        let open = self.open
        let chunkSize = self.chunkSize
        let realtime = self.realtime
        let state = StreamState()

        // `unfolding` 생성자는 **당겨 가는(pull) 스트림**이다. 소비자가 다음 청크를 요구할 때만
        // 아래 클로저가 불리므로 Kotlin `flow { ... emit(...) }`와 같은 콜드·역압 의미가 된다.
        // continuation 방식으로 짜면 버퍼가 무한히 앞서 달려 파일 전체를 즉시 메모리에 쌓는다.
        return AsyncThrowingStream(unfolding: { () async throws -> [Int16]? in
            // 안드로이드 `while (remaining > 0 && currentCoroutineContext().isActive)`.
            if Task.isCancelled { return nil }

            if state.reader == nil {
                let reader = WavByteReader(try open())
                state.remaining = try parseWavHeader(reader)
                state.reader = reader
            }
            guard let reader = state.reader, state.remaining > 0 else { return nil }

            let want = min(chunkSize * 2, state.remaining)
            let bytes = reader.readAtMost(want)
            // data 청크 길이가 실제 파일보다 길게 적혀 있어도 여기서 멈춘다.
            if bytes.count < 2 { return nil }

            var samples = [Int16](repeating: 0, count: bytes.count / 2)
            for i in 0..<samples.count {
                // 리틀엔디언: 낮은 바이트가 앞이다.
                samples[i] = Int16(bitPattern: UInt16(bytes[i * 2]) | (UInt16(bytes[i * 2 + 1]) << 8))
            }
            if realtime {
                // 안드로이드 `delay(samples.size * 1000L / SAMPLE_RATE)`와 같은 정수 나눗셈이다
                // (512샘플 → 32ms). ns로 먼저 환산하면 절단 지점이 달라져 페이스가 미세하게 갈린다.
                let ms = Int64(samples.count) * 1000 / Int64(sampleRate)
                if ms > 0 { try await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000) }
            }
            state.remaining -= bytes.count
            return samples
        })
    }

    /// 스트림 하나가 들고 가는 읽기 상태. 클로저가 여러 번 불리는 사이 살아 있어야 해서
    /// 값 타입 캡처가 아니라 참조 하나로 묶는다.
    private final class StreamState {
        var reader: WavByteReader?
        var remaining = 0
    }
}

/// `Data` 위의 읽기 커서. 안드로이드가 `InputStream`에 붙여 쓰던 확장 세 개
/// (`readAtMost`·`readFullyOrThrow`·`skipExactly`)를 그대로 옮겼다.
final class WavByteReader {
    private let data: Data
    private var offset = 0

    init(_ data: Data) {
        self.data = data
    }

    /// `want` 바이트를 목표로 채우되, 데이터가 먼저 끝나면 그때까지 읽은 만큼만 돌려준다.
    func readAtMost(_ want: Int) -> [UInt8] {
        let start = data.startIndex + offset
        let end = min(start + want, data.endIndex)
        if end <= start { return [] }
        offset += end - start
        return [UInt8](data[start..<end])
    }

    func readFullyOrThrow(_ count: Int, _ what: String) throws -> [UInt8] {
        let bytes = readAtMost(count)
        if bytes.count != count {
            throw WavFormatError("WAV가 중간에 끊김 - \(what) 를 읽지 못함")
        }
        return bytes
    }

    func skipExactly(_ count: Int) throws {
        if readAtMost(count).count != count {
            throw WavFormatError("WAV가 중간에 끊김 - 청크를 건너뛰지 못함")
        }
    }
}

/// RIFF 청크를 훑어 fmt 를 검증하고 data 청크 앞까지 `input`을 진행시킨 뒤, data 바이트 수를 돌려준다.
///
/// 44바이트 고정 헤더로 가정하지 않는 이유: 편집기가 만든 WAV에는 LIST·fact 같은 청크가 fmt 와
/// data 사이에 끼어 있는 경우가 흔하다. 그런 파일을 넣으면 메타데이터를 소리로 재생하게 된다.
func parseWavHeader(_ input: WavByteReader) throws -> Int {
    let riff = try input.readFullyOrThrow(12, "RIFF 헤더")
    guard ascii(riff, 0, 4) == "RIFF", ascii(riff, 8, 4) == "WAVE" else {
        throw WavFormatError("WAV 파일이 아님 - RIFF/WAVE 시그니처 없음")
    }

    var fmtSeen = false
    while true {
        let chunkHeader = try input.readFullyOrThrow(8, "청크 헤더")
        let id = ascii(chunkHeader, 0, 4)
        let size = leInt(chunkHeader, 4)
        guard size >= 0 else {
            throw WavFormatError("청크 크기가 비정상 - id=\(id) size=\(size)")
        }
        switch id {
        case "fmt ":
            guard size >= 16 else { throw WavFormatError("fmt 청크가 너무 짧음 - size=\(size)") }
            let fmt = try input.readFullyOrThrow(size, "fmt 청크")
            let audioFormat = leShort(fmt, 0)
            let channels = leShort(fmt, 2)
            let rate = leInt(fmt, 4)
            let bitsPerSample = leShort(fmt, 14)
            guard audioFormat == 1 else { throw WavFormatError("PCM이 아님 - audioFormat=\(audioFormat)") }
            guard channels == 1 else { throw WavFormatError("모노가 아님 - channels=\(channels)") }
            guard bitsPerSample == 16 else { throw WavFormatError("16bit가 아님 - bitsPerSample=\(bitsPerSample)") }
            guard rate == sampleRate else {
                throw WavFormatError("샘플레이트가 \(sampleRate)Hz가 아님 - sampleRate=\(rate)")
            }
            fmtSeen = true

        case "data":
            guard fmtSeen else { throw WavFormatError("fmt 청크 없이 data 청크가 먼저 나옴") }
            return size

        default:
            // RIFF 청크는 짝수 경계에 놓인다 - 홀수 길이면 뒤에 패딩 1바이트가 붙는다.
            try input.skipExactly(size + (size & 1))
        }
    }
}

private func ascii(_ bytes: [UInt8], _ offset: Int, _ length: Int) -> String {
    String(decoding: bytes[offset..<(offset + length)], as: UTF8.self)
}

private func leShort(_ bytes: [UInt8], _ offset: Int) -> Int {
    Int(bytes[offset]) | (Int(bytes[offset + 1]) << 8)
}

/// 안드로이드 쪽은 32비트 `Int`라 최상위 비트가 서면 음수가 된다(그래서 호출부가 `size >= 0`을
/// 검사한다). 같은 판정을 내려면 Swift도 64비트로 넓히지 말고 Int32를 거쳐야 한다.
private func leInt(_ bytes: [UInt8], _ offset: Int) -> Int {
    let bits = UInt32(bytes[offset])
        | (UInt32(bytes[offset + 1]) << 8)
        | (UInt32(bytes[offset + 2]) << 16)
        | (UInt32(bytes[offset + 3]) << 24)
    return Int(Int32(bitPattern: bits))
}
