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

/**
 * 업로드 한 건의 수명(등록 - 전송 - 실패 - 재시도 - 폐기)을 쥐는 자리.
 *
 * 재시도에는 상한이 있다 (KAN-147, 2026-08-25 결정). 같은 멱등 키로 같은 바이트를 다시 보내는
 * 일이라 두 번을 넘겨 성공하는 경우가 거의 없고, 그런데도 [재시도] 버튼을 계속 남겨두면 사용자는
 * 눌러도 아무 일이 없는 버튼 앞에 갇힌다. 상한을 넘긴 실패의 복구 경로는 재전송이 아니라
 * 재녹음이고(호출자가 녹음 화면을 다시 연다), 그 전환의 신호가 `retryable = false`다.
 */
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
     * 시도별로 이미 쓴 재시도 횟수 (KAN-147). 상한 판정이 실패 상태를 만드는 자리에 있어야 해서
     * 별도로 센다 - [UploadState.Failed]에 필드를 더하면 이 값을 모르는 호출처가 상태를 만들 수 있다.
     * 접근은 전부 lock 아래.
     */
    private val retriesUsed = mutableMapOf<String, Int>()

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
            retriesUsed[snapshot.attemptId] = 0
            register(snapshot, retriesUsed = 0)
        }
        job.start()
    }

    /**
     * 실패한 전송을 같은 멱등 키와 같은 바이트로 다시 보낸다. 시도당 [MAX_RETRIES]번까지다 (KAN-147).
     *
     * 상한을 넘긴 실패는 애초에 `retryable = false`로 내려오므로 이 함수는 그대로 무시한다 -
     * 판정을 여기가 아니라 상태를 만드는 자리([register])에 둔 이유는 화면이 [재시도] 버튼을
     * 그릴지 말지를 같은 값 하나로 정하기 때문이다. 버튼은 보이는데 눌러도 무시되는 구간이 없다.
     */
    fun retry(attemptId: String) {
        val job = synchronized(lock) {
            val request = originals[attemptId] ?: return
            val state = _uploads.value[attemptId]
            if (state !is UploadState.Failed || !state.retryable) return
            val used = (retriesUsed[attemptId] ?: 0) + 1
            retriesUsed[attemptId] = used
            register(request, retriesUsed = used)
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
     * 새 attemptId를 발급할 것.
     *
     * 호출처는 둘 다 KAN-147에서 생겼다: 재시도 상한을 넘겨 포기한 업로드(그 문항은 녹음 화면이
     * 다시 열린다)와, 같은 문항의 새 녹음이 등록되면서 밀려난 앞 시도다. 둘 다 결과가 나올 일이
     * 없어진 시도라 바이트를 들고 있을 이유가 없다.
     */
    fun discard(attemptId: String) {
        synchronized(lock) {
            // jobs에서 먼저 떼어낸 뒤 취소한다. 취소가 완료 핸들러를 같은 스레드에서 곧바로 부르더라도
            // (락은 재진입 가능) 이미 지워진 항목이라 새 시도의 Job을 건드리지 않는다.
            val job = jobs.remove(attemptId)
            originals.remove(attemptId)
            // 폐기는 시도 자체를 버리는 것이라 재시도 횟수도 함께 푼다 - 같은 키로 다시 enqueue하면
            // 그것은 다른 바이트를 보내는 새 시도이므로 앞 시도의 소진분을 물려받을 이유가 없다.
            retriesUsed.remove(attemptId)
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
            retriesUsed.clear()
            _uploads.value = emptyMap()
            running.forEach { it.cancel() }
        }
    }

    /**
     * InFlight 표식과 Job 등록을 한 번에 끝낸다. 반드시 [lock] 안에서 부르고,
     * 반환된 Job은 락을 놓은 뒤에 start한다(코루틴 본문이 호출 스레드에서 인라인으로 돌 수 있으므로).
     *
     * [retriesUsed]는 이 전송이 몇 번째 재시도인지다. 전송을 걸 때의 값을 그대로 붙잡아 두는 이유는
     * 결과가 돌아오는 시점에 다시 세면 그사이 폐기, 재등록으로 값이 바뀌어 있을 수 있어서다.
     */
    private fun register(request: UploadRequest, retriesUsed: Int): Job {
        val attemptId = request.attemptId
        _uploads.value = _uploads.value + (attemptId to UploadState.InFlight)
        // 상한을 다 쓴 뒤의 실패는 서버가 뭐라 하든 재시도 불가다 (KAN-147). 호출자는 이 값을 보고
        // [재시도] 대신 녹음 화면을 다시 연다.
        val retriable = retriesUsed < MAX_RETRIES
        // jobs에 등록하기 전에 본문이 돌면 폐기가 끊을 대상을 놓친다. LAZY로 띄워 등록을 마친 뒤 시작한다.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val state = try {
                when (val result = client.upload(sessionId, sessionToken, request)) {
                    is UploadResult.Accepted -> UploadState.Done(result.analysisJobId)
                    is UploadResult.Rejected ->
                        UploadState.Failed(result.retryable && retriable, result.message)
                    is UploadResult.TransportError -> UploadState.Failed(retriable, result.reason)
                }
            } catch (e: CancellationException) {
                // 취소는 실패가 아니다. 상태를 건드리지 않고 코루틴 취소를 그대로 전파한다.
                throw e
            } catch (e: Throwable) {
                // 클라이언트 구현이 예외를 흘리더라도 InFlight로 고착되지 않게 실패로 내린다.
                UploadState.Failed(retriable, e.message ?: e.javaClass.simpleName)
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

    companion object {
        /**
         * 한 시도가 쓸 수 있는 재시도 횟수 (KAN-147, 2026-08-25 결정).
         * 최초 전송까지 합쳐 세 번째 실패가 확정 실패다 - 그 뒤의 복구는 재녹음이다.
         */
        const val MAX_RETRIES = 2
    }
}
