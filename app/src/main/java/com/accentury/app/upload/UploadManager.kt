package com.accentury.app.upload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    /** 조건 확인과 InFlight 전환을 CAS로 묶어, 두 호출이 같은 키를 동시에 집어가지 못하게 한다. */
    private fun claim(attemptId: String, allowed: (UploadState?) -> Boolean): Boolean {
        while (true) {
            val current = _uploads.value
            if (!allowed(current[attemptId])) return false
            if (_uploads.compareAndSet(current, current + (attemptId to UploadState.InFlight))) return true
        }
    }

    private fun send(request: UploadRequest) {
        scope.launch {
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
            // 성공한 업로드의 WAV 바이트는 더 쓸 일이 없다. 실패분은 재시도용으로 남긴다.
            if (state is UploadState.Done) originals.remove(request.attemptId)
            _uploads.update { it + (request.attemptId to state) }
        }
    }
}
