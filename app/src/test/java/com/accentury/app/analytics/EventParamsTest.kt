package com.accentury.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventParamsTest {

    @Test
    fun `숫자는 숫자로 남는다`() {
        /*
         * 이 티켓의 AC가 여기 걸려 있다. 문자열로 뭉개면 GA4가 duration_ms를 차원으로 잡아
         * 평균·P95를 낼 수 없고, "대기 화면 체류 시간을 대시보드에서 바로 확인한다"가 깨진다.
         */
        assertEquals(
            mapOf(
                "duration_ms" to EventParam.Count(12_345L),
                "pending_item_count" to EventParam.Count(3L),
            ),
            parseEventParams("""{"duration_ms":12345,"pending_item_count":3}"""),
        )
    }

    @Test
    fun `정수와 실수를 구분한다`() {
        // 전부 실수로 보내면 개수가 대시보드에 3.0으로 찍힌다.
        assertEquals(
            mapOf("item_seq" to EventParam.Count(3L), "ratio" to EventParam.Amount(0.5)),
            parseEventParams("""{"item_seq":3,"ratio":0.5}"""),
        )
    }

    @Test
    fun `문자열 코드값은 그대로 실린다`() {
        assertEquals(
            mapOf("reason" to EventParam.Text("USER"), "tier_code" to EventParam.Text("A")),
            parseEventParams("""{"reason":"USER","tier_code":"A"}"""),
        )
    }

    @Test
    fun `null은 값 없이 지나간다`() {
        /*
         * 웹 스키마의 campaign은 null일 수 있다(공유 링크로 들어오지 않은 실행). 빈 문자열로
         * 넣으면 그 자리가 값 하나로 세어져 유입 없는 실행과 빈 캠페인이 한 축에 섞인다 —
         * 빠진 파라미터는 GA4가 (not set)으로 센다.
         */
        assertEquals(emptyMap<String, EventParam>(), parseEventParams("""{"campaign":null}"""))
    }

    @Test
    fun `JSON이 아니거나 객체가 아니면 이벤트째 버린다`() {
        // 실을 것이 무엇인지 알 수 없는 경우다. 값 하나가 아니라 payload 전체가 신뢰 밖이다.
        assertNull(parseEventParams(""))
        assertNull(parseEventParams("{oops"))
        assertNull(parseEventParams("[]"))
        assertNull(parseEventParams("42"))
    }

    @Test
    fun `실을 수 없는 값 하나 때문에 이벤트를 잃지는 않는다`() {
        /*
         * 파라미터 하나로 사건 자체를 버리면 퍼널 카운트가 줄어드는데, 그 손실은 대시보드에서
         * "일어나지 않은 일"과 구분되지 않는다. 중첩·배열은 평평한 Bundle에 실을 자리가 없고,
         * 규격 밖 이름은 GA4에 지울 수 없는 축을 만든다.
         */
        assertEquals(
            mapOf("item_seq" to EventParam.Count(1L)),
            parseEventParams(
                """{"item_seq":1,"nested":{"a":1},"list":[1,2],"Bad Name":"x","long_value":"${"z".repeat(101)}"}""",
            ),
        )
    }

    @Test
    fun `파라미터 상한을 넘긴 값은 앞에서부터 25개만 남는다`() {
        val many = (1..30).joinToString(",") { """"p_$it":$it""" }
        val params = parseEventParams("{$many}")

        assertEquals(25, params?.size)
        // 웹이 보낸 순서로 잘린다 — 무엇이 실렸는지가 재현 가능해야 한다.
        assertEquals(EventParam.Count(1L), params?.get("p_1"))
        assertNull(params?.get("p_26"))
    }

    @Test
    fun `우리 스키마의 이름은 전부 통과한다`() {
        // web/src/analytics/events.ts의 이름들. 규칙이 좁아져 실제 이벤트가 막히면 여기서 걸린다.
        listOf(
            "referral_opened",
            "item_shown",
            "recording_retake",
            "analysis_wait_duration",
            "tier_assigned",
            "duration_ms",
            "overall_bucket",
        ).forEach { assertTrue(it, isAnalyticsName(it)) }
    }

    @Test
    fun `규격 밖 이름은 거른다`() {
        assertFalse("빈 이름", isAnalyticsName(""))
        assertFalse("숫자로 시작", isAnalyticsName("1_event"))
        assertFalse("언더스코어로 시작", isAnalyticsName("_event"))
        assertFalse("하이픈", isAnalyticsName("item-shown"))
        assertFalse("공백", isAnalyticsName("item shown"))
        // 대문자는 스펙상 허용이지만 우리 스키마에는 없다. 통과시키면 Item_Shown이 item_shown과
        // 별개의 축으로 쌓여 두 지표가 조용히 갈린다.
        assertFalse("대문자", isAnalyticsName("Item_Shown"))
        assertFalse("40자 초과", isAnalyticsName("a".repeat(41)))
        assertTrue("40자", isAnalyticsName("a".repeat(40)))
        // SDK가 자기 몫으로 예약한 접두사 — 보내도 SDK 단에서 거부된다.
        assertFalse("예약 접두사", isAnalyticsName("firebase_start"))
        assertFalse("예약 접두사", isAnalyticsName("google_x"))
        assertFalse("예약 접두사", isAnalyticsName("ga_x"))
    }
}
