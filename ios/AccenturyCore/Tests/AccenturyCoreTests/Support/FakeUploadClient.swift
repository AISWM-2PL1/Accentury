import Foundation
@testable import AccenturyCore

/// 테스트가 응답 시점을 정하는 업로드 클라이언트. 안드로이드 `UploadManagerTest.FakeUploadClient` 자리다.
///
/// 코틀린은 `CompletableDeferred`를 attemptId로 묶어 뒀지만 여기서는 **호출 하나마다** 번호를
/// 매겨 대기시킨다. 같은 attemptId로 옛 전송과 새 전송이 동시에 살아 있는 경합 테스트(KAN-89)에서
/// 취소 통지가 엉뚱한 쪽을 깨우지 않게 하려면 그 구분이 필요하다.
actor FakeUploadClient: UploadClient {

    private struct Waiter {
        let attemptId: String
        let continuation: CheckedContinuation<UploadResult, Never>
    }

    private(set) var received: [UploadRequest] = []

    private var waiters: [Int: Waiter] = [:]
    private var cancelledCalls: Set<Int> = []
    private var swallowCancellation: Set<String> = []
    private var nextCallId = 0

    func upload(_ request: UploadRequest, sessionId: String, sessionToken: String) async -> UploadResult {
        nextCallId += 1
        let callId = nextCallId
        received.append(request)
        return await withTaskCancellationHandler {
            await park(callId: callId, attemptId: request.attemptId)
        } onCancel: {
            Task { await self.cancelCall(callId) }
        }
    }

    /// 이 키의 전송은 취소를 삼키고 성공 결과를 들고 돌아온다. 늦은 완료 경합을 결정론적으로 만든다.
    func swallowCancellationFor(_ attemptId: String) {
        swallowCancellation.insert(attemptId)
    }

    /// 테스트가 응답 시점을 정한다. 같은 키의 전송이 여럿 살아 있으면 **가장 최근** 것을 깨운다.
    func respond(_ attemptId: String, _ result: UploadResult) {
        guard let callId = waiters.filter({ $0.value.attemptId == attemptId }).keys.max() else { return }
        waiters.removeValue(forKey: callId)?.continuation.resume(returning: result)
    }

    func callsFor(_ attemptId: String) -> Int {
        received.filter { $0.attemptId == attemptId }.count
    }

    var receivedCount: Int { received.count }

    var lastReceived: UploadRequest? { received.last }

    /// 이 키로 최소 몇 번 호출이 도착했는지 기다린다 — 코틀린의 `advanceUntilIdle()` 자리다.
    func hasCalls(_ attemptId: String, atLeast count: Int) -> Bool {
        callsFor(attemptId) >= count
    }

    /// 테스트가 끝난 뒤 남은 대기를 정리한다. 깨우지 않은 continuation은 런타임 경고를 남긴다.
    func drain() {
        let pending = waiters
        waiters.removeAll()
        for waiter in pending.values {
            waiter.continuation.resume(returning: .transportError(failure: .unknown, reason: "drained"))
        }
    }

    private func park(callId: Int, attemptId: String) async -> UploadResult {
        if cancelledCalls.remove(callId) != nil { return cancelResult(for: attemptId) }
        return await withCheckedContinuation { continuation in
            waiters[callId] = Waiter(attemptId: attemptId, continuation: continuation)
        }
    }

    private func cancelCall(_ callId: Int) {
        guard let waiter = waiters.removeValue(forKey: callId) else {
            // 취소가 park보다 먼저 도착했다. 그 호출이 자리를 잡는 순간 바로 돌려보낸다.
            cancelledCalls.insert(callId)
            return
        }
        waiter.continuation.resume(returning: cancelResult(for: waiter.attemptId))
    }

    /// 취소를 삼키는 키는 성공을 들고 돌아온다(좀비 완료). 그 외에는 전송 실패로 끝난다 —
    /// 어느 쪽이든 폐기된 시도의 결과는 매니저가 세대 번호로 버려야 한다.
    private func cancelResult(for attemptId: String) -> UploadResult {
        swallowCancellation.contains(attemptId)
            ? .accepted(analysisJobId: "aj_zombie")
            : .transportError(failure: .unknown, reason: "cancelled")
    }
}

/// 응답을 기다리지 않는 클라이언트. 경합 테스트에서 여러 Task가 동시에 쓰므로 가변 상태를 두지 않는다.
struct ImmediateUploadClient: UploadClient {
    func upload(_ request: UploadRequest, sessionId: String, sessionToken: String) async -> UploadResult {
        .accepted(analysisJobId: "aj-\(request.attemptId)")
    }
}
