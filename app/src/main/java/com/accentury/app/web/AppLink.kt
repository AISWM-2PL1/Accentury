package com.accentury.app.web

import java.net.URI

/**
 * 공유 유입 계측 코드(`c`)가 서버 계약에 맞는지 보는 검사식 (KAN-31·KAN-32).
 * 웹 `web/src/session/campaign.ts`의 `CAMPAIGN_TOKEN_PATTERN`, 백엔드
 * `CreateSessionRequest.campaignToken`의 `@Pattern`과 같은 규칙이다 — 셋 중 하나만 느슨하면
 * 앱이 통과시킨 값을 서버가 400으로 돌려준다.
 *
 * Kotlin `matches()`는 입력 전체가 맞아떨어질 때만 참이라 끝의 개행 하나를 눈감아 주는
 * 자바 `$`의 관용이 끼어들지 않는다 — iOS도 같은 뜻이 되도록 `\z`로 못 박아 뒀다.
 */
val CAMPAIGN_TOKEN_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")

/**
 * App Link로 들어온 진입 (KAN-32). 링크에서 앱이 읽어 가는 것은 계측 코드 하나뿐이라
 * 필드도 하나다 — 이 자료형이 곧 "링크로 넘어올 수 있는 것의 전부"라는 선언이다.
 *
 * @property campaignToken `?c=` 값. 없거나 서버 계약에 어긋나면 null이고, 그래도 진입 자체는 성립한다
 */
data class AppLinkEntry(val campaignToken: String?)

/**
 * 공유 링크를 앱 진입으로 해석한다 (KAN-32 1단계). `https://accentury.app/t?c=kko_share` 꼴만
 * 받아들이고, 그 밖의 URL은 null — 호출자는 null을 "이 링크는 우리 진입점이 아니다"로 읽으면 된다.
 *
 * null을 돌려주는 경우: url이 없거나 파싱이 안 될 때, [webOrigin]이 없거나 [allowedOrigins] 밖일 때
 * (§7의 보안 경계를 App Link 입구에도 그대로 적용한다), 경로가 `/t`·`/t/`가 아닐 때. 경로를 정확히
 * 맞추는 이유는 `/t/무엇이든`·`/privacy` 같은 링크가 테스트 진입으로 둔갑하지 않게 하기 위해서다.
 *
 * **`c` 말고는 어떤 쿼리도 읽지 않는다.** `sessionId`·`screen`·`testVersion`·`bridge`·`app`이
 * 붙어 와도 전부 버린다 — AC "링크가 개인 결과 또는 세션 토큰을 포함하지 않는다"를 지키는 자리가
 * 여기다. 링크는 누구나 손으로 지어낼 수 있으므로, 읽지 않는 것이 곧 남의 세션을 주입당하거나
 * 결과 화면으로 건너뛰는 링크가 성립하지 않는다는 보증이다. 진입 URL은 앱이 [buildWebUrl]로
 * 직접 조립하고, 링크는 계측 코드 한 개만 거기에 실어 보낸다.
 *
 * 코드가 계약에 어긋나면 진입을 막는 대신 코드만 버린다 — campaign.ts `sanitizeCampaignToken`과
 * 같은 판단이다. 공유 링크는 메신저를 여러 번 거치며 잘리거나 트래킹 파라미터가 덧붙는 경로라
 * 코드가 망가진 채 도착하는 일이 실제로 생기는데, 계측은 실패해도 되는 일이고 응시는 아니다.
 *
 * `android.net.Uri`를 쓰지 않고 [java.net.URI]로 파싱하는 이유는 [webOrigin]과 같다 —
 * 안드로이드 프레임워크를 타지 않아야 JVM 단위 테스트로 검증이 끝난다.
 */
fun parseAppLink(url: String?, allowedOrigins: Set<String>): AppLinkEntry? {
    if (url == null) return null
    val origin = webOrigin(url) ?: return null
    if (origin !in allowedOrigins) return null
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return null
    }
    // 디코딩된 경로로 비교한다 — `/%74`도 `/t`로 인정한다는 뜻이다. 매니페스트의 `android:path`
    // 필터가 이미 디코딩된 경로로 맞추므로 OS가 `/%74`를 앱에 넘겨 주고, 경로를 어떻게 적어 오든
    // 앱이 읽는 값은 `c` 하나뿐이라 escape로 위장해서 얻어 갈 것이 없다.
    val path = uri.path ?: return null
    if (path != "/t" && path != "/t/") return null
    val rawToken = firstQueryValue(uri.rawQuery, "c")
    return AppLinkEntry(campaignToken = rawToken?.takeIf(CAMPAIGN_TOKEN_PATTERN::matches))
}

/**
 * 쿼리에서 [name]의 **첫** 값을 꺼낸다. 같은 이름이 여러 번 오면 뒤엣것은 무시한다 —
 * `?c=a&c=b`처럼 값을 덧붙여 판정을 흔드는 링크에서 앱이 읽는 값이 하나로 정해져 있어야 한다.
 *
 * 디코딩된 [java.net.URI.getQuery] 대신 [java.net.URI.getRawQuery]를 쪼갠 뒤 푸는 이유:
 * 먼저 통째로 풀면 `c=a%26b`의 `%26`이 진짜 `&`가 되어 파라미터 경계가 하나 더 생긴다.
 * 경계를 원문으로 가른 다음에는 **이름도 값과 똑같이 디코딩해서** 비교한다 — `?%63=kko_share`가
 * `?c=kko_share`와 같은 링크라는 뜻이다. iOS의 `URLComponents.queryItems`와 웹의
 * `URLSearchParams`가 둘 다 이름을 풀어서 읽으므로, 같은 링크를 세 소비자가 같게 읽으려면
 * 여기도 풀어야 한다.
 */
private fun firstQueryValue(rawQuery: String?, name: String): String? {
    if (rawQuery == null) return null
    for (pair in rawQuery.split('&')) {
        if (pair.isEmpty()) continue
        val separator = pair.indexOf('=')
        val key = if (separator < 0) pair else pair.substring(0, separator)
        if (decodePercentEscapes(key) != name) continue
        return decodePercentEscapes(if (separator < 0) "" else pair.substring(separator + 1))
    }
    return null
}

/**
 * `%XX`만 UTF-8 바이트로 되돌린다. `+`는 공백으로 바꾸지 않고 글자 그대로 둔다 —
 * iOS의 `URLComponents.queryItems`가 그렇게 동작하므로 두 플랫폼이 같은 링크를 같게 읽으려면
 * `URLDecoder`(form-urlencoded, `+` → 공백)를 쓸 수 없다. `+`는 검사식에 없는 글자라
 * 어느 쪽이든 계측 코드로는 통과하지 못하고, 그 사실을 두 플랫폼 모두 테스트로 박아 뒀다.
 */
private fun decodePercentEscapes(value: String): String {
    val out = java.io.ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        val high = if (char == '%' && index + 2 < value.length) hexDigit(value[index + 1]) else -1
        val low = if (high >= 0) hexDigit(value[index + 2]) else -1
        if (low >= 0) {
            out.write(high * 16 + low)
            index += 3
        } else {
            // 망가진 escape는 되살리지 않고 글자 그대로 흘린다 — 어차피 검사식에서 걸러진다.
            out.write(char.toString().toByteArray(Charsets.UTF_8))
            index += 1
        }
    }
    return out.toString(Charsets.UTF_8.name())
}

private fun hexDigit(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
}

/**
 * App Link로 앱을 열 수 있는 origin (KAN-32 2단계). `AndroidManifest.xml`의 VIEW intent-filter가
 * 든 `android:host` 목록과 같은 것을 가리키는 거울이다 — 한쪽만 고치면 매니페스트는 링크를 앱으로
 * 넘기는데 [parseAppLink]가 그 origin을 모르는(또는 그 반대의) 어긋남이 생기므로,
 * `AppLinkTest`가 두 목록이 같은지를 매니페스트를 직접 읽어 검사한다.
 *
 * staging을 함께 두는 이유: 릴리스 전에 팀이 staging 버킷의 assetlinks(디버그 서명 지문까지 실린다)로
 * 실제 링크 탭 흐름을 확인할 수 있어야 한다. 검증은 호스트마다 `/.well-known/assetlinks.json`이
 * 있어야 성립한다 (KAN-32 4단계).
 */
val APP_LINK_ORIGINS: Set<String> = setOf("https://accentury.app", "https://staging.accentury.app")

/**
 * 이 빌드가 App Link 진입으로 인정할 origin 전부 — [APP_LINK_ORIGINS]에 이 빌드의 웹 origin을 더한다.
 *
 * 더하는 쪽은 사실상 디버그 빌드다. 에뮬레이터의 `WEB_URL`은 `http://10.0.2.2:5173`이라 HTTPS
 * 검증이 성립하지 않는데, 이 origin이 목록에 있으면
 * `adb shell am start -n com.accentury.app/.MainActivity -a android.intent.action.VIEW -d "http://10.0.2.2:5173/t?c=x"`로
 * 링크 진입 경로를 그대로 밟아 볼 수 있다 (매니페스트 필터는 https·accentury.app만 받으므로
 * 컴포넌트를 `-n`으로 직접 지정해야 한다).
 * 릴리스에서는 `webOrigin(WEB_URL)`이 이미 [APP_LINK_ORIGINS] 안에 있어 아무것도 늘지 않는다.
 */
fun appLinkOrigins(webUrl: String): Set<String> = APP_LINK_ORIGINS + setOfNotNull(webOrigin(webUrl))
