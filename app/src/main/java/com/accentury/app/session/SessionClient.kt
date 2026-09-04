package com.accentury.app.session

import com.accentury.app.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

// 서버 계약(API 명세서 §3.1 / KAN-9)에 묶인 값들. 계약이 바뀌면 여기만 고친다.
private const val PATH_SESSIONS = "v0/sessions"
private const val PLATFORM_ANDROID = "ANDROID"
private const val JSON_MEDIA_TYPE = "application/json"
private const val HEADER_AUTHORIZATION = "Authorization"
private const val HEADER_CORRELATION_ID = "X-Correlation-Id"
private const val HEADER_RETRY_AFTER = "Retry-After"
private const val BEARER_PREFIX = "Bearer "

private const val STATUS_REQUEST_TIMEOUT = 408
private const val STATUS_TOO_MANY_REQUESTS = 429

/**
 * 세션 생성 한 건의 절대 상한.
 *
 * 업로드(60초)보다 훨씬 짧게 잡는다. 올릴 본문이 없어 저속망에서도 오래 걸릴 이유가 없고,
 * 그동안 사용자는 [시작하기]를 누른 채 아무것도 없는 준비 화면을 보고 있다 — 여기서 기다리는
 * 시간은 "진입 → 결과 3분" 예산에서 통째로 빠지는 시간이다. 넘기면 IOException으로 끊어
 * 실패 화면의 [다시 시도]가 받게 한다.
 */
private const val CREATE_CALL_TIMEOUT_SEC = 15L

private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(CREATE_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
    .build()

/** 세션 생성 한 번의 결과. 판정(무엇을 보여줄지)은 [SessionGateController]가 한다. */
sealed interface SessionResult {

    data class Created(val session: Session) : SessionResult

    /**
     * 서버가 응답은 했지만 세션을 주지 않았다. 필드는 공통 오류 봉투(§2.4) 그대로다.
     *
     * @property retryAfterMs 429가 알려주는 대기 시간 (§2.5). 그 외에는 null이다
     */
    data class Rejected(
        val code: String?,
        val message: String?,
        val retryable: Boolean,
        val retryAfterMs: Long?,
    ) : SessionResult

    /** 응답이 아예 오지 않은 전송 실패. 의미상 항상 재시도 가능. */
    data class TransportError(val reason: String) : SessionResult
}

/**
 * `POST /v0/sessions` 클라이언트 (KAN-34 결선, KAN-9 계약).
 *
 * [previousToken]이 이 인터페이스에 있는 이유: 재응시도 같은 호출이다 (KAN-107, §3.1). 이전 세션의
 * 토큰을 함께 보내면 서버가 그 세션과 결과를 즉시 폐기하고 새 세션을 발급한다. 최초 응시와
 * 재응시가 다른 메서드로 갈리면 헤더 하나 차이인 두 경로가 따로 늙으므로 파라미터로 둔다.
 * 호출부는 아직 최초 응시뿐이다 — 재응시 결선은 KAN-34 2단계다.
 */
interface SessionClient {
    /**
     * @param appVersion 익명 집계용 앱 버전 (서버 상한 32자)
     * @param previousToken 재응시일 때 폐기할 이전 세션의 토큰. 최초 응시는 null
     * @param campaignToken App Link로 들어온 공유 유입 계측 코드 (KAN-32). 링크 진입이 아니면 null
     */
    suspend fun create(
        appVersion: String,
        previousToken: String? = null,
        campaignToken: String? = null,
    ): SessionResult
}

class OkHttpSessionClient(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) : SessionClient {

    private val baseUrl: HttpUrl = baseUrl.toHttpUrl()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun create(
        appVersion: String,
        previousToken: String?,
        campaignToken: String?,
    ): SessionResult = try {
        client.await(buildRequest(appVersion, previousToken, campaignToken)).use { response ->
            val body = withContext(Dispatchers.IO) { response.body.string() }
            toResult(response.code, body, response.header(HEADER_RETRY_AFTER))
        }
    } catch (e: IOException) {
        SessionResult.TransportError(e.message ?: e.javaClass.simpleName)
    }

    private fun buildRequest(appVersion: String, previousToken: String?, campaignToken: String?): Request {
        val url = baseUrl.newBuilder().addPathSegments(PATH_SESSIONS).build()
        /*
         * 바디 전체가 선택이지만(§3.1) client는 채워 보낸다 — 익명 집계가 플랫폼별 응시·완주를
         * 가르는 유일한 입력이다.
         *
         * campaignToken은 App Link가 준 링크 진입에만 실린다 (KAN-32). 앱이 세션을 직접 만들므로
         * (KAN-34) 진입 URL의 `?c=`만으로는 서버 세션에 유입 경로가 남지 않는다 — 웹이 세션을 만들 때
         * 하던 일을 이 자리가 대신한다. 링크 진입이 아니면 키 자체를 빼고 보낸다: kotlinx 기본이
         * encodeDefaults=false라 null 필드는 직렬화되지 않고(웹 webSession.ts도 같은 방식이다),
         * 서버 `@Pattern`은 없는 필드는 보지만 `null`로 온 값에는 걸릴 수 있다.
         */
        val payload = json.encodeToString(
            CreateSessionBody.serializer(),
            CreateSessionBody(
                campaignToken = campaignToken,
                client = ClientBody(platform = PLATFORM_ANDROID, appVersion = appVersion),
            ),
        )
        val builder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .header(HEADER_CORRELATION_ID, UUID.randomUUID().toString())
        /*
         * 재응시라면 이전 토큰을 실어 이전 세션과 결과를 즉시 폐기시킨다 (KAN-107).
         *
         * 만료됐거나 서버가 모르는 토큰은 조용히 무시되고 응답이 최초 응시와 구분되지 않는다 —
         * 401도 404도 오지 않는다. 그래서 여기서 토큰의 생사를 미리 따지지 않는다: 따져 봐야
         * 알 수 없고, 알아도 할 일이 같다(새 세션을 받는다).
         */
        if (previousToken != null) {
            builder.header(HEADER_AUTHORIZATION, BEARER_PREFIX + previousToken)
        }
        return builder.build()
    }

    private fun toResult(status: Int, body: String, retryAfterHeader: String?): SessionResult {
        if (status in 200..299) {
            // 계약상 201이지만 다른 2xx도 5필드가 온전하면 받아들인다.
            val created = runCatching { json.decodeFromString(CreatedBody.serializer(), body) }
                .getOrNull()
                ?.takeIf { it.sessionId.isNotBlank() && it.sessionToken.isNotBlank() && it.testVersion.isNotBlank() }
            return if (created != null) {
                SessionResult.Created(
                    Session(
                        sessionId = created.sessionId,
                        sessionToken = created.sessionToken,
                        testVersion = created.testVersion,
                        scoreVersion = created.scoreVersion,
                        expiresAt = created.expiresAt,
                    ),
                )
            } else {
                // 서버에는 세션이 생겼는데 우리는 쓸 수 없다. 다시 부르면 새 세션이 생기므로
                // 재시도 가능이다 — 버려진 세션은 아무 데이터도 달리지 않은 채 30분 뒤 만료된다.
                SessionResult.Rejected(
                    code = null,
                    message = "성공 응답($status) 본문에서 세션 값을 읽지 못함",
                    retryable = true,
                    retryAfterMs = null,
                )
            }
        }
        val envelope = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), body) }.getOrNull()
        return SessionResult.Rejected(
            code = envelope?.code,
            message = envelope?.message ?: "오류 봉투 없는 응답($status)",
            // 봉투가 없으면 재시도 여부를 서버가 알려주지 않으므로 상태 코드로 판단한다.
            retryable = envelope?.retryable ?: isRetryableStatus(status),
            // 서버는 429에 봉투의 retryAfterMs와 Retry-After 헤더(초)를 함께 보낸다
            // (GlobalExceptionHandler). 봉투를 못 읽는 응답에서도 대기 시간 안내를 살리려고
            // 헤더를 예비로 읽는다 — 헤더가 HTTP-date 꼴이면 숫자로 읽히지 않아 null이 된다.
            retryAfterMs = envelope?.retryAfterMs ?: retryAfterHeader?.toLongOrNull()?.times(1_000),
        )
    }

    private fun isRetryableStatus(status: Int): Boolean =
        status >= 500 || status == STATUS_REQUEST_TIMEOUT || status == STATUS_TOO_MANY_REQUESTS
}

/** 요청 바디 (§3.1). 모든 필드가 선택이라 서버는 바디 자체가 없어도 세션을 만든다. */
@Serializable
private data class CreateSessionBody(val campaignToken: String? = null, val client: ClientBody)

@Serializable
private data class ClientBody(val platform: String, val appVersion: String)

/** 201 응답 5필드 (§3.1). 하나라도 빠지면 파싱이 실패해 재시도 가능한 거절이 된다. */
@Serializable
private data class CreatedBody(
    val sessionId: String,
    val sessionToken: String,
    val testVersion: String,
    val scoreVersion: String,
    val expiresAt: String,
)

@Serializable
private data class ErrorEnvelope(
    val code: String? = null,
    val message: String? = null,
    val retryable: Boolean,
    val retryAfterMs: Long? = null,
    val correlationId: String? = null,
)
