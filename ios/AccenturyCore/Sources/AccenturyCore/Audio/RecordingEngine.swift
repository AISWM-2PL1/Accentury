import Foundation

/// 안드로이드 `app/src/main/java/com/accentury/app/audio/RecordingEngine.kt`의 1:1 이식본.
///
/// 캡처 소스에서 흘러오는 PCM 청크를 모아 10초에서 자동으로 끊고, 흘러가는 동안
/// 경과 시간·RMS·겹침 창별 F0를 진행 리포트로 올려 준다.
///
/// 안드로이드는 기본 인자로 `AudioRecorder()`를 물고 있지만 여기서는 소스를 반드시 받는다 —
/// iOS 캡처(`AVAudioEngine`)는 앱 타깃에 있고 이 패키지는 그쪽을 볼 수 없다.
/// 배선은 앱 쪽 `defaultPcmSource()`가 한다.
public final class RecordingEngine: @unchecked Sendable {

    /// 분석 창 1개의 F0. timestampMs는 창 **중앙** 샘플의 시각이고, 무성음이면 pitchHz가 nil이다.
    public struct PitchFrame: Sendable, Equatable {
        public let timestampMs: Int64
        public let pitchHz: Float?

        public init(timestampMs: Int64, pitchHz: Float?) {
            self.timestampMs = timestampMs
            self.pitchHz = pitchHz
        }
    }

    public struct Progress: Sendable {
        public let elapsedMs: Int64
        public let rms: Double
        /// 이번 청크가 완성시킨 분석 창들의 F0. 청크 길이에 따라 0개 이상이고 32ms 간격이다.
        public let pitchFrames: [PitchFrame]

        public init(elapsedMs: Int64, rms: Double, pitchFrames: [PitchFrame]) {
            self.elapsedMs = elapsedMs
            self.rms = rms
            self.pitchFrames = pitchFrames
        }
    }

    /// 안드로이드의 `sealed interface Outcome`. Swift에서는 enum이 같은 자리다 —
    /// 케이스가 둘뿐이고 상속으로 늘릴 계획이 없어 열거형이 오히려 정확하다(빠뜨린 분기가 컴파일 오류).
    public enum Outcome {
        case success(pcm: [Int16], durationMs: Int64, autoStopped: Bool)
        case failure(reason: String)
    }

    public static let maxDurationMs: Int64 = 10_000
    public static let maxSamples = Int(Int64(sampleRate) * maxDurationMs / 1000)

    private let source: PcmSource

    /// 안드로이드 `AtomicReference<AtomicBoolean?>`. 지금 도는 녹음의 정지 깃발을 가리키고,
    /// 녹음이 없으면 nil이다. 깃발을 **매 녹음마다 새로 만드는** 이유가 핵심이다 —
    /// `requestStop()`이 지난 녹음의 깃발을 세우거나, 녹음 전 정지 요청이 다음 녹음을
    /// 시작하자마자 끝내 버리는 일이 없어야 한다.
    private let sessions = SessionSlot()

    public init(source: PcmSource) {
        self.source = source
    }

    public func requestStop() {
        sessions.current()?.set()
    }

    public func record(onProgress: (Progress) -> Void) async -> Outcome {
        let stopRequested = StopFlag()
        sessions.install(stopRequested)
        defer {
            // 안드로이드 `compareAndSet(stopRequested, null)` — 그 사이 다음 녹음이 시작돼
            // 다른 깃발이 걸려 있으면 건드리지 않는다.
            sessions.clear(ifCurrent: stopRequested)
        }

        var chunks: [[Int16]] = []
        var totalSamples = 0
        // 프레이머는 녹음 1회분 상태다. 이전 녹음의 잔여 샘플이 섞이지 않도록 여기서 새로 만든다.
        let framer = OverlappedFramer()

        do {
            for try await chunk in source.recordingStream() {
                // 안드로이드 `takeWhile { !stopRequested.get() && totalSamples < MAX_SAMPLES }`와
                // 같은 자리다. Kotlin의 takeWhile은 술어를 **수집 전에** 보므로, 한계를 넘기는
                // 그 청크는 아직 조건이 참일 때 들어와 통째로 수집되고 다음 청크에서 끊긴다.
                // (10초 = 160000샘플, 청크 2048 → 79번째 청크까지 들어와 161792샘플이 되고
                //  아래에서 160000으로 잘린다.) 그래서 검사가 append보다 앞에 있어야 한다.
                if stopRequested.isSet || totalSamples >= Self.maxSamples { break }
                chunks.append(chunk)
                totalSamples += chunk.count
                let pitchFrames = framer.push(chunk).map { frame in
                    PitchFrame(
                        // 창 시작이 아니라 **중앙**의 시각이다. YIN이 낸 F0는 창 하나(128ms)
                        // 전체를 대표하는 값이라 대표 시각도 그 한가운데가 맞다. 시작 시각으로
                        // 찍으면 곡선이 실제보다 64ms 앞당겨 그려져, 같은 시간축에 놓인 가이드
                        // 곡선과 정렬이 어긋난다.
                        timestampMs: (frame.startSampleIndex + Int64(frame.samples.count / 2))
                            * 1000 / Int64(sampleRate),
                        pitchHz: YinPitchEstimator.estimate(frame.samples)
                    )
                }
                onProgress(
                    Progress(
                        elapsedMs: Int64(min(totalSamples, Self.maxSamples)) * 1000 / Int64(sampleRate),
                        rms: calculateRms(chunk),
                        pitchFrames: pitchFrames
                    )
                )
            }
        } catch let error as CaptureError {
            return .failure(reason: error.message)
        } catch is CancellationError {
            // 안드로이드는 CancellationException을 잡지 않고 호출부로 던진다(코루틴 규칙).
            // 여기는 `record`가 throws가 아니라 Outcome을 돌려주는 함수라 던질 자리가 없어
            // Failure로 접는다. 상위 Task 취소는 화면 이탈처럼 이미 결과를 안 쓰는 상황이라
            // 이 문구가 사용자에게 보일 일은 없다.
            return .failure(reason: "녹음 취소됨")
        } catch {
            // 안드로이드의 `catch (e: SecurityException)` 자리. iOS는 권한 거부도 세션·엔진
            // 오류로 나와 CaptureError로 들어오므로, 여기 남는 건 소스 구현의 예상 밖 오류다.
            return .failure(reason: "녹음 실패 — \(error)")
        }

        if totalSamples == 0 { return .failure(reason: "캡처된 오디오가 없음") }

        let capped = min(totalSamples, Self.maxSamples)
        var pcm: [Int16] = []
        pcm.reserveCapacity(capped)
        for chunk in chunks {
            let room = capped - pcm.count
            if room <= 0 { break }
            pcm.append(contentsOf: room >= chunk.count ? chunk : Array(chunk[0..<room]))
        }
        return .success(
            pcm: pcm,
            durationMs: Int64(pcm.count) * 1000 / Int64(sampleRate),
            autoStopped: totalSamples >= Self.maxSamples
        )
    }
}

/// 안드로이드 `AtomicReference<AtomicBoolean?>` 자리. 락을 만지는 일을 **동기 메서드 안에**
/// 가둬 두는 이유는 `NSLock.lock()`이 async 함수 본문에서 직접 불리면 경고(Swift 6에서는 오류)이기
/// 때문이다 - 락을 쥔 채 await로 스레드를 넘길 수 있어서다. 여기 세 메서드는 await가 없다.
final class SessionSlot: @unchecked Sendable {
    private let lock = NSLock()
    private var value: StopFlag?

    func current() -> StopFlag? {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func install(_ flag: StopFlag) {
        lock.lock()
        value = flag
        lock.unlock()
    }

    func clear(ifCurrent flag: StopFlag) {
        lock.lock()
        if value === flag { value = nil }
        lock.unlock()
    }
}

/// 안드로이드 `AtomicBoolean` 자리. 정지 요청은 UI 스레드에서 오고 확인은 녹음 루프에서
/// 하므로 스레드를 넘나든다. 외부 의존성(swift-atomics) 없이 `NSLock` 하나면 충분하다 —
/// 32ms에 한 번 읽는 경합 없는 락이라 원자 연산과의 비용 차이가 의미 없는 자리다.
final class StopFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    var isSet: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func set() {
        lock.lock()
        value = true
        lock.unlock()
    }
}
