package com.accentury.app.upload

import com.accentury.app.net.TransportFailure
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
 * 재시도 횟수에 상한을 두지 않는다 (KAN-147, 2026-08-25 B안). [UploadResult.TransportError]는
 * 응답이 오지 않았다는 뜻이지 녹음에 문제가 있다는 뜻이 아니고(멱등 키 덕에 서버가 이미 받았을
 * 수도 있다), 잠깐 끊긴 사용자에게서 녹음을 빼앗는 것은 되돌릴 수 없는 손실이다. 그래서 전송
 * 실패에는 [재시도]를 계속 남긴다.
 *
 * 자동 재녹음으로 넘어가는 것은 서버가 녹음 자체를 거절했다고 답한 코드([RERECORD_CODES])뿐이다.
 * 그 신호가 [UploadState.Failed.rerecord]고, 그 외의 재시도 불가 거절(세션 만료·재녹음 횟수
 * 초과 등)은 서버가 준 문구를 단 실패 행으로 화면에 그대로 남는다 - 사용자가 읽어야 할 안내를
 * 자동 전환이 지워버리지 않게 한다.
 *
 * 여기서 이탈 수단을 주지 않는 것은 의도한 공백이다. 실패 화면에서 어디로 빠져나갈지는 KAN-39
 * 디자인이 정한다.
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

    /**
     * 실패한 전송을 같은 멱등 키와 같은 바이트로 다시 보낸다. 횟수 제한은 없다 (KAN-147, B안).
     *
     * 재시도 불가로 내려온 실패는 그대로 무시한다 - 판정을 여기가 아니라 상태를 만드는
     * 자리([register])에 둔 이유는 화면이 [재시도] 버튼을 그릴지 말지를 같은 값 하나로 정하기
     * 때문이다. 버튼은 보이는데 눌러도 무시되는 구간이 없다.
     */
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
     * 새 attemptId를 발급할 것.
     *
     * 호출처는 둘 다 KAN-147에서 생겼다: 재녹음 전환(rerecord)이 확정된 업로드(그 문항은 녹음
     * 화면이 다시 열린다)와, 같은 문항의 새 녹음이 등록되면서 밀려난(supersede) 앞 시도다.
     * 둘 다 결과가 나올 일이 없어진 시도라 바이트를 들고 있을 이유가 없다.
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
                    is UploadResult.Rejected -> {
                        // 녹음을 새로 해야 풀리는 거절이면 재전송 쪽은 닫는다 (KAN-147). 두 복구
                        // 경로를 함께 세우면 화면이 어느 쪽을 권하는지 말할 수 없다.
                        val rerecord = result.code in RERECORD_CODES
                        UploadState.Failed(
                            retryable = result.retryable && !rerecord,
                            message = result.message,
                            rerecord = rerecord,
                        )
                    }
                    // 응답이 오지 않은 것은 녹음의 문제가 아니다. 언제든 다시 보낼 수 있게 남긴다.
                    is UploadResult.TransportError ->
                        UploadState.Failed(retryable = true, message = result.failure.userMessage())
                }
            } catch (e: CancellationException) {
                // 취소는 실패가 아니다. 상태를 건드리지 않고 코루틴 취소를 그대로 전파한다.
                throw e
            } catch (e: Throwable) {
                // 클라이언트 구현이 예외를 흘리더라도 InFlight로 고착되지 않게 실패로 내린다.
                // 원인을 모르는 실패라 녹음을 버리게 하지 않고 재시도 쪽에 남긴다. 예외 문구는
                // 사용자가 읽을 말이 아니므로 삼키고, 원인 불명 전송 실패와 같은 안내를 쓴다.
                UploadState.Failed(retryable = true, message = TransportFailure.Unknown.userMessage())
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
         * 백엔드 ErrorCode 중 녹음을 새로 해야 풀리는 것들 (KAN-147, 2026-08-25 B안).
         * 같은 바이트를 다시 보내면 서버가 같은 답을 할 뿐이라 재전송이 아니라 재녹음이 복구 경로다.
         *
         * AUDIO_TOO_QUIET은 서버가 `retryable = true`로 주지만 여기서는 재녹음이 이긴다 -
         * 재전송해도 같은 바이트가 같은 판정을 받는다.
         *
         * AUDIO_FORMAT_UNSUPPORTED는 넣지 않는다. 포맷은 클라이언트가 만드는 것이라 사용자가
         * 다시 녹음해도 같은 포맷이 나간다 - 재녹음을 시켜도 벗어날 수 없는 클라이언트 버그다.
         */
        private val RERECORD_CODES = setOf("AUDIO_TOO_LONG", "AUDIO_TOO_LARGE", "AUDIO_TOO_QUIET")
    }
}

/**
 * 전송 실패를 사용자가 읽을 한 줄로 옮긴다 (KAN-147 2단계).
 *
 * OkHttp 예외 문구("timeout", "Unable to resolve host ...")를 그대로 상태 바에 태우면
 * 사용자는 자기가 끊긴 건지 서버가 죽은 건지 알 수 없다. 원인을 단정할 수 있는 만큼만 말하고,
 * 어느 쪽이든 복구 수단은 하나([재시도])라 문구는 전부 "다시 시도" 쪽으로 모은다.
 */
private fun TransportFailure.userMessage(): String = when (this) {
    TransportFailure.Offline -> "인터넷 연결을 확인해 주세요"
    TransportFailure.ServerUnreachable -> "서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요"
    TransportFailure.Timeout -> "응답이 늦어요. 다시 시도해 주세요"
    TransportFailure.Unknown -> "전송에 실패했어요. 다시 시도해 주세요"
}
