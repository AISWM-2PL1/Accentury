import AVFoundation
import AccenturyCore
import Foundation
import os

/// 안드로이드 `app/src/main/java/com/accentury/app/audio/AudioRecorder.kt`의 iOS 대응물.
///
/// 안드로이드가 `AudioRecord`에서 16kHz 모노 16bit를 **그대로** 읽어 오는 것과 달리, iOS는
/// 하드웨어가 주는 포맷(아이폰 마이크는 보통 44.1/48kHz Float32)을 앱이 받아 직접 내려야 한다.
/// 그래서 이 파일에는 안드로이드에 없는 변환 단계가 하나 더 있다 — `AVAudioConverter`로
/// 16kHz 모노 Int16으로 내린 뒤, `Int16Rechunker`가 512샘플(32ms) 단위로 다시 잘라 흘린다.
///
/// ## 스레드 배치
/// 탭 콜백은 실시간 오디오 스레드다. 여기서 시간이 오래 걸리거나 잠길 수 있는 일을 하면
/// 콜백이 마감을 놓쳐 소리가 끊긴다(글리치). 그래서 탭에서는 **들어온 버퍼를 복사해
/// 변환 큐로 넘기기만** 하고, 변환·재절단·방출은 전용 직렬 큐에서 한다.
///
/// 복사가 필요한 이유: `AVAudioEngine`은 탭 버퍼를 재사용한다. 참조만 넘기면 변환 큐가
/// 읽는 사이에 다음 콜백이 같은 메모리를 덮어써 소리가 뒤섞인다 — 안드로이드가
/// `buffer.copyOf(read)`로 막은 것과 정확히 같은 함정이다.
///
/// 남은 탭 스레드 작업은 버퍼 1개 할당 + `memcpy` 한 번이다. 할당까지 없애려면 락 없는
/// 링 버퍼가 필요한데, 락을 쓰는 풀은 오디오 스레드에서 우선순위 역전을 부를 수 있어
/// 오히려 나쁘다. 실기기 프로파일링에서 글리치가 확인되기 전까지는 이 선에서 멈춘다.
///
/// ## 왜 `.measurement` 모드인가
/// iOS는 녹음 세션 모드에 따라 OS가 음성 처리(자동 이득 조절·노이즈 억제·에코 제거)를
/// 신호에 **먼저** 걸어 준다. 통화 앱에는 좋지만 우리에게는 독이다 — AGC가 음량을 평탄하게
/// 만들면 볼륨 판정(`AudioQuality`)이 실제 발화 크기를 못 보고, 잡음 억제가 배음을 깎으면
/// YIN이 기본 주파수를 놓쳐 곡선이 조각난다. `.measurement`는 그 처리를 끄고 마이크 신호를
/// 날것으로 준다. 안드로이드에서 `MediaRecorder.AudioSource.MIC`(가공 없음)를 고르고
/// `VOICE_RECOGNITION`/`VOICE_COMMUNICATION`을 피한 것과 정확히 같은 판단이다
/// (`docs/wiki/audio-capture.md`).
///
/// 블루투스 입력(`.allowBluetooth`)도 켜지 않는다. HFP로 잡히면 8~16kHz 대역제한에 자체
/// 처리까지 얹혀 오는데, 그건 KAN-105에서 안드로이드가 이미 한 번 밟은 함정이다
/// (`docs/wiki/troubleshooting.md` 33번). 옵션을 안 주면 iOS는 내장 마이크를 쓴다.
final class AudioRecorder: PcmSource {

    /// 탭이 한 번에 넘겨받을 프레임 수(입력 샘플레이트 기준). 48kHz에서 약 21ms다.
    /// 안드로이드의 `AudioRecord` 내부 버퍼처럼 읽기 단위와 별개로 잡는 값이고, 실제 방출
    /// 단위는 `Int16Rechunker`가 `readChunkSize`(512샘플 = 32ms)로 다시 맞춘다.
    fileprivate static let tapBufferSize: AVAudioFrameCount = 1024

    /// 소비자가 밀렸을 때 버퍼에 쌓아 둘 청크 수 (512샘플 × 128 ≈ 4초).
    ///
    /// `.unbounded`로 두지 않는 이유: 마이크는 실시간이라 소비가 멈춰도 생산은 계속된다.
    /// 무한 버퍼면 소비자가 멎은 만큼 메모리가 자란다. 4초는 분석(릴리스에서 0.23ms/프레임)이
    /// 32ms 주기를 못 따라갈 일이 사실상 없다는 전제에서 넉넉하게 잡은 여유다 — 이걸 넘겨
    /// 청크가 버려지는 상황이면 이미 기기가 곡선을 못 그리고 있다.
    private static let bufferedChunkLimit = 128

    func recordingStream() -> AsyncThrowingStream<[Int16], Error> {
        AsyncThrowingStream([Int16].self, bufferingPolicy: .bufferingNewest(Self.bufferedChunkLimit)) { continuation in
            let session = CaptureSession(continuation: continuation)
            // 소비 측이 멈추면(엔진이 10초에서 끊거나 사용자가 정지) 여기로 들어온다.
            // 안드로이드 `flow { ... } finally { audioRecord.release() }` 자리 — 어떤 경로로
            // 끝나든 마이크를 반드시 반납한다. start()보다 **먼저** 걸어야 시작 실패로
            // 곧바로 finish될 때도 정리가 돈다.
            continuation.onTermination = { _ in session.tearDown() }
            session.start()
        }
    }
}

/// 녹음 1회분의 AVAudioEngine 배선. `recordingStream()` 호출마다 새로 만든다 —
/// 안드로이드의 콜드 Flow가 수집할 때마다 `AudioRecord`를 새로 여는 것과 같다.
private final class CaptureSession {

    private static let log = Logger(subsystem: "com.accentury.app", category: "audio")

    private let continuation: AsyncThrowingStream<[Int16], Error>.Continuation
    private let engine = AVAudioEngine()

    /// 변환·재절단 전용 직렬 큐. 실시간 스레드는 아니지만 마감이 있는 일이라
    /// `.userInteractive`로 올린다. 직렬이라 아래 세 필드는 락 없이 이 큐 안에서만 만진다.
    private let convertQueue = DispatchQueue(label: "accentury.audio.convert", qos: .userInteractive)
    private var converter: AVAudioConverter?
    /// 변환 출력 버퍼. 콜백마다 새로 잡지 않고 한 번 잡아 재사용한다.
    private var conversionBuffer: AVAudioPCMBuffer?
    private let rechunker = Int16Rechunker()

    /// 정리는 탭 스레드·변환 큐·소비자 스레드 어디서든 들어올 수 있다. 아래 네 필드를
    /// 하나의 락으로 직렬화해 "무엇까지 세워 놨는지"를 정리 시점에 정확히 알게 한다.
    private let stateLock = NSLock()
    private var tornDown = false
    private var tapInstalled = false
    private var interruptionObserver: NSObjectProtocol?

    /// 탭 스레드에서 `removeTap`·`engine.stop()`·`setActive(false)`를 직접 부르면 교착이
    /// 나거나 오디오 스레드를 막는다. 정리는 전부 이 큐로 던진다.
    private let teardownQueue = DispatchQueue(label: "accentury.audio.teardown")

    init(continuation: AsyncThrowingStream<[Int16], Error>.Continuation) {
        self.continuation = continuation
    }

    func start() {
        do {
            try configureSession()
            try wireEngine()
            observeInterruptions()
        } catch let error as CaptureError {
            continuation.finish(throwing: error)
        } catch {
            continuation.finish(throwing: CaptureError("녹음 시작 실패 — \(error.localizedDescription)"))
        }
    }

    private func configureSession() throws {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .measurement)
            try session.setActive(true)
        } catch {
            // 안드로이드 "AudioRecord 초기화 실패 — 권한 없음 또는 마이크 점유 중"과 같은 자리.
            // iOS는 마이크 권한이 없거나 다른 앱이 세션을 물고 있으면 여기서 걸린다.
            throw CaptureError("녹음 세션 활성화 실패 — 권한 없음 또는 마이크 점유 중")
        }
        // 입력 경로가 없는데 inputNode를 건드리면 예외가 아니라 프레임워크 안에서 죽는다.
        // **이 가드가 시뮬레이터 크래시를 막지는 못한다** — 시뮬레이터는 입력이 실제로 없어도
        // isInputAvailable=true, 경로 MicrophoneBuiltIn으로 보고한다(README «시뮬레이터에서는
        // 마이크 경로를 확인할 수 없다» 참고). 실기기에서 이어폰만 빠진 상태 같은 경우를 위한
        // 값싼 보험이라고 보면 된다.
        guard session.isInputAvailable else {
            throw CaptureError("마이크 입력 없음 — 사용 가능한 입력 장치가 없음")
        }
    }

    private func wireEngine() throws {
        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
            throw CaptureError("마이크 입력 없음 — 권한 없음 또는 입력 장치 없음")
        }

        guard let output = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Double(AccenturyCore.sampleRate),
            channels: 1,
            interleaved: true
        ) else {
            throw CaptureError("출력 포맷 생성 실패 — 16kHz 모노 16bit")
        }
        guard let converter = AVAudioConverter(from: inputFormat, to: output) else {
            throw CaptureError("오디오 변환기 생성 실패 — 입력 \(Int(inputFormat.sampleRate))Hz \(inputFormat.channelCount)ch")
        }

        // 출력 버퍼 용량: 탭 한 번 분량을 변환한 결과 + 리샘플러가 물고 있던 여유.
        // 탭이 예고보다 큰 버퍼를 줘도 아래 변환 루프가 여러 번 나눠 받으므로 넘치지 않는다.
        let ratio = max(output.sampleRate / inputFormat.sampleRate, 1)
        let capacity = AVAudioFrameCount(Double(AudioRecorder.tapBufferSize) * ratio) + 4096
        guard let conversionBuffer = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: capacity) else {
            throw CaptureError("오디오 변환 버퍼 확보 실패")
        }
        self.converter = converter
        self.conversionBuffer = conversionBuffer

        input.installTap(onBus: 0, bufferSize: AudioRecorder.tapBufferSize, format: inputFormat) { [weak self] buffer, _ in
            self?.receive(buffer)
        }
        stateLock.lock()
        tapInstalled = true
        stateLock.unlock()

        engine.prepare()
        do {
            try engine.start()
        } catch {
            throw CaptureError("녹음 시작 실패 — \(error.localizedDescription)")
        }
    }

    private func observeInterruptions() {
        let observer = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: nil
        ) { [weak self] note in
            guard
                let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                let type = AVAudioSession.InterruptionType(rawValue: raw),
                type == .began
            else { return }
            // 전화·알람이 끼어들면 마이크를 뺏긴다. 반쪽짜리 녹음을 성공으로 넘기지 않고
            // 실패로 끊는다 — 안드로이드가 `read`의 음수 코드를 CaptureException으로 올린 자리다.
            // 재개(.ended)는 다루지 않는다: 사용자가 다시 녹음 버튼을 누르는 쪽이 맞다.
            self?.continuation.finish(throwing: CaptureError("녹음 중단 — 인터럽션"))
        }
        stateLock.lock()
        let alreadyTornDown = tornDown
        if !alreadyTornDown { interruptionObserver = observer }
        stateLock.unlock()
        // 등록 직전에 정리가 끝났으면 우리가 직접 뗀다 — 안 그러면 이 옵저버만 남는다.
        if alreadyTornDown { NotificationCenter.default.removeObserver(observer) }
    }

    // MARK: - 탭 스레드

    /// 실시간 오디오 스레드에서 불린다. 복사와 디스패치 말고는 아무것도 하지 않는다.
    private func receive(_ buffer: AVAudioPCMBuffer) {
        guard buffer.frameLength > 0, let copied = Self.copy(buffer) else { return }
        convertQueue.async { [weak self] in self?.convert(copied) }
    }

    /// 탭 버퍼를 같은 포맷의 새 버퍼로 복사한다. 포맷을 가리지 않도록 채널 배열을 직접 훑는다
    /// (인터리브·논인터리브, Float32·Int16·Int32 모두 같은 코드로 처리된다).
    private static func copy(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength) else {
            return nil
        }
        // mDataByteSize가 frameLength에 맞춰 잡히도록 복사 전에 길이를 정한다.
        copy.frameLength = buffer.frameLength
        let source = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: buffer.audioBufferList))
        let destination = UnsafeMutableAudioBufferListPointer(copy.mutableAudioBufferList)
        guard source.count == destination.count else { return nil }
        for i in 0..<source.count {
            guard let from = source[i].mData, let to = destination[i].mData else { return nil }
            memcpy(to, from, Int(min(source[i].mDataByteSize, destination[i].mDataByteSize)))
        }
        return copy
    }

    // MARK: - 변환 큐 (직렬)

    private func convert(_ input: AVAudioPCMBuffer) {
        guard let converter, let output = conversionBuffer else { return }

        // 입력 블록은 변환기가 더 필요하다고 할 때마다 불린다. 이번 버퍼는 한 번만 주고,
        // 그 뒤로는 `.noDataNow`로 "지금은 없다"를 알린다 — 같은 버퍼를 두 번 주면 소리가 겹친다.
        var supplied = false
        while true {
            var conversionError: NSError?
            let status = converter.convert(to: output, error: &conversionError) { _, outStatus in
                if supplied {
                    outStatus.pointee = .noDataNow
                    return nil
                }
                supplied = true
                outStatus.pointee = .haveData
                return input
            }
            switch status {
            case .error:
                let message = conversionError?.localizedDescription ?? "알 수 없는 원인"
                continuation.finish(throwing: CaptureError("오디오 변환 실패 — \(message)"))
                return
            case .haveData:
                emit(output)
                // 버퍼가 꽉 찼다면 아직 변환기 안에 남아 있을 수 있다. 한 번 더 받아 간다.
                if output.frameLength < output.frameCapacity { return }
            case .inputRanDry, .endOfStream:
                emit(output)
                return
            @unknown default:
                return
            }
        }
    }

    /// 변환 결과를 512샘플 단위로 잘라 흘린다. `rechunker`는 이 큐에서만 만지므로 락이 없다.
    private func emit(_ buffer: AVAudioPCMBuffer) {
        guard buffer.frameLength > 0, let samples = buffer.int16ChannelData?[0] else { return }
        let chunks = rechunker.push(UnsafeBufferPointer(start: samples, count: Int(buffer.frameLength)))
        // 청크는 값 복사본(`Array`)이라 뒤이은 변환이 덮어쓸 여지가 없다.
        for chunk in chunks { continuation.yield(chunk) }
    }

    // MARK: - 정리

    /// 어떤 경로로 끝나든 마이크를 반납한다.
    ///
    /// 512를 못 채운 잔여(<32ms)는 버린다. 이 시점에는 소비 측이 이미 스트림을 놓아
    /// `yield`가 무시되기 때문이고, 안드로이드도 정지 순간 `read` 중이던 버퍼를 그대로
    /// 버리므로 잘려 나가는 길이가 양쪽 같다.
    func tearDown() {
        stateLock.lock()
        if tornDown {
            stateLock.unlock()
            return
        }
        tornDown = true
        // 어디까지 세워 놨는지를 락 안에서 확정해 들고 나간다. 시작 도중 실패하면
        // 탭이 아직 없는데 removeTap을 부르는 일이 생기고, 그건 inputNode를 다시 건드려
        // 실패 원인(입력 없음)과 같은 자리에서 또 죽을 수 있다.
        let hadTap = tapInstalled
        tapInstalled = false
        let observer = interruptionObserver
        interruptionObserver = nil
        stateLock.unlock()

        if let observer { NotificationCenter.default.removeObserver(observer) }

        // 탭 스레드에서 불릴 수 있어 동기로 처리하지 않는다.
        teardownQueue.async { [engine] in
            if hadTap { engine.inputNode.removeTap(onBus: 0) }
            if engine.isRunning { engine.stop() }
            do {
                // 세션 비활성화는 어느 단계에서 실패했든 무조건 시도한다 — 활성화까지만
                // 해 놓고 뒤에서 실패한 경우 여기서 놓으면 마이크 표시등이 남는다.
                // 애초에 활성화된 적이 없으면 오류가 돌아오고, 그건 무시해도 되는 오류다.
                try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            } catch {
                Self.log.debug("오디오 세션 비활성화 실패(무시): \(error.localizedDescription, privacy: .public)")
            }
        }
    }
}
