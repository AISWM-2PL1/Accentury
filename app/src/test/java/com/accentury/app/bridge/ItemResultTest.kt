package com.accentury.app.bridge

import com.accentury.app.audio.QualityStatus
import com.accentury.app.upload.UploadState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemResultTest {

    private val meta = ItemAttempt(
        itemId = "item_1",
        attemptId = "at-1",
        durationMs = 4_200L,
        quality = QualityStatus.NORMAL,
    )

    @Test
    fun `업로드가 끝난 시도만 5필드가 채워진 결과가 된다`() {
        val result = assembleItemResult(meta, mapOf("at-1" to UploadState.Done("aj_1")))

        assertEquals(
            ItemResult(
                itemId = "item_1",
                attemptId = "at-1",
                analysisJobId = "aj_1",
                durationMs = 4_200L,
                qualityStatus = QualityStatus.NORMAL,
            ),
            result,
        )
    }

    @Test
    fun `아직 끝나지 않았거나 실패했거나 모르는 키면 줄 게 없다`() {
        assertNull(assembleItemResult(meta, mapOf("at-1" to UploadState.InFlight)))
        assertNull(
            assembleItemResult(meta, mapOf("at-1" to UploadState.Failed(retryable = true, message = "timeout"))),
        )
        assertNull(assembleItemResult(meta, mapOf("at-2" to UploadState.Done("aj_2"))))
        assertNull(assembleItemResult(meta, emptyMap()))
    }

    @Test
    fun `직렬화 결과의 키는 계약된 5개뿐이다`() {
        val result = assembleItemResult(meta, mapOf("at-1" to UploadState.Done("aj_1")))!!

        val keys = Json.parseToJsonElement(result.toJson()).jsonObject.keys

        assertEquals(
            setOf("itemId", "attemptId", "analysisJobId", "durationMs", "qualityStatus"),
            keys,
        )
    }

    @Test
    fun `qualityStatus는 enum 이름 문자열로 나간다`() {
        val quiet = assembleItemResult(
            meta.copy(quality = QualityStatus.TOO_QUIET),
            mapOf("at-1" to UploadState.Done("aj_1")),
        )!!

        val obj = Json.parseToJsonElement(quiet.toJson()).jsonObject

        assertEquals("TOO_QUIET", obj.getValue("qualityStatus").jsonPrimitive.content)
        assertEquals("4200", obj.getValue("durationMs").jsonPrimitive.content)
    }
}
