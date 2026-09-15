package com.accentury.app.analytics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * 브리지로 들어온 계측 이벤트를 GA4가 받는 모양으로 좁힌다 (KAN-33).
 *
 * 웹이 보내는 값은 신뢰 경계 밖이다 — `parseVoiceItemStart`·`parseSharePayload`와 같은 전제인데,
 * 여기서 걸러야 하는 것은 안전이 아니라 **집계 축의 위생**이다. 규격을 벗어난 이름이 그대로
 * 흘러가면 GA4에 지울 수 없는 축이 하나 생기고(이벤트·파라미터 정의는 사후 삭제가 안 된다),
 * 그 축은 사람이 다시 읽어야 하는 대시보드가 된다.
 */

/** 모르는 필드를 흘려보내는 것은 브리지의 다른 파서와 같은 규칙이다 (필드 추가는 하위호환, §5). */
private val json = Json { ignoreUnknownKeys = true }

/**
 * GA4 이름 규칙 — 영문 소문자로 시작하는 snake_case, 40자 이내.
 *
 * 스펙은 대문자도 허용하지만(대소문자를 구분한다) **소문자만 받는다.** 우리 스키마의 이름은 전부
 * 소문자라(`web/src/analytics/events.ts`), 대문자가 섞여 들어온다는 것은 오타이거나 다른 규약을
 * 쓰는 코드가 붙었다는 뜻이다. 그대로 통과시키면 `Item_Shown`이 `item_shown`과 별개의 축으로
 * 쌓여 두 지표가 조용히 갈린다.
 */
private val NAME_PATTERN = Regex("^[a-z][a-z0-9_]{0,39}$")

/** SDK가 자기 몫으로 예약한 접두사. 우리가 보내면 SDK 단에서 거부된다 */
private val RESERVED_PREFIXES = listOf("firebase_", "google_", "ga_")

/** 이벤트 하나에 실을 수 있는 파라미터 수 (GA4 상한) */
private const val MAX_PARAMS = 25

/**
 * 파라미터 값의 길이 상한 (GA4 상한). 넘으면 **자르지 않고 버린다** — 잘린 값은 원래 값과 다른
 * 집계 축이 되는데, 그 사실이 대시보드에는 드러나지 않는다. 우리 스키마의 값은 전부 짧은
 * 코드라(등급 코드·채널·사유) 100자를 넘는 값은 애초에 우리 것이 아니다.
 */
private const val MAX_VALUE_LENGTH = 100

/**
 * 이벤트명·파라미터명으로 쓸 수 있는 이름인가.
 *
 * 브리지가 이 판정에 걸린 이벤트를 조용히 버린다 — 웹은 오류를 돌려줄 상대가 아니고
 * (`parseVoiceItemStart`와 같은 규칙), 규격 밖 이름을 보내는 코드가 있다는 사실은 사람이 봐야
 * 하므로 Crashlytics 비치명 이벤트로 남긴다 (`CrashReports`).
 */
fun isAnalyticsName(name: String): Boolean =
    NAME_PATTERN.matches(name) && RESERVED_PREFIXES.none { name.startsWith(it) }

/**
 * 웹이 보낸 파라미터 JSON을 타입이 살아 있는 맵으로 옮긴다. 객체가 아니면 null이다.
 *
 * **JSON 원본의 타입을 그대로 살린다**는 것이 이 함수의 존재 이유다 ([EventParam] KDoc). 웹은
 * `duration_ms`·`item_seq`를 숫자로 보내는데, 문자열로 뭉개 넘기면 GA4에서 평균·P95를 낼 수 없다.
 *
 * 이벤트째 버리는 경우와 값 하나만 버리는 경우를 나눈다. JSON이 깨졌거나 객체가 아니면 실을 것이
 * 무엇인지 알 수 없어 이벤트를 버리지만, 값 하나가 규격 밖인 경우에는 **나머지를 실어 보낸다** —
 * 파라미터 하나 때문에 사건 자체를 잃으면 퍼널 카운트가 줄어들고, 그 손실은 대시보드에서
 * "일어나지 않은 일"과 구분되지 않는다.
 *
 * null은 값 없이 지나간다. 웹 스키마의 `campaign`이 null일 수 있는데(공유 링크로 들어오지 않은
 * 실행), Bundle에는 "값이 없다"를 담을 자리가 없고 GA4는 빠진 파라미터를 `(not set)`으로 센다 —
 * 빈 문자열을 넣으면 그 자리가 값 하나로 세어져 유입 없는 실행과 빈 캠페인이 섞인다.
 */
fun parseEventParams(paramsJson: String): Map<String, EventParam>? {
    val root = try {
        json.parseToJsonElement(paramsJson)
    } catch (_: Exception) {
        return null
    }
    if (root !is JsonObject) return null

    val params = LinkedHashMap<String, EventParam>()
    for ((key, element) in root) {
        // 상한을 넘은 뒤의 값은 어차피 SDK가 버린다. 여기서 멈추면 무엇이 실렸는지가 웹이 보낸
        // 순서로 결정돼 재현 가능하다.
        if (params.size >= MAX_PARAMS) break
        if (!isAnalyticsName(key)) continue
        if (element !is JsonPrimitive || element is JsonNull) continue
        params[key] = element.toEventParam() ?: continue
    }
    return params
}

/**
 * JSON 원시값 하나를 [EventParam]으로. 실을 수 없는 값이면 null이다.
 *
 * 정수와 실수를 나누는 판정은 값 자체로 한다 — 웹이 `10`을 보내면 GA4에도 10으로 찍혀야 하고,
 * `10.5`는 실수여야 한다. 불리언은 지금 스키마에 없지만 문자열로 옮겨 둔다: 나중에 하나 생겼을
 * 때 조용히 사라지는 것보다 `true`/`false`라는 두 값의 축으로 보이는 편이 낫다.
 */
private fun JsonPrimitive.toEventParam(): EventParam? {
    if (isString) return if (content.length > MAX_VALUE_LENGTH) null else EventParam.Text(content)
    booleanOrNull?.let { return EventParam.Text(it.toString()) }
    longOrNull?.let { return EventParam.Count(it) }
    doubleOrNull?.let { return EventParam.Amount(it) }
    return null
}
