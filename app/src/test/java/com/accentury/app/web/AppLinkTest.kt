package com.accentury.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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

    @Test
    fun `퍼센트 인코딩된 경로도 같은 진입점이다`() {
        // 매니페스트의 `android:path` 필터가 디코딩된 경로로 맞추므로 OS가 이미 `/%74`를 앱에 넘긴다.
        // 여기서 거절하면 OS는 넘기는데 앱만 모르는 어긋남이 된다 — iOS도 같은 판정이다.
        assertEquals(
            AppLinkEntry(campaignToken = "kko_share"),
            parseAppLink("https://accentury.app/%74?c=kko_share", allowed),
        )
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

    @Test
    fun `파라미터 이름의 퍼센트 인코딩도 풀어서 맞춘다`() {
        // iOS `URLComponents.queryItems`와 웹 `URLSearchParams`가 둘 다 이름을 풀어서 읽는다.
        // 여기서만 원문으로 비교하면 같은 링크를 안드로이드만 다르게 읽는다.
        assertEquals(
            AppLinkEntry(campaignToken = "kko_share"),
            parseAppLink("https://accentury.app/t?%63=kko_share", allowed),
        )
    }

    @Test
    fun `이름을 푼 뒤에도 먼저 온 값이 이긴다`() {
        assertEquals(
            AppLinkEntry(campaignToken = "a"),
            parseAppLink("https://accentury.app/t?%63=a&c=b", allowed),
        )
    }

    // --- 링크 경계: userinfo·fragment·포트·호스트 대소문자 ---

    @Test
    fun `호스트 앞에 붙인 userinfo는 호스트가 되지 못한다`() {
        // `@` 앞은 userinfo다 — 진짜 호스트는 evil.com이라 allowlist 밖이다.
        assertNull(parseAppLink("https://accentury.app@evil.com/t?c=x", allowed))
        // 반대로 userinfo가 무엇이든 호스트가 우리 것이면 진입이다.
        assertEquals(
            AppLinkEntry(campaignToken = "x"),
            parseAppLink("https://evil.com@accentury.app/t?c=x", allowed),
        )
    }

    @Test
    fun `fragment는 쿼리 판정을 흔들지 않는다`() {
        assertEquals(
            AppLinkEntry(campaignToken = "kko_share"),
            parseAppLink("https://accentury.app/t?c=kko_share#frag", allowed),
        )
        // `#`이 먼저 오면 뒤는 통째로 fragment다 — 쿼리가 아예 없으므로 코드도 없다.
        assertEquals(
            AppLinkEntry(campaignToken = null),
            parseAppLink("https://accentury.app/t#frag?c=x", allowed),
        )
    }

    @Test
    fun `기본 포트는 적혀 있어도 같은 origin이고 다른 포트는 아니다`() {
        assertEquals(
            AppLinkEntry(campaignToken = "x"),
            parseAppLink("https://accentury.app:443/t?c=x", allowed),
        )
        assertNull(parseAppLink("https://accentury.app:8443/t?c=x", allowed))
    }

    @Test
    fun `호스트의 대소문자는 같은 origin이다`() {
        // 경로와 달리 호스트는 대소문자를 가리지 않는다 — webOrigin이 소문자로 내린다.
        assertEquals(
            AppLinkEntry(campaignToken = "x"),
            parseAppLink("https://ACCENTURY.APP/t?c=x", allowed),
        )
    }

    // --- 진입으로 인정하는 origin 목록 (KAN-32 2단계) ---

    @Test
    fun `디버그 웹 origin이 App Link 진입 목록에 더해진다`() {
        // 에뮬레이터에서 adb로 링크 진입을 그대로 밟아 보려면 HTTPS가 아닌 이 origin이 필요하다.
        val origins = appLinkOrigins("http://10.0.2.2:5173")

        assertTrue(origins.containsAll(APP_LINK_ORIGINS))
        assertTrue(origins.contains("http://10.0.2.2:5173"))
    }

    @Test
    fun `릴리스 웹 origin은 이미 목록 안이라 아무것도 늘리지 않는다`() {
        assertEquals(APP_LINK_ORIGINS, appLinkOrigins("https://accentury.app"))
    }

    // --- 매니페스트와 코드가 같은 것을 가리키는지 (KAN-32 2단계) ---

    /**
     * App Link는 매니페스트(OS가 링크를 앱에 넘길지 정한다)와 [parseAppLink](앱이 그 링크를 진입으로
     * 인정할지 정한다)가 **둘 다** 맞아야 성립한다. 한쪽만 고쳐도 컴파일은 통과하고 단위 테스트도
     * 조용한데, 링크만 조용히 죽는다 — 그 어긋남을 잡으라고 매니페스트를 직접 읽는 테스트다.
     */
    @Test
    fun `매니페스트의 App Link 필터가 코드의 origin 목록과 같다`() {
        val activity = mainActivityElement()
        assertEquals("singleTask", activity.getAttributeNS(ANDROID_NS, "launchMode"))

        val filter = viewIntentFilter(activity)
        assertEquals("true", filter.getAttributeNS(ANDROID_NS, "autoVerify"))

        val data = childElements(filter, "data")
        assertEquals(
            APP_LINK_ORIGINS.map { it.removePrefix("https://") }.toSet(),
            data.mapNotNull { it.attributeOrNull("host") }.toSet(),
        )
        assertEquals(setOf("https"), data.mapNotNull { it.attributeOrNull("scheme") }.toSet())
        assertEquals(setOf("/t", "/t/"), data.mapNotNull { it.attributeOrNull("path") }.toSet())

        val categories = childElements(filter, "category").mapNotNull { it.attributeOrNull("name") }.toSet()
        assertTrue(categories.contains("android.intent.category.DEFAULT"))
        assertTrue(categories.contains("android.intent.category.BROWSABLE"))
    }

    private fun mainActivityElement(): Element {
        // Gradle 단위 테스트의 작업 디렉터리는 모듈(app/)이다. 레포 루트에서 돌리는 경우도 받아 준다.
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull(File::exists)
            ?: error("AndroidManifest.xml을 찾지 못했다 (cwd=${File(".").absolutePath})")
        val document = DocumentBuilderFactory.newInstance()
            // android: 접두사가 아니라 네임스페이스로 속성을 읽는다 — 접두사는 파일마다 다를 수 있다.
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val activities = document.getElementsByTagName("activity")
        for (index in 0 until activities.length) {
            val element = activities.item(index) as Element
            if (element.getAttributeNS(ANDROID_NS, "name") == ".MainActivity") return element
        }
        error(".MainActivity 선언을 찾지 못했다")
    }

    private fun viewIntentFilter(activity: Element): Element =
        childElements(activity, "intent-filter").single { filter ->
            childElements(filter, "action").any { it.attributeOrNull("name") == "android.intent.action.VIEW" }
        }

    private fun childElements(parent: Element, tag: String): List<Element> =
        (0 until parent.childNodes.length)
            .mapNotNull { parent.childNodes.item(it) as? Element }
            .filter { it.tagName == tag }

    private fun Element.attributeOrNull(name: String): String? =
        getAttributeNS(ANDROID_NS, name).takeIf { it.isNotEmpty() }
}

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
