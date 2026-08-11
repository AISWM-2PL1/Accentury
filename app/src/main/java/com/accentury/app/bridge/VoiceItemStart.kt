package com.accentury.app.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 웹이 모르는 필드를 붙여 보내도 파싱이 깨지지 않게 한다 — 필드 추가는 하위호환이라
 * 버전을 올리지 않으므로(§5), 신버전 웹 + 구버전 앱 조합이 정상적으로 존재한다.
 */
private val json = Json { ignoreUnknownKeys = true }

/**
 * VOICE 문항 진입 시 웹이 네이티브에 건네는 문항 컨텍스트 (KAN-100).
 *
 * 테스트 정의(KAN-10)를 받는 주체는 웹이다 — 네이티브는 정의를 통째로 알 필요가 없고
 * 녹음 화면을 그릴 만큼만 여기서 받는다. 그래서 이 타입은 정의의 부분집합이지 사본이 아니다.
 * 웹 쪽 정본은 `web/src/bridge/bridge.ts`의 `VoiceItemStart`다.
 *
 * @property itemNumber 진행 표기용 순번. 1부터 시작한다 (정의의 `seq`가 아니라 사람이 읽는 번호다)
 * @property maxDurationMs 최대 녹음 길이. VOICE 문항 정의가 들고 있는 값 그대로다
 */
@Serializable
data class VoiceItemStart(
    val itemId: String,
    val prompt: String,
    val itemNumber: Int,
    val totalItems: Int,
    val maxDurationMs: Long,
)

/**
 * 웹이 보낸 JSON을 [VoiceItemStart]로 좁힌다. 신뢰할 수 없으면 null이다.
 *
 * WebView에 로드된 페이지는 네이티브에서 보면 신뢰 경계 밖이다 — allowlist(§7)가 오리진을
 * 막아 주지만 payload 내용까지 보증하지는 않는다. 값 자체가 말이 되는지는 여기서 본다:
 * 빈 itemId는 결과를 어느 문항에 붙일지 알 수 없게 만들고, 0 이하의 순번·문항 수·녹음 길이는
 * 녹음 화면이 그릴 수 없는 상태다. 어느 쪽이든 화면을 띄우기 전에 끊는 편이 안전하다.
 */
fun parseVoiceItemStart(payloadJson: String): VoiceItemStart? {
    val start = try {
        json.decodeFromString(VoiceItemStart.serializer(), payloadJson)
    } catch (_: Exception) {
        return null
    }

    if (start.itemId.isBlank()) return null
    if (start.itemNumber < 1 || start.totalItems < 1) return null
    if (start.itemNumber > start.totalItems) return null
    if (start.maxDurationMs <= 0) return null

    return start
}
