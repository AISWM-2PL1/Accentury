package com.accentury.app.upload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

class UploadManager(
    private val client: UploadClient,
    private val scope: CoroutineScope,
    private val sessionId: String,
    private val sessionToken: String,
) {

    /**
     * 상태(uploads)·원본 바이트(originals)·진행 중 코루틴(jobs)은 항상 함께 움직여야 한다.
     * 셋을 따로따로 원자적으로 다루면 "조건 확인 → 등록" 사이에 폐기가 끼어들어
     * 폐기 이후에 시작된 업로드가 originals에 바이트를 영구히 남긴다(FR-DP-02 위반).
     * 그래서 모든 변이 구간을 이 락 하나로 선형화한다.
     *
     * 락 안에서는 suspend·블로킹 호출을 하지 않는다. [Job.cancel]은 비블로킹이라 허용하고,
     * 코루틴 본문을 실제로 굴리는 [Job.start]는 락 밖에서 호출한다.
     */
    private val lock = Any()

    private val _uploads = MutableStateFlow<Map<String, UploadState>>(emptyMap())
    val uploads: StateFlow<Map<String, UploadState>> = _uploads.asStateFlow()

    // 재시도는 같은 멱등 키로 같은 바이트를 다시 보내야 하므로 원본을 들고 있는다. 접근은 전부 lock 아래.
    private val originals = mutableMapOf<String, UploadRequest>()

    // 폐기 시 진행 중 전송을 실제로 끊으려면 코루틴을 잡고 있어야 한다. 접근은 전부 lock 아래.
    private val jobs = mutableMapOf<String, Job>()

    /**
     * 처음 보는 멱등 키만 받는다. 이미 아는 키는 상태와 무관하게 무시해,
     * 하나의 멱등 키에 서로 다른 payload가 붙는 일을 원천 차단한다.
     * 실패한 업로드의 재전송 경로는 [retry] 하나뿐이다.
     */
    fun enqueue(request: UploadRequest) {
        val job = synchronized(lock) {
            if (_uploads.value.containsKey(request.attemptId)) return
            // 호출자가 나중에 배열을 바꿔도 재시도 바이트가 흔들리지 않도록 스냅샷을 뜬다.
            val snapshot = request.copy(wavBytes = request.wavBytes.copyOf())
            originals[snapshot.attemptId] = snapshot
            register(snapshot)
        }
        job.start()
    }

    fun retry(attemptId: String) {
        val job = synchronized(lock) {
            val request = originals[attemptId] ?: return
            val state = _uploads.value[attemptId]
            if (state !is UploadState.Failed || !state.retryable) return
            register(request)
        }
        job.start()
    }

    /**
     * 이 시도의 음성 바이트를 확정적으로 폐기한다 (FR-DP-02).
     * 진행 중이면 전송을 끊고(UploadClient의 invokeOnCancellation 경로로 소켓까지),
     * 원본 바이트와 상태 항목을 함께 지운다.
     *
     * 폐기 후 같은 attemptId로 [enqueue]하면 새 시도로 다시 받는다.
     * 폐기는 시도 자체를 버리는 것이므로 멱등 키를 다시 열어주는 게 맞다.
     *
     * ⚠️ 단, 폐기 직전 전송이 이미 서버에 도달했을 수 있다. 그 경우 같은 키의 재요청에는
     * 서버 멱등 규칙(명세서 §5.2)이 기존 작업을 반환하므로, **폐기된 키를 새 녹음(다른
     * 바이트)에 재사용하면 새 녹음이 옛 analysisJobId에 조용히 묶인다.** 새 시도는 항상
     * 새 attemptId를 발급할 것. (호출처는 KAN-100 브리지에서 생긴다 — 현재 하네스는 clearAll만 사용)
     */
    fun discard(attemptId: String) {
        synchronized(lock) {
            // jobs에서 먼저 떼어낸 뒤 취소한다. 취소가 완료 핸들러를 같은 스레드에서 곧바로 부르더라도
            // (락은 재진입 가능) 이미 지워진 항목이라 새 시도의 Job을 건드리지 않는다.
            val job = jobs.remove(attemptId)
            originals.remove(attemptId)
            _uploads.value = _uploads.value - attemptId
            job?.cancel()
        }
    }

    /** 뷰모델 정리(onCleared)에서 남아 있는 음성 바이트를 전부 폐기한다 (FR-DP-02). [discard]와 같은 정리를 전 키에 적용한다. */
    fun clearAll() {
        synchronized(lock) {
            val running = jobs.values.toList()
            jobs.clear()
            originals.clear()
            _uploads.value = emptyMap()
            running.forEach { it.cancel() }
        }
    }

    /**
     * InFlight 표식과 Job 등록을 한 번에 끝낸다. 반드시 [lock] 안에서 부르고,
     * 반환된 Job은 락을 놓은 뒤에 start한다(코루틴 본문이 호출 스레드에서 인라인으로 돌 수 있으므로).
     */
    private fun register(request: UploadRequest): Job {
        val attemptId = request.attemptId
        _uploads.value = _uploads.value + (attemptId to UploadState.InFlight)
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
        // 자기 항목만 치운다. 이미 폐기·교체된 뒤라면 남의 Job을 지우지 않는다.
        job.invokeOnCompletion {
            synchronized(lock) { if (jobs[attemptId] === job) jobs.remove(attemptId) }
        }
        return job
    }

    /**
     * jobs에 등록된 Job이 여전히 이 코루틴일 때만 결과를 반영한다.
     * [discard]/[clearAll]은 항목을 지우고 [enqueue]/[retry]는 새 Job으로 덮으므로,
     * 폐기되거나 교체된 시도의 뒤늦은 결과는 여기서 버려진다.
     * 반영과 원본 정리가 같은 락 구간 안에 있어, 늦은 Done이 새 시도의 원본을 지우는 일도 없다.
     */
    private fun publish(attemptId: String, job: Job, state: UploadState) {
        synchronized(lock) {
            if (jobs[attemptId] !== job) return
            _uploads.value = _uploads.value + (attemptId to state)
            // 성공한 업로드의 WAV 바이트는 더 쓸 일이 없다. 실패분은 재시도용으로 남긴다.
            if (state is UploadState.Done) originals.remove(attemptId)
        }
    }
}
