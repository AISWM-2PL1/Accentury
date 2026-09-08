package com.accentury.app.bridge

import com.accentury.app.recording.USER_CURVE_WINDOW_SCALE
import com.accentury.app.recording.guideCurveDisplayPoints
import com.accentury.app.recording.userCurveWindowMs
import java.io.File
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 발행본 가이드 곡선 전수 검사 (KAN-194).
 *
 * 픽스처 한 문항(`GuideF0Fixture`)이 대표라면 이 파일은 표본이 아니라 전수다 — 정본 발행본
 * `gn-2026.09.1`의 음성 145문항을 하나씩 브리지 파싱과 곡선 계산에 태워, 실제로 사용자에게
 * 내려가는 데이터에서 곡선이 버려지는 문항(AC1)과 사용자 창이 폴백으로 주저앉는 문항(AC2)이
 * 0건임을 확인한다. 곡선 규칙 자체는 `GuideCurveTest`·`UserCurveTest`가 덮으므로, 여기서
 * 보는 것은 "그 규칙이 발행본 형태를 견디는가" 하나다.
 *
 * 입력은 문항 JSON을 그대로 쓰지 않고 **웹이 보내는 payload 문자열로 조립해** 브리지에
 * 넣는다. 앱이 실제로 받는 것은 정의가 아니라 payload라, 정의를 직접 읽어 검사하면
 * `parseVoiceItemStart`의 좁히기 단계를 건너뛰게 된다 — 곡선을 버리는 사고는 바로 그
 * 단계에서 일어난다.
 *
 * 입력 파일은 백엔드 마이그레이션 SQL을 직접 읽는다. 앱에 정의 사본을 떠 두면 발행본이
 * 바뀔 때 사본만 낡아 초록불이 거짓말을 하므로, 정본 파일을 그때그때 읽는 편이 맞다.
 *
 * **파일이 없으면 skip이 아니라 실패다.** 발행본이 이 검사의 정본이라, 못 읽었으면 검사가
 * 안 돌았다는 뜻이지 통과했다는 뜻이 아니다. 조용히 건너뛰면 마이그레이션 파일이
 * 사라지거나 이름이 바뀐 날 아무도 모르게 커버리지가 0이 된다.
 */
class PublishedGuideF0Test {

    /** 발행본 음성 문항 하나. 원문 JSON과, 그것으로 조립한 브리지 payload를 함께 들고 있다 */
    private data class VoiceItem(
        val itemId: String,
        val scriptKey: String,
        val guideF0: JsonObject,
        val payload: String,
    ) {
        /** 실패 메시지에서 어느 문항인지 바로 보이게 하는 꼬리표 */
        val tag: String get() = "$itemId($scriptKey)"
    }

    /**
     * 조건을 어긴 문항만 추린다. 개수만 세면 "몇 개 깨졌다"까지만 나오지만, 어긴 문항을
     * 모아 비교하면 실패 메시지에 itemId·scriptKey와 어긋난 값이 그대로 찍힌다.
     */
    private fun violations(inspect: (VoiceItem) -> String?): List<String> =
        voiceItems.mapNotNull { item -> inspect(item)?.let { "${item.tag}: $it" } }

    @Test
    fun `음성 문항이 145개다`() {
        assertEquals(145, voiceItems.size)
    }

    @Test
    fun `전 문항이 곡선을 버리지 않고 파싱된다 - 구제 경로를 타는 문항이 없다 (AC1)`() {
        // guideF0가 null이면 파싱이 실패해 곡선을 떼어낸 구제 경로(decodeStart)를 탔다는 뜻이다.
        // 녹음은 그대로 진행되지만 가이드 레인이 비므로, 발행본에서는 0건이어야 한다.
        assertEquals(
            emptyList<String>(),
            violations { item ->
                val start = parseVoiceItemStart(item.payload)
                val guide = start?.guideF0
                when {
                    start == null -> "payload 자체가 거부됐다"
                    guide == null -> "guideF0가 버려졌다 (구제 경로)"
                    guide.frameIntervalMs != item.rawFrameIntervalMs ->
                        "frameIntervalMs ${guide.frameIntervalMs} != 원문 ${item.rawFrameIntervalMs}"
                    guide.values.size != item.rawValueCount ->
                        "values ${guide.values.size}개 != 원문 ${item.rawValueCount}개"
                    else -> null
                }
            },
        )
    }

    @Test
    fun `전 문항에서 곡선이 그려진다 - 빈 곡선이 없다 (AC1)`() {
        assertEquals(
            emptyList<String>(),
            violations { item ->
                val values = parseVoiceItemStart(item.payload)?.guideF0?.values
                    ?: return@violations "guideF0가 없어 곡선을 그릴 수 없다"
                if (guideCurveDisplayPoints(values).isNotEmpty()) null else "값 ${values.size}개인데 그릴 점이 0개다"
            },
        )
    }

    @Test
    fun `전 문항의 사용자 창이 가이드 길이에서 나오고 폴백으로 떨어지지 않는다 (AC2)`() {
        assertEquals(
            emptyList<String>(),
            violations { item ->
                val guide = parseVoiceItemStart(item.payload)?.guideF0
                    ?: return@violations "guideF0가 없어 창을 가이드에서 뽑을 수 없다"
                val actual = userCurveWindowMs(guide.frameIntervalMs, guide.values.size)
                val expected =
                    (USER_CURVE_WINDOW_SCALE * guide.frameIntervalMs * (guide.values.size - 1)).roundToLong()
                when {
                    actual != expected -> "창 ${actual}ms != 가이드에서 나온 ${expected}ms"
                    actual == FALLBACK_WINDOW_MS -> "창이 폴백 ${FALLBACK_WINDOW_MS}ms와 같다"
                    else -> null
                }
            },
        )
    }

    @Test
    fun `단위는 semitone이고 허용 밴드는 없다 - KAN-17 1안은 중앙선만 낸다`() {
        assertEquals(
            emptyList<String>(),
            violations { item ->
                val unit = item.guideF0["unit"]?.jsonPrimitive?.content
                val hasBand = item.guideF0.containsKey("bandLow") || item.guideF0.containsKey("bandHigh")
                when {
                    unit != "semitone" -> "unit이 $unit 다"
                    hasBand -> "허용 밴드 키가 있다: ${item.guideF0.keys}"
                    else -> null
                }
            },
        )
    }

    @Test
    fun `값 개수는 20의 배수이고 160~320점이다 - 어절 20등분 격자`() {
        assertEquals(
            emptyList<String>(),
            violations { item ->
                val count = item.rawValueCount
                if (count % 20 == 0 && count in 160..320) null else "값이 ${count}개다"
            },
        )
    }

    @Test
    fun `frameIntervalMs가 실수면 같은 문항이라도 곡선을 버린다 - 반올림 발행이 필요한 이유`() {
        // 대조군. `GuideF0.frameIntervalMs`가 Int라 실수 표기는 파싱이 안 되고, 구제 경로가
        // 곡선만 떼어낸 채 문항을 통과시킨다. 발행 파이프라인이 산출물의 실수를 반올림해
        // 내보내는 결정(2026-09-04)이 없었다면 145문항이 통째로 이 상태였을 것이다.
        // (장난감 값 `10.5`로 같은 경로를 보는 케이스는 `AccenturyBridgeTest`에 있다.)
        val item = voiceItems.first { it.itemId == "v102" }
        val broken = item.payload.replace(""""frameIntervalMs":16,""", """"frameIntervalMs":16.4,""")
        assertTrue("전제: 실수로 바꾼 payload가 원본과 달라야 한다", broken != item.payload)

        val start = parseVoiceItemStart(broken)
        assertEquals("문항 자체는 받아야 한다", "v102", start?.itemId)
        assertNull("실수 frameIntervalMs면 곡선은 버려진다", start?.guideF0)

        // 원본(정수)은 같은 payload에서 곡선을 지킨다 — 대조가 성립함을 함께 못박는다.
        assertNotNull(parseVoiceItemStart(item.payload)?.guideF0)
    }

    private val VoiceItem.rawFrameIntervalMs: Int
        get() = guideF0.getValue("frameIntervalMs").jsonPrimitive.int

    private val VoiceItem.rawValueCount: Int
        get() = guideF0.getValue("values").jsonArray.size

    private companion object {
        /** 정본 발행본이 담긴 마이그레이션. 레포 루트 기준 경로다 */
        const val MIGRATION_RELATIVE_PATH =
            "backend/src/main/resources/db/migration/V6__gn_2026_09_1_content.sql"

        /** 정의 JSON을 감싼 PostgreSQL 달러 인용 구분자 */
        const val DELIMITER = "\$definition\$"

        /** 가이드를 쓸 수 없을 때의 창 길이. 어느 문항도 여기로 떨어지면 안 된다 */
        val FALLBACK_WINDOW_MS = userCurveWindowMs(null, null)

        val json = Json { ignoreUnknownKeys = true }

        /** 실패 메시지를 테스트별로 보이게 하려고 늦게 읽는다 — 클래스 초기화에서 터지면 이유가 묻힌다 */
        val voiceItems: List<VoiceItem> by lazy { loadPublishedVoiceItems() }

        /**
         * 마이그레이션 파일을 찾는다. Gradle 유닛 테스트의 `user.dir`은 모듈 디렉터리(`app/`)라
         * 레포 루트까지 거슬러 올라가야 한다. 워크스페이스 배치가 바뀌어도 견디게 몇 단계를 본다.
         */
        fun findMigration(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            val tried = mutableListOf<String>()
            repeat(5) {
                val current = dir ?: return@repeat
                val candidate = File(current, MIGRATION_RELATIVE_PATH)
                tried += candidate.path
                if (candidate.isFile) return candidate
                dir = current.parentFile
            }
            error(
                "발행본 마이그레이션을 찾지 못했다. 찾아본 경로:\n" + tried.joinToString("\n") +
                    "\n이 검사의 입력은 정본 발행본이라, 파일이 없으면 건너뛰는 게 아니라 실패다.",
            )
        }

        fun loadPublishedVoiceItems(): List<VoiceItem> {
            val sql = findMigration().readText()
            val start = sql.indexOf(DELIMITER)
            val end = sql.indexOf(DELIMITER, start + DELIMITER.length)
            check(start >= 0 && end >= 0) { "$DELIMITER 구분자로 감싼 정의 JSON이 없다" }

            val definition = json.parseToJsonElement(sql.substring(start + DELIMITER.length, end)).jsonObject
            assertEquals("gn-2026.09.1", definition.getValue("testVersion").jsonPrimitive.content)

            val voice = definition.getValue("items").jsonArray
                .map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.content == "VOICE" }

            return voice.map { item ->
                val itemId = item.getValue("itemId").jsonPrimitive.content
                val scriptKey = item["scriptKey"]?.jsonPrimitive?.content.orEmpty()
                val guideF0 = item.getValue("guideF0").jsonObject
                VoiceItem(
                    itemId = itemId,
                    scriptKey = scriptKey,
                    guideF0 = guideF0,
                    // 웹이 보내는 payload 그대로다 — guideF0는 원문 JsonElement를 손대지 않고 싣는다.
                    payload = """{"itemId":${item.getValue("itemId")},""" +
                        """"prompt":${item.getValue("prompt")},"itemNumber":1,""" +
                        """"totalItems":${voice.size},"maxDurationMs":15000,"guideF0":$guideF0}""",
                )
            }
        }
    }
}
