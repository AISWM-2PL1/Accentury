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
 * @property guideF0 상단 레인에 그릴 정적 가이드 곡선 (KAN-102). 필드 추가는 하위호환이라
 *   계약 버전을 올리지 않았고(§5), 그래서 이 필드를 모르는 구버전 웹의 payload도 유효하다 —
 *   없으면 가이드 레인만 비운 채 녹음은 그대로 진행한다.
 */
@Serializable
data class VoiceItemStart(
    val itemId: String,
    val prompt: String,
    val itemNumber: Int,
    val totalItems: Int,
    val maxDurationMs: Long,
    val guideF0: GuideF0? = null,
)

/**
 * 정의의 `guideF0`를 그대로 실어 온 것 (KAN-10 §3.2 미러, 산출: KAN-17).
 *
 * 웹이 가공하지 않고 정의째로 건네는 이유: 곡선을 어떻게 그릴지는 전부 네이티브 사정이라,
 * 중간에서 요약하면 렌더링 규칙이 바뀔 때마다 브리지 계약도 같이 흔들린다. 정의 그대로면
 * 시드(목 픽스처)를 실데이터로 갈아끼워도 이 계약과 렌더링 코드는 손대지 않는다.
 *
 * @property unit semitone — 화자 음역 정규화 단위
 * @property frameIntervalMs 시간축 샘플링 간격. 아직 렌더링에 안 쓰지만(가이드는 레인
 *   전체 폭에 맞춰 그린다) 사용자 곡선과의 시간축 대응(후속 티켓)이 쓸 값이라 함께 받는다
 * @property values 정규화된 semitone 배열. 무성 구간은 null이다 — 0은 평균 음높이라는
 *   유효한 값이라 무성 표현이 될 수 없다 (2026-08-17 결정, `GuideCurve.kt`)
 * @property bandLow 허용 밴드 하한. 렌더링 범위 밖(채점 층위)이지만 정의 그대로 받는다
 * @property bandHigh 허용 밴드 상한. 유무 규칙은 bandLow와 같다
 */
@Serializable
data class GuideF0(
    val unit: String,
    val frameIntervalMs: Int,
    val values: List<Double?>,
    val bandLow: List<Double>? = null,
    val bandHigh: List<Double>? = null,
)

/**
 * 웹이 보낸 JSON을 [VoiceItemStart]로 좁힌다. 신뢰할 수 없으면 null이다.
 *
 * WebView에 로드된 페이지는 네이티브에서 보면 신뢰 경계 밖이다 — allowlist(§7)가 오리진을
 * 막아 주지만 payload 내용까지 보증하지는 않는다. 값 자체가 말이 되는지는 여기서 본다:
 * 빈 itemId는 결과를 어느 문항에 붙일지 알 수 없게 만들고, 0 이하의 순번·문항 수·녹음 길이는
 * 녹음 화면이 그릴 수 없는 상태다. 어느 쪽이든 화면을 띄우기 전에 끊는 편이 안전하다.
 *
 * guideF0는 따로 값 검증을 두지 않는다 — 그릴 수 없는 배열(빈 배열·전부 무성)은 렌더링 쪽
 * (`GuideCurve`)이 빈 곡선으로 소화하고, 곡선은 없어도 녹음은 성립하기 때문이다. 다만 타입이
 * 어긋난 guideF0(예: values에 문자열)는 다른 필드처럼 payload 전체를 무시하게 된다 — 같은
 * 빌드의 웹만 이 필드를 보내므로, 부분 복구보다 "불량 payload는 통째로 무시" 원칙의 일관성을 택했다.
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
