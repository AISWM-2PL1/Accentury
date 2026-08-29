import AccenturyCore
import Foundation
import XCTest

/// 조건이 참이 될 때까지 짧게 양보하며 기다린다. `AccenturyCoreTests/Support/AsyncWait.swift`의
/// 같은 함수를 앱 타깃 테스트에도 둔 것이다 — 두 타깃이 소스를 나눠 갖지 않는다(패키지 테스트
/// 타깃의 헬퍼는 앱 테스트 번들에서 보이지 않는다).
///
/// 실패 판정이 시간이 아니라 **조건**이라 느린 기기에서도 통과 여부가 바뀌지 않고,
/// 상한은 영영 멈추는 것을 막는 안전선이다.
@discardableResult
func waitUntil(
    _ message: @autoclosure () -> String = "조건이 만족되지 않았다",
    timeout: TimeInterval = 5,
    file: StaticString = #filePath,
    line: UInt = #line,
    _ condition: () async -> Bool
) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if await condition() { return true }
        try? await Task.sleep(nanoseconds: 1_000_000) // 1ms
    }
    XCTFail(message(), file: file, line: line)
    return false
}

/// 마이크 자리에 끼우는 가짜 소스. 끝없이 같은 청크를 흘리고, **캡처가 실제로 닫혔는지**를
/// 기록한다 — "화면을 떠나면 마이크를 놓는가"를 단언하는 것이 이 타입의 존재 이유다.
///
/// 반납은 취소든 정상 종료든 스트림의 정리 구간에서 일어난다. 실제 캡처(`AudioRecorder`)가
/// `AVAudioEngine`을 멈추고 오디오 세션을 내리는 자리와 같다.
final class SpyPcmSource: PcmSource, @unchecked Sendable {

    private let lock = NSLock()
    private var opened = false
    private var released = false

    /// 캡처가 한 번이라도 열렸는가.
    var isOpened: Bool {
        lock.lock()
        defer { lock.unlock() }
        return opened
    }

    /// 캡처가 닫혔는가 (= 마이크를 놓았는가).
    var isReleased: Bool {
        lock.lock()
        defer { lock.unlock() }
        return released
    }

    /// 안드로이드 테스트가 쓰던 ±1000 교대 파형. 유성 판정이 나오도록 진폭이 충분하다.
    static func tone(count: Int = 2_048) -> [Int16] {
        (0..<count).map { $0 % 2 == 0 ? 1_000 : -1_000 }
    }

    /*
     * `unfolding:` 대신 continuation 형태를 쓰는 이유가 `onTermination` 하나다.
     *
     * 화면이 떠날 때 마이크가 닫히는 경로는 둘인데(정지 요청으로 엔진이 루프를 빠져나오는 것과
     * Task 취소), `unfolding:`은 앞쪽에서 아무 신호도 주지 않는다 — 소비가 멈추면 생산 클로저를
     * 그냥 더 안 부를 뿐이다. `onTermination`은 이터레이터가 해제되는 두 경우 모두에 불려서,
     * `PcmSource` 주석이 말하는 "소비 측이 멈추면 상류가 정리된다"를 실제로 관측할 수 있다.
     */
    func recordingStream() -> AsyncThrowingStream<[Int16], Error> {
        let chunk = Self.tone()
        return AsyncThrowingStream { continuation in
            mark { opened = true }
            let pump = Task {
                while !Task.isCancelled {
                    // 실제 마이크처럼 청크 사이에 간격을 둔다 — 즉시 흘리면 10초 상한이 몇 ms
                    // 만에 차서 "녹음 중에 떠난다"를 재현할 수 없다.
                    try? await Task.sleep(nanoseconds: 5_000_000)
                    if Task.isCancelled { return }
                    continuation.yield(chunk)
                }
            }
            continuation.onTermination = { [self] _ in
                pump.cancel()
                mark { released = true }
            }
        }
    }

    private func mark(_ mutate: () -> Void) {
        lock.lock()
        mutate()
        lock.unlock()
    }
}

/// 서버 없이 업로드 상태 흐름만 보는 가짜 클라이언트. 응답을 손으로 놓아 주므로
/// InFlight → Done/Failed 전이를 테스트가 원하는 순간에 만든다.
actor FakeUploadClient: UploadClient {

    private var pending: [CheckedContinuation<UploadResult, Never>] = []
    private var queued: [UploadResult] = []
    private(set) var requests: [UploadRequest] = []

    func upload(_ request: UploadRequest, sessionId: String, sessionToken: String) async -> UploadResult {
        requests.append(request)
        if !queued.isEmpty { return queued.removeFirst() }
        return await withCheckedContinuation { continuation in
            pending.append(continuation)
        }
    }

    /// 아직 답을 못 받고 서 있는 요청 수 — 테스트가 "지금 InFlight"를 확인하는 자리다.
    var inFlightCount: Int { pending.count }

    var requestCount: Int { requests.count }

    /// 서 있는 요청 하나에 답한다. 없으면 다음 요청이 곧바로 이 답을 받는다.
    func respond(_ result: UploadResult) {
        if pending.isEmpty {
            queued.append(result)
        } else {
            pending.removeFirst().resume(returning: result)
        }
    }
}
