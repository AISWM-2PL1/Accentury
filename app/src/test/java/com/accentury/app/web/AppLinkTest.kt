package com.accentury.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLinkTest {

    private val allowed = setOf("https://accentury.app")

    // --- 진입으로 인정하는 링크 (KAN-32) ---

    @Test
    fun `공유 링크는 계측 코드를 실은 진입이 된다`() {
        assertEquals(
            AppLinkEntry(campaignToken = "kko_share"),
            parseAppLink("https://accentury.app/t?c=kko_share", allowed),
        )
    }

    @Test
    fun `끝에 슬래시가 붙은 경로도 같은 진입이다`() {
        assertEquals(
            AppLinkEntry(campaignToken = "kko_share"),
            parseAppLink("https://accentury.app/t/?c=kko_share", allowed),
        )
    }

    @Test
    fun `계측 코드의 퍼센트 인코딩은 풀어서 읽는다`() {
        assertEquals(
            AppLinkEntry(campaignToken = "kko_share"),
            parseAppLink("https://accentury.app/t?c=kko%5Fshare", allowed),
        )
    }

    // --- origin 검사 (§7의 보안 경계를 App Link 입구에도) ---

    @Test
    fun `allowlist 밖의 호스트는 진입이 아니다`() {
        assertNull(parseAppLink("https://evil.example.com/t?c=kko_share", allowed))
    }

    @Test
    fun `스킴이 다르면 같은 호스트여도 진입이 아니다`() {
        assertNull(parseAppLink("http://accentury.app/t?c=kko_share", allowed))
    }

    @Test
    fun `origin이 없는 URL과 null은 진입이 아니다`() {
        assertNull(parseAppLink("javascript:alert(1)", allowed))
        assertNull(parseAppLink("not a url", allowed))
        assertNull(parseAppLink("", allowed))
        assertNull(parseAppLink(null, allowed))
    }

    // --- 경로 검사: `/t`만 진입점이다 ---

    @Test
    fun `루트와 다른 경로는 진입이 아니다`() {
        assertNull(parseAppLink("https://accentury.app/?c=kko_share", allowed))
        assertNull(parseAppLink("https://accentury.app/privacy?c=kko_share", allowed))
    }

    @Test
    fun `t 아래의 하위 경로는 진입이 아니다`() {
        assertNull(parseAppLink("https://accentury.app/t/x?c=kko_share", allowed))
    }

    @Test
    fun `경로의 대소문자는 다른 경로다`() {
        assertNull(parseAppLink("https://accentury.app/T?c=kko_share", allowed))
    }

    // --- 계측 코드가 없거나 계약에 어긋날 때: 진입은 살리고 코드만 버린다 ---

    @Test
    fun `계측 코드가 없어도 진입은 성립한다`() {
        assertEquals(AppLinkEntry(campaignToken = null), parseAppLink("https://accentury.app/t", allowed))
        assertEquals(AppLinkEntry(campaignToken = null), parseAppLink("https://accentury.app/t?x=1", allowed))
    }

    @Test
    fun `서버 계약에 어긋나는 코드는 버리고 진입만 남긴다`() {
        val dropped = AppLinkEntry(campaignToken = null)
        // 공백 — 인코딩돼 도착해도 검사식은 통과하지 못한다.
        assertEquals(dropped, parseAppLink("https://accentury.app/t?c=kko%20share", allowed))
        // 65자 (상한 64자 초과)
        assertEquals(dropped, parseAppLink("https://accentury.app/t?c=${"a".repeat(65)}", allowed))
        // 한글 (`%ED%95%9C%EA%B8%80`)
        assertEquals(dropped, parseAppLink("https://accentury.app/t?c=%ED%95%9C%EA%B8%80", allowed))
        // 빈 값
        assertEquals(dropped, parseAppLink("https://accentury.app/t?c=", allowed))
    }

    @Test
    fun `plus는 공백으로 풀지 않으므로 계측 코드가 되지 못한다`() {
        // iOS의 `URLComponents.queryItems`와 같은 해석이다 — 두 플랫폼이 같은 링크를 같게 읽는지를
        // 이 한 줄이 붙들고 있다. form-urlencoded 규칙(`+` → 공백)으로 풀면 여기서 갈린다.
        assertEquals(AppLinkEntry(campaignToken = null), parseAppLink("https://accentury.app/t?c=a+b", allowed))
    }

    @Test
    fun `상한인 64자는 통과한다`() {
        val token = "a".repeat(64)
        assertEquals(AppLinkEntry(token), parseAppLink("https://accentury.app/t?c=$token", allowed))
    }

    @Test
    fun `끝에 개행이 붙은 코드는 통과하지 못한다`() {
        // iOS는 개행 하나를 눈감아 주는 `$` 대신 `\z`로 같은 판정을 맞춰 뒀다.
        assertEquals(
            AppLinkEntry(campaignToken = null),
            parseAppLink("https://accentury.app/t?c=kko_share%0A", allowed),
        )
    }

    // --- AC: 링크가 개인 결과 또는 세션 토큰을 포함하지 않는다 ---

    @Test
    fun `지어낸 세션과 화면 파라미터는 링크에 실려도 읽지 않는다`() {
        // AppLinkEntry의 필드는 계측 코드 하나뿐이라, 이 동등 비교가 곧 "나머지는 어디에도 안 실렸다"는 주장이다.
        assertEquals(
            AppLinkEntry("kko_share"),
            parseAppLink(
                "https://accentury.app/t?c=kko_share&sessionId=abc&screen=result" +
                    "&testVersion=gn-2026.08.1&bridge=99&app=9.9",
                allowed,
            ),
        )
    }

    @Test
    fun `계측 코드가 여러 번 오면 첫 값만 읽는다`() {
        assertEquals(
            AppLinkEntry("first"),
            parseAppLink("https://accentury.app/t?c=first&c=second", allowed),
        )
    }

    @Test
    fun `인코딩된 구분자가 파라미터 경계를 만들지 못한다`() {
        // `%26`을 먼저 통째로 디코딩하면 `c=a`와 `b`로 갈려 `a`가 코드로 통과한다 — 그걸 막는 케이스다.
        assertEquals(
            AppLinkEntry(campaignToken = null),
            parseAppLink("https://accentury.app/t?c=a%26b", allowed),
        )
    }
}
