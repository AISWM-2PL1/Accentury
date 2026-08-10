package com.accentury.app.web

import org.junit.Assert.assertEquals
import org.junit.Test

class AccenturyBridgeTest {

    /** View.post처럼 실행을 미뤄두는 큐. 브리지의 "메인 스레드로 넘긴 뒤 검증" 순서를 재현한다. */
    private class FakeMainQueue {
        private val queue = mutableListOf<() -> Unit>()
        fun post(block: () -> Unit) {
            queue += block
        }
        fun drain() {
            queue.forEach { it() }
            queue.clear()
        }
    }

    @Test
    fun `허용된 origin이면 권한 게이트 콜백이 실행된다`() {
        val queue = FakeMainQueue()
        var fired = 0
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { true },
            onRequestMicPermission = { fired++ },
        )
        bridge.requestMicPermission()
        queue.drain()
        assertEquals(1, fired)
    }

    @Test
    fun `allowlist 밖 origin에서는 콜백이 실행되지 않는다`() {
        val queue = FakeMainQueue()
        var fired = 0
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { false },
            onRequestMicPermission = { fired++ },
        )
        bridge.requestMicPermission()
        queue.drain()
        assertEquals(0, fired)
    }

    @Test
    fun `origin 검증은 호출 시점이 아니라 메인 스레드 실행 시점 값으로 판정한다`() {
        // JS 스레드에서 호출된 직후 페이지가 allowlist 밖으로 리다이렉트되는 경합을 재현한다.
        // 호출 시점(allowed=true)이 아니라 실행 시점(allowed=false)을 봐야 안전하다 (§8).
        val queue = FakeMainQueue()
        var allowedNow = true
        var fired = 0
        val bridge = AccenturyBridge(
            postToMain = queue::post,
            isCurrentUrlAllowed = { allowedNow },
            onRequestMicPermission = { fired++ },
        )
        bridge.requestMicPermission() // 호출 시점엔 허용 상태
        allowedNow = false // 실행 전에 allowlist 밖으로 이동
        queue.drain()
        assertEquals(0, fired)
    }

    @Test
    fun `getContractVersion은 앱이 보유한 계약 버전을 돌려준다`() {
        val bridge = AccenturyBridge(
            postToMain = { it() },
            isCurrentUrlAllowed = { true },
            onRequestMicPermission = {},
        )
        assertEquals(BRIDGE_CONTRACT_VERSION, bridge.getContractVersion())
    }
}
