import Foundation
@testable import AccenturyCore

/// 여러 스레드에서 적히는 사건 기록. 캡처 열림/닫힘 순서를 단언하는 데 쓴다.
final class EventLog: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [String] = []

    func append(_ event: String) {
        lock.lock()
        events.append(event)
        lock.unlock()
    }

    var all: [String] {
        lock.lock()
        defer { lock.unlock() }
        return events
    }

    func contains(_ event: String) -> Bool { all.contains(event) }

    /// 지금까지 쌓인 것을 꺼내 가며 비운다. 회차마다 순서를 따로 읽는 경합 테스트가 쓴다.
    func drain() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        let events = self.events
        self.events.removeAll()
        return events
    }
}

/// 마이크 자리에 끼우는 가짜 소스. 안드로이드 테스트의 `FakeSource(flow { ... })` 자리다.
///
/// 세 가지를 흉내 낸다:
/// - **콜드**: `recordingStream()`을 부를 때마다 처음부터 다시 흐른다 (재녹음·재응시 경로).
/// - **정리 구간**: 취소되거나 다 흘렀을 때 `release{n}`을 남긴다 — 실제 캡처가 AVAudioEngine을
///   멈추고 세션을 내리는 자리이고, "화면을 떠나면 마이크를 놓는가"를 여기서 단언한다.
/// - **늦은 반납**: `releaseDelay`를 주면 취소가 돌아온 뒤에도 한동안 마이크를 쥐고 있다.
///   취소에 영향받지 않는 대기(`DispatchQueue.asyncAfter`)라 코틀린 테스트의
///   `withContext(NonCancellable) { delay(...) }`와 같은 모양이다.
final class FakePcmSource: PcmSource, @unchecked Sendable {

    let events: EventLog

    private let chunk: [Int16]
    /// nil이면 무한 소스다.
    private let limit: Int?
    private let intervalNanos: UInt64
    private let releaseDelay: TimeInterval
    private let failure: Error?
    private let lock = NSLock()
    private var opens = 0

    init(
        chunk: [Int16] = FakePcmSource.tone(),
        limit: Int? = nil,
        intervalNanos: UInt64 = 0,
        releaseDelay: TimeInterval = 0,
        failure: Error? = nil,
        events: EventLog = EventLog()
    ) {
        self.chunk = chunk
        self.limit = limit
        self.intervalNanos = intervalNanos
        self.releaseDelay = releaseDelay
        self.failure = failure
        self.events = events
    }

    /// 안드로이드 테스트가 쓰던 ±1000 교대 파형. 유성 판정이 나오도록 진폭이 충분하다.
    static func tone(amplitude: Int16 = 1000, count: Int = chunkSize) -> [Int16] {
        (0..<count).map { $0 % 2 == 0 ? amplitude : -amplitude }
    }

    func recordingStream() -> AsyncThrowingStream<[Int16], Error> {
        lock.lock()
        opens += 1
        let id = opens
        lock.unlock()

        var emitted = 0
        var opened = false
        return AsyncThrowingStream(unfolding: { [self] in
            if !opened {
                opened = true
                events.append("open\(id)")
            }
            if let failure {
                await self.release(id)
                throw failure
            }
            if intervalNanos > 0 {
                do {
                    try await Task.sleep(nanoseconds: intervalNanos)
                } catch {
                    // 취소가 여기로 온다. 실제 캡처의 정리 구간과 같은 자리라 반납을 남긴다.
                    await self.release(id)
                    throw error
                }
            }
            if let limit, emitted >= limit {
                await self.release(id)
                return nil
            }
            emitted += 1
            return chunk
        })
    }

    private func release(_ id: Int) async {
        if releaseDelay > 0 {
            // 취소와 무관하게 끝까지 수행된다 — 실제 오디오 세션 반납이 그렇다.
            await withCheckedContinuation { continuation in
                DispatchQueue.global().asyncAfter(deadline: .now() + releaseDelay) {
                    continuation.resume()
                }
            }
        }
        events.append("release\(id)")
    }
}
