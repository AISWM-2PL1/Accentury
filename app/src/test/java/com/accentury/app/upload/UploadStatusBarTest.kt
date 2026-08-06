package com.accentury.app.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadStatusBarTest {

    @Test
    fun `진행 중은 세고 완료는 표시 대상에서 빠진다`() {
        val summary = summarize(
            linkedMapOf(
                "at-1" to UploadState.InFlight,
                "at-2" to UploadState.Done("aj_2"),
                "at-3" to UploadState.InFlight,
            ),
        )

        assertEquals(2, summary.inFlight)
        assertTrue(summary.failed.isEmpty())
    }

    @Test
    fun `실패는 재시도 가능 여부와 함께 넣은 순서대로 나온다`() {
        val summary = summarize(
            linkedMapOf(
                "at-1" to UploadState.Failed(retryable = true, message = "timeout"),
                "at-2" to UploadState.Done("aj_2"),
                "at-3" to UploadState.Failed(retryable = false, message = "파일이 너무 큽니다"),
            ),
        )

        assertEquals(0, summary.inFlight)
        assertEquals(listOf("at-1", "at-3"), summary.failed.map { it.first })
        assertEquals(listOf(true, false), summary.failed.map { it.second.retryable })
        assertEquals("timeout", summary.failed.first().second.message)
    }

    @Test
    fun `업로드가 없으면 보여줄 것도 없다`() {
        val summary = summarize(emptyMap())

        assertEquals(0, summary.inFlight)
        assertTrue(summary.failed.isEmpty())
    }
}
