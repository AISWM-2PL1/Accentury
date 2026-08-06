package com.accentury.app.upload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class UploadManager(
    private val client: UploadClient,
    private val scope: CoroutineScope,
    private val sessionId: String,
    private val sessionToken: String,
) {

    private val _uploads = MutableStateFlow<Map<String, UploadState>>(emptyMap())
    val uploads: StateFlow<Map<String, UploadState>> = _uploads.asStateFlow()

    // 재시도는 같은 멱등 키로 같은 바이트를 다시 보내야 하므로 원본을 들고 있는다.
    private val originals = ConcurrentHashMap<String, UploadRequest>()

    // 폐기 시 진행 중 전송을 실제로 끊으려면 코루틴을 잡고 있어야 한다. 완료 시 자기 항목을 지운다.
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * 처음 보는 멱등 키만 받는다. 이미 아는 키는 상태와 무관하게 무시해,
     * 하나의 멱등 키에 서로 다른 payload가 붙는 일을 원천 차단한다.
     * 실패한 업로드의 재전송 경로는 [retry] 하나뿐이다.
     */
    fun enqueue(request: UploadRequest) {
        if (!claim(request.attemptId) { it == null }) return
        // 호출자가 나중에 배열을 바꿔도 재시도 바이트가 흔들리지 않도록 스냅샷을 뜬다.
        val snapshot = request.copy(wavBytes = request.wavBytes.copyOf())
        originals[snapshot.attemptId] = snapshot
        send(snapshot)
    }

    fun retry(attemptId: String) {
        val request = originals[attemptId] ?: return
        if (!claim(attemptId) { it is UploadState.Failed && it.retryable }) return
        send(request)
    }

    /**
     * 이 시도의 음성 바이트를 확정적으로 폐기한다 (FR-DP-02).
     * 진행 중이면 전송을 끊고(UploadClient의 invokeOnCancellation 경로로 소켓까지),
     * 원본 바이트와 상태 항목을 함께 지운다.
     *
     * 폐기 후 같은 attemptId로 [enqueue]하면 새 시도로 다시 받는다.
     * 폐기는 시도 자체를 버리는 것이므로 멱등 키를 다시 열어주는 게 맞다.
     */
    fun discard(attemptId: String) {
        // jobs -> uploads 순서로 지운다. send는 두 흔적이 모두 남아 있을 때만 상태를 반영하므로(publish),
        // 먼저 지워진 쪽이 곧바로 게이트가 되어 폐기된 시도가 되살아나지 못한다.
        jobs.remove(attemptId)?.cancel()
        originals.remove(attemptId)
        _uploads.update { it - attemptId }
    }

    /** 테스트 종료·문항 이탈에서 남아 있는 음성 바이트를 전부 폐기한다 (FR-DP-02). [discard]와 같은 정리를 전 키에 적용한다. */
    fun clearAll() {
        jobs.keys.forEach { jobs.remove(it)?.cancel() }
        originals.clear()
        _uploads.update { emptyMap() }
    }

    /** 조건 확인과 InFlight 전환을 CAS로 묶어, 두 호출이 같은 키를 동시에 집어가지 못하게 한다. */
    private fun claim(attemptId: String, allowed: (UploadState?) -> Boolean): Boolean {
        while (true) {
            val current = _uploads.value
            if (!allowed(current[attemptId])) return false
            if (_uploads.compareAndSet(current, current + (attemptId to UploadState.InFlight))) return true
        }
    }

    private fun send(request: UploadRequest) {
        val attemptId = request.attemptId
        // jobs에 등록하기 전에 본문이 돌면 폐기가 끊을 대상을 놓친다. LAZY로 띄워 등록을 마친 뒤 시작한다.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val state = try {
                when (val result = client.upload(sessionId, sessionToken, request)) {
                    is UploadResult.Accepted -> UploadState.Done(result.analysisJobId)
                    is UploadResult.Rejected -> UploadState.Failed(result.retryable, result.message)
                    is UploadResult.TransportError -> UploadState.Failed(true, result.reason)
                }
            } catch (e: CancellationException) {
                // 취소는 실패가 아니다. 상태를 건드리지 않고 코루틴 취소를 그대로 전파한다.
                throw e
            } catch (e: Throwable) {
                // 클라이언트 구현이 예외를 흘리더라도 InFlight로 고착되지 않게 실패로 내린다.
                UploadState.Failed(true, e.message ?: e.javaClass.simpleName)
            }
            publish(attemptId, coroutineContext.job, state)
        }
        jobs[attemptId] = job
        job.invokeOnCompletion { jobs.remove(attemptId, job) }
        job.start()
    }

    /**
     * 폐기와 경합해도 상태가 되살아나지 않게, claim이 남긴 InFlight 표식과 jobs 항목이
     * 둘 다 이 시도의 것일 때만 결과를 반영한다. [discard]/[clearAll]은 두 흔적을 모두 지우므로
     * 폐기된 시도의 결과는 여기서 버려진다. InFlight 확인과 반영은 CAS로 묶여 원자적이다.
     */
    private fun publish(attemptId: String, job: Job, state: UploadState) {
        while (true) {
            val current = _uploads.value
            if (current[attemptId] != UploadState.InFlight || jobs[attemptId] !== job) return
            if (_uploads.compareAndSet(current, current + (attemptId to state))) {
                // 성공한 업로드의 WAV 바이트는 더 쓸 일이 없다. 실패분은 재시도용으로 남긴다.
                if (state is UploadState.Done) originals.remove(attemptId)
                return
            }
        }
    }
}
