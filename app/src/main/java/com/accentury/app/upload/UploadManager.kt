package com.accentury.app.upload

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

    fun enqueue(request: UploadRequest) {
        if (!claim(request.attemptId) { it == null || it is UploadState.Failed }) return
        originals[request.attemptId] = request
        send(request)
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
            val state = when (val result = client.upload(sessionId, sessionToken, request)) {
                is UploadResult.Accepted -> UploadState.Done(result.analysisJobId)
                is UploadResult.Rejected -> UploadState.Failed(result.retryable, result.message)
                is UploadResult.TransportError -> UploadState.Failed(true, result.reason)
            }
            // 성공한 업로드의 WAV 바이트는 더 쓸 일이 없다. 실패분은 재시도용으로 남긴다.
            if (state is UploadState.Done) originals.remove(request.attemptId)
            _uploads.update { it + (request.attemptId to state) }
        }
    }
}
