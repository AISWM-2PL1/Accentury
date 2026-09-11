package com.accentury.app.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedRetestGateTest {

    @Test
    fun `보여줄 광고가 없으면 광고 없이 통과한다`() {
        // 로드 실패·아직 로드 전·소진 뒤 재로드 전이 모두 여기다 — 광고 사정으로 재응시를 막지 않는다 (§8.2).
        val gate = RewardedRetestGate()
        assertEquals(RewardedGateDecision.Proceed, gate.request(loaded = false))
        assertFalse(gate.showing)
    }

    @Test
    fun `광고가 있으면 띄우고 결정은 콜백으로 미룬다`() {
        val gate = RewardedRetestGate()
        assertEquals(RewardedGateDecision.ShowAd, gate.request(loaded = true))
        assertTrue(gate.showing)
    }

    @Test
    fun `끝까지 보면 통과하고 뒤따르는 닫힘은 무시한다`() {
        // SDK는 보상 콜백을 닫힘 콜백보다 먼저 준다. 통과는 보상 시점에 한 번이고 닫힘은 두 번째
        // 진행이 되면 안 된다.
        val gate = RewardedRetestGate()
        gate.request(loaded = true)
        assertEquals(RewardedGateDecision.Proceed, gate.onEarnedReward())
        assertEquals(RewardedGateDecision.Ignored, gate.onDismissed())
        assertFalse(gate.showing)
    }

    @Test
    fun `보상 없이 닫으면 Dismissed다`() {
        val gate = RewardedRetestGate()
        gate.request(loaded = true)
        assertEquals(RewardedGateDecision.Dismissed, gate.onDismissed())
        assertFalse(gate.showing)
    }

    @Test
    fun `표시에 실패하면 광고 없이 통과한다`() {
        val gate = RewardedRetestGate()
        gate.request(loaded = true)
        assertEquals(RewardedGateDecision.Proceed, gate.onShowFailed())
        assertFalse(gate.showing)
    }

    @Test
    fun `표시 중의 두 번째 요청은 무시한다`() {
        // 웹의 pending 잠금이 먼저 막지만 네이티브도 한 겹 — 광고를 두 번 띄우면 첫 광고의 보상이
        // 갈 곳을 잃는다.
        val gate = RewardedRetestGate()
        gate.request(loaded = true)
        assertEquals(RewardedGateDecision.Ignored, gate.request(loaded = true))
        assertEquals(RewardedGateDecision.Ignored, gate.request(loaded = false))
        assertTrue(gate.showing)
    }

    @Test
    fun `보상은 한 번만 통과시킨다`() {
        val gate = RewardedRetestGate()
        gate.request(loaded = true)
        assertEquals(RewardedGateDecision.Proceed, gate.onEarnedReward())
        assertEquals(RewardedGateDecision.Ignored, gate.onEarnedReward())
    }

    @Test
    fun `표시 중이 아닌데 온 콜백은 전부 무시한다`() {
        val gate = RewardedRetestGate()
        assertEquals(RewardedGateDecision.Ignored, gate.onEarnedReward())
        assertEquals(RewardedGateDecision.Ignored, gate.onDismissed())
        assertEquals(RewardedGateDecision.Ignored, gate.onShowFailed())
    }

    @Test
    fun `닫힌 뒤에는 다음 요청을 다시 받는다`() {
        // 중도에 닫아 Dismissed가 났어도 결과 화면의 버튼은 다시 열린다 — 다음 탭은 새 광고다.
        val gate = RewardedRetestGate()
        gate.request(loaded = true)
        gate.onDismissed()
        assertEquals(RewardedGateDecision.ShowAd, gate.request(loaded = true))
        // 앞선 표시의 보상 여부가 새어 오지 않는다.
        assertEquals(RewardedGateDecision.Dismissed, gate.onDismissed())
    }
}
