package com.accentury.app.upload

import com.accentury.app.audio.ClientQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// 서버 계약(API 명세서 §3.3 / KAN-23)에 묶인 값들. 계약이 바뀌면 여기만 고친다.
private const val PATH_SESSIONS = "v0/sessions"
private const val PATH_VOICE_ITEMS = "voice-items"
private const val PATH_RECORDING = "recording"
private const val PART_AUDIO = "audio"
private const val PART_META = "meta"
private const val AUDIO_FILE_NAME = "recording.wav"
private const val AUDIO_MEDIA_TYPE = "audio/wav"
private const val META_MEDIA_TYPE = "application/json"
private const val HEADER_AUTHORIZATION = "Authorization"
private const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"
private const val HEADER_CORRELATION_ID = "X-Correlation-Id"
private const val BEARER_PREFIX = "Bearer "

private const val STATUS_REQUEST_TIMEOUT = 408
private const val STATUS_TOO_MANY_REQUESTS = 429

sealed interface UploadResult {

    data class Accepted(val analysisJobId: String) : UploadResult

    data class Rejected(
        val code: String?,
        val message: String?,
        val retryable: Boolean,
        val retryAfterMs: Long?,
    ) : UploadResult

    /** 응답이 아예 오지 않은 전송 실패. 의미상 항상 재시도 가능. */
    data class TransportError(val reason: String) : UploadResult
}

interface UploadClient {
    suspend fun upload(sessionId: String, sessionToken: String, request: UploadRequest): UploadResult
}

class OkHttpUploadClient(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) : UploadClient {

    private val baseUrl: HttpUrl = baseUrl.toHttpUrl()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun upload(
        sessionId: String,
        sessionToken: String,
        request: UploadRequest,
    ): UploadResult = try {
        client.await(buildRequest(sessionId, sessionToken, request)).use { response ->
            val body = withContext(Dispatchers.IO) { response.body.string() }
            toResult(response.code, body)
        }
    } catch (e: IOException) {
        UploadResult.TransportError(e.message ?: e.javaClass.simpleName)
    }

    private fun buildRequest( //어디로, 어떤 방법으로, 어떤 헤더를 가지고, 어떤 형식으로 보낼지 정해주는 일. 주문서 짜주는 느낌
        sessionId: String,
        sessionToken: String,
        request: UploadRequest,
    ): Request {
        val url = baseUrl.newBuilder()
            .addPathSegments(PATH_SESSIONS)
            .addPathSegment(sessionId)
            .addPathSegment(PATH_VOICE_ITEMS)
            .addPathSegment(request.itemId)
            .addPathSegment(PATH_RECORDING)
            .build()
        val meta = json.encodeToString(
            MetaPart.serializer(),
            MetaPart(durationMs = request.durationMs, clientQuality = request.clientQuality),
        )
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                PART_AUDIO,
                AUDIO_FILE_NAME,
                request.wavBytes.toRequestBody(AUDIO_MEDIA_TYPE.toMediaType()),
            )
            .addFormDataPart(PART_META, null, meta.toRequestBody(META_MEDIA_TYPE.toMediaType()))
            .build()
        return Request.Builder()
            .url(url)
            .post(body)
            .header(HEADER_AUTHORIZATION, BEARER_PREFIX + sessionToken)
            // 비용이 발생하는 POST라 중복 접수를 막는다 (§2.2). 재시도해도 같은 attemptId를 쓴다.
            .header(HEADER_IDEMPOTENCY_KEY, request.attemptId)
            .header(HEADER_CORRELATION_ID, UUID.randomUUID().toString())
            .build()
    }

    private fun toResult(status: Int, body: String): UploadResult {
        if (status in 200..299) {
            // 계약상 202지만 다른 2xx도 analysisJobId만 있으면 받아들인다.
            val jobId = runCatching { json.decodeFromString<AcceptedBody>(body).analysisJobId }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            return if (jobId != null) {
                UploadResult.Accepted(jobId)
            } else {
                // 업로드는 접수됐지만 폴링할 ID가 없다. idempotencyKey가 중복 접수를 막아주므로 재시도로 회수 가능.
                UploadResult.Rejected(
                    code = null,
                    message = "성공 응답($status) 본문에서 analysisJobId를 읽지 못함",
                    retryable = true,
                    retryAfterMs = null,
                )
            }
        }
        val envelope = runCatching { json.decodeFromString<ErrorEnvelope>(body) }.getOrNull()
        return UploadResult.Rejected(
            code = envelope?.code,
            message = envelope?.message ?: "오류 봉투 없는 응답($status)",
            // 봉투가 없으면 재시도 여부를 서버가 알려주지 않으므로 상태 코드로 판단한다.
            retryable = envelope?.retryable ?: isRetryableStatus(status),
            retryAfterMs = envelope?.retryAfterMs,
        )
    }

    private fun isRetryableStatus(status: Int): Boolean =
        status >= 500 || status == STATUS_REQUEST_TIMEOUT || status == STATUS_TOO_MANY_REQUESTS
}

/** multipart의 meta 파트 본문(§3.3). audio와 함께 둘 다 필수다. */
@Serializable
private data class MetaPart(val durationMs: Long, val clientQuality: ClientQuality)

@Serializable
private data class AcceptedBody(val analysisJobId: String)

@Serializable
private data class ErrorEnvelope(
    val code: String? = null,
    val message: String? = null,
    val retryable: Boolean,
    val retryAfterMs: Long? = null,
    val correlationId: String? = null,
)

// enqueue는 OkHttp 디스패처 스레드에서 돌기 때문에 호출자 스레드를 막지 않는다.
private suspend fun OkHttpClient.await(request: Request): Response =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() } // 코루틴 취소 -> http 호출을 끊어버림
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, _, _ -> response.close() }
                }
            },
        )
    }
