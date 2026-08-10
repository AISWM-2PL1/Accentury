package com.accentury.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebConfigTest {

    // --- buildWebUrl: 스큐 협상 파라미터 (§5) ---

    @Test
    fun `로드 URL에 브리지 계약 버전과 앱 버전을 실어 보낸다`() {
        assertEquals(
            "https://web.example.com?bridge=$BRIDGE_CONTRACT_VERSION&app=1.0",
            buildWebUrl("https://web.example.com", "1.0"),
        )
    }

    @Test
    fun `기존 쿼리가 있으면 덮지 않고 뒤에 잇는다`() {
        assertEquals(
            "https://web.example.com?env=dev&bridge=$BRIDGE_CONTRACT_VERSION&app=1.0",
            buildWebUrl("https://web.example.com?env=dev", "1.0"),
        )
    }

    @Test
    fun `앱 버전 문자열은 URL 인코딩을 거친다`() {
        val url = buildWebUrl("https://web.example.com", "1.0 beta")
        assertFalse(url.contains(" "))
        assertTrue(url.contains("app=1.0"))
    }

    // --- webOrigin: allowlist 비교 입력 정규화 (§7) ---

    @Test
    fun `http https URL에서 origin을 뽑는다`() {
        assertEquals("https://web.example.com", webOrigin("https://web.example.com/intro?x=1"))
        assertEquals("http://10.0.2.2:5173", webOrigin("http://10.0.2.2:5173/"))
    }

    @Test
    fun `기본 포트는 표기 유무와 무관하게 같은 origin이다`() {
        assertEquals(webOrigin("https://web.example.com"), webOrigin("https://web.example.com:443"))
        assertEquals(webOrigin("http://web.example.com"), webOrigin("http://web.example.com:80"))
    }

    @Test
    fun `http 계열이 아닌 스킴은 origin이 없다 - javascript file about`() {
        assertNull(webOrigin("javascript:alert(1)"))
        assertNull(webOrigin("file:///etc/passwd"))
        assertNull(webOrigin("about:blank"))
    }

    @Test
    fun `파싱 불가능한 문자열은 origin이 없다`() {
        assertNull(webOrigin("not a url"))
        assertNull(webOrigin(""))
    }

    // --- isAllowedWebUrl: 보안 경계 (§7) ---

    @Test
    fun `allowlist 안의 origin만 허용한다`() {
        val allowed = setOf("https://web.example.com")
        assertTrue(isAllowedWebUrl("https://web.example.com/intro", allowed))
        assertFalse(isAllowedWebUrl("https://evil.example.com/intro", allowed))
        assertFalse(isAllowedWebUrl("http://web.example.com/intro", allowed)) // 스킴 다운그레이드 거부
        assertFalse(isAllowedWebUrl("https://web.example.com:8443/intro", allowed)) // 다른 포트 거부
    }

    @Test
    fun `null이나 origin이 없는 URL은 항상 거부한다`() {
        val allowed = setOf("https://web.example.com")
        assertFalse(isAllowedWebUrl(null, allowed))
        assertFalse(isAllowedWebUrl("javascript:alert(1)", allowed))
    }

    @Test
    fun `호스트 대소문자는 origin 비교에 영향을 주지 않는다`() {
        assertTrue(isAllowedWebUrl("https://WEB.Example.com/intro", setOf("https://web.example.com")))
    }
}
