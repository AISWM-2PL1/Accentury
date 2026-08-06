package com.accentury.app.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroScreenTest {

    @Test
    fun `문항 구성은 음성과 단어를 나눠 보여주고 합계까지 붙인다`() {
        assertEquals(
            "🎤 음성 5문항 + 📝 단어 5문항 (총 10문항)",
            compositionText(voiceCount = 5, vocabularyCount = 5),
        )
    }

    @Test
    fun `문항 수가 바뀌면 합계도 따라간다`() {
        assertTrue(compositionText(voiceCount = 3, vocabularyCount = 7).contains("총 10문항"))
        assertTrue(compositionText(voiceCount = 4, vocabularyCount = 4).contains("총 8문항"))
    }

    @Test
    fun `예상 소요 시간은 어림값이라 약을 붙여 보여준다`() {
        assertEquals("예상 소요 시간 약 3분", estimatedDurationText(minutes = 3))
    }

    @Test
    fun `KAN-10 확정값인 음성 5 어휘 5 총 10문항을 상수로 들고 있다`() {
        assertEquals(5, VOICE_ITEM_COUNT)
        assertEquals(5, VOCABULARY_ITEM_COUNT)
        assertEquals(10, VOICE_ITEM_COUNT + VOCABULARY_ITEM_COUNT)
        assertEquals(3, ESTIMATED_MINUTES)
    }
}
