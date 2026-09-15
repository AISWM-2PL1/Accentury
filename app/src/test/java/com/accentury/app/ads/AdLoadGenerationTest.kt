package com.accentury.app.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdLoadGenerationTest {

    @Test
    fun `현재 세대의 토큰은 통과한다`() {
        val generation = AdLoadGeneration()
        val token = generation.begin()
        assertTrue(generation.isCurrent(token))
    }

    @Test
    fun `폐기 뒤에는 옛 토큰을 거부한다`() {
        // 동의가 바뀌어 discard()가 불린 뒤 도착하는 옛 조건의 로드 결과가 여기다 — 성공·실패 둘 다 버린다 (P1-2).
        val generation = AdLoadGeneration()
        val token = generation.begin()
        generation.invalidate()
        assertFalse(generation.isCurrent(token))
    }

    @Test
    fun `폐기 뒤 새로 시작한 로드는 통과한다`() {
        // 새 조건으로 다시 나간 요청은 새 세대다. 옛 토큰은 여전히 거부된다.
        val generation = AdLoadGeneration()
        val stale = generation.begin()
        generation.invalidate()
        val fresh = generation.begin()
        assertTrue(generation.isCurrent(fresh))
        assertFalse(generation.isCurrent(stale))
    }
}
