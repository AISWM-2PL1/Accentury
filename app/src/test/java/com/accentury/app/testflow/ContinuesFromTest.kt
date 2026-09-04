package com.accentury.app.testflow

import com.accentury.app.bridge.GuideF0
import com.accentury.app.bridge.VoiceItemStart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오버레이가 컴포지션에서 빠질 때 녹음 상태를 되감을지 정하는 판정 (KAN-146).
 *
 * 이 판정이 틀리면 두 방향으로 깨진다: 너무 넓게 이어짐으로 보면 이미 제출해 PCM이 빠져나간 확인
 * 화면이 새 녹음 자리에 그대로 뜨고, 너무 좁게 보면 회전 한 번에 진행 중인 녹음이 죽는다.
 * 화면(Compose) 없이 검증할 수 있게 순수 함수로 떼어 둔 이유가 이것이다.
 */
class ContinuesFromTest {

    @Test
    fun `녹음 중 회전 - 같은 문항의 녹음은 이어진다`() {
        val shown = TestFlowPhase.Recording(voiceItem())

        assertTrue(continuesFrom(shown, TestFlowPhase.Recording(voiceItem())))
    }

    @Test
    fun `제출 중 회전 - 같은 문항의 제출은 이어진다`() {
        val shown = TestFlowPhase.Submitting(voiceItem(), "at_1")

        assertTrue(continuesFrom(shown, TestFlowPhase.Submitting(voiceItem(), "at_1")))
    }

    /*
     * [다음]으로 제출에 들어가는 전이. 여기서 되감으면 방금 그린 '내 억양' 곡선이 제출 화면에서 사라진다.
     */
    @Test
    fun `녹음에서 같은 문항의 제출로 넘어가는 것은 이어진다`() {
        val shown = TestFlowPhase.Recording(voiceItem())

        assertTrue(continuesFrom(shown, TestFlowPhase.Submitting(voiceItem(), "at_1")))
    }

    /*
     * 웹이 결과를 못 받고 같은 문항을 다시 열었을 때 생기는 전이. 그 문항을 처음부터 다시 하는 것이라
     * 되감아야 한다 - 안 그러면 이미 제출한 확인 화면이 그대로 뜨고 거기서 [다음]은 아무 일도 못 한다.
     */
    @Test
    fun `제출에서 같은 문항의 녹음으로 되돌아오는 것은 이어지지 않는다`() {
        val shown = TestFlowPhase.Submitting(voiceItem(), "at_1")

        assertFalse(continuesFrom(shown, TestFlowPhase.Recording(voiceItem())))
    }

    @Test
    fun `다음 문항이 자리를 이어받으면 이어지지 않는다`() {
        val shown = TestFlowPhase.Submitting(voiceItem(), "at_1")
        val next = TestFlowPhase.Recording(voiceItem(itemId = "item_3", number = 3))

        assertFalse(continuesFrom(shown, next))
        assertFalse(continuesFrom(TestFlowPhase.Recording(voiceItem()), next))
    }

    @Test
    fun `웹으로 돌아가면 이어지지 않는다`() {
        assertFalse(continuesFrom(TestFlowPhase.Recording(voiceItem()), TestFlowPhase.Web))
        assertFalse(continuesFrom(TestFlowPhase.Submitting(voiceItem(), "at_1"), TestFlowPhase.Web))
    }

    @Test
    fun `권한 게이트가 다시 서면 이어지지 않는다 - 통과 후 대기 상태에서 시작해야 한다`() {
        val shown = TestFlowPhase.Submitting(voiceItem(), "at_1")

        assertFalse(continuesFrom(shown, TestFlowPhase.NeedsPermission(voiceItem())))
    }

    @Test
    fun `오버레이가 떠 있지 않던 페이즈는 이어질 것이 없다`() {
        assertFalse(continuesFrom(TestFlowPhase.Web, TestFlowPhase.Web))
        assertFalse(continuesFrom(TestFlowPhase.NeedsPermission(voiceItem()), TestFlowPhase.Recording(voiceItem())))
    }

    private fun voiceItem(itemId: String = "item_1", number: Int = 1) = VoiceItemStart(
        itemId = itemId,
        prompt = "밥 뭇나?",
        itemNumber = number,
        totalItems = 10,
        maxDurationMs = 10_000,
        guideF0 = GuideF0(unit = "semitone", frameIntervalMs = 10, values = listOf(0.0, 1.0)),
    )
}
