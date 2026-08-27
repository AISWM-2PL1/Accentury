package com.accentury.app.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** [VoiceItemStart]와 같은 이유로 모르는 필드를 흘려보낸다 — 필드 추가는 하위호환이라(§5). */
private val json = Json { ignoreUnknownKeys = true }

/** 카드 문구 상한. 카카오 피드 템플릿 title 제한(200자) 그대로다 — 넘으면 어차피 잘려 나간다. */
private const val MAX_TEXT_LENGTH = 200

/**
 * 결과 공유 카드 자산 — 웹이 네이티브에 건네는 값 (KAN-30).
 *
 * 웹 쪽 정본은 `web/src/bridge/bridge.ts`의 `SharePayload`다. 카드를 무엇으로 채울지는 서버가
 * 정하고(등급별 이미지·문구) 웹은 그대로 나른다 — 네이티브는 카카오 SDK 호출과 폴백만 맡는다.
 *
 * **점수·세션 id는 없다** (KAN-30 요구). 수신자는 남의 결과를 열어 보는 게 아니라 자기 테스트를
 * 새로 응시하므로, 카드에 필요한 건 등급 문구와 캠페인 URL뿐이다. 필드를 늘리기 전에 그 값이
 * "받은 사람이 볼 것"인지 먼저 확인할 것.
 *
 * @property imageUrl 등급별 카드 이미지
 * @property text 카드 문구. 등급명은 있지만 점수는 없다
 * @property webTestUrl 캠페인 파라미터가 붙은 웹 테스트 URL — 카드 링크와 버튼이 같은 값을 쓴다
 */
@Serializable
data class SharePayload(
    val imageUrl: String,
    val text: String,
    val webTestUrl: String,
)

/**
 * 웹이 보낸 JSON을 [SharePayload]로 좁힌다. 신뢰할 수 없으면 null이다.
 *
 * WebView 페이지는 신뢰 경계 밖이라는 [parseVoiceItemStart]와 같은 전제인데, 여기 값들은
 * 한 걸음 더 나간다 — **앱 밖으로 나가는 값**이다. imageUrl·webTestUrl은 카카오 템플릿과
 * ACTION_SEND 인텐트에 그대로 실려 다른 앱과 남의 대화방에 도착한다. 그래서 스킴을 https로
 * 못박는다: `javascript:`·`intent:`·`file:` 같은 스킴이 공유 링크로 나가면 우리 앱이 받는 사람
 * 기기에서 임의 동작을 여는 통로가 되고, `http://`는 카카오가 이미지로 받지 않는 데다 우리가
 * 평문 링크를 퍼뜨릴 이유도 없다.
 *
 * 검증 실패는 조용히 null이다 — 문항 payload와 같은 규칙이다. 웹은 오류를 돌려줄 상대가 아니고,
 * 엉뚱한 링크가 실린 카드를 내보내는 것보다 아무 일도 안 하는 편이 안전하다.
 */
fun parseSharePayload(payloadJson: String): SharePayload? {
    val payload = try {
        json.decodeFromString(SharePayload.serializer(), payloadJson)
    } catch (_: Exception) {
        return null
    }

    if (payload.text.isBlank() || payload.text.length > MAX_TEXT_LENGTH) return null
    if (!isShareableHttpsUrl(payload.imageUrl)) return null
    if (!isShareableHttpsUrl(payload.webTestUrl)) return null

    return payload
}

/** 공유 카드에 실어도 되는 URL인가. 스킴 검사가 전부다 — 도메인 제한은 캠페인 URL이 바뀔 때마다 깨진다. */
private fun isShareableHttpsUrl(url: String): Boolean =
    url.isNotBlank() && url.startsWith("https://")
