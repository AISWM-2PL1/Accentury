package com.accentury.app.web

import java.net.URI
import java.net.URLEncoder

/**
 * 앱이 보유한 브리지 계약 버전 (webview-layer.md §5).
 * 규칙: 메서드·필드 추가는 하위호환이라 버전을 유지하고, 삭제·의미 변경 시에만 올린다.
 * ItemResult 5필드(KAN-89 계약)를 바꾸는 변경도 반드시 버전 증가 대상이다.
 *
 * KAN-205에서 1 → 2로 올렸다. 진입 쿼리의 `voiceSet`이 웹에 필수가 됐고, 그 값을 싣지 않는
 * 구버전 앱은 문항 화면에서 빠져나올 수 없다 - 웹의 `REQUIRED_BRIDGE_VERSION`과 짝이다.
 */
const val BRIDGE_CONTRACT_VERSION = 2

/**
 * 로드 실패 판정 자체 타임아웃 (§6). onPageFinished가 영영 안 오는 경우를 대비한다.
 * 8초 = Nielsen 10초 주의력 한계 직전, "진입 → 결과 3분" 목표와 정합하는 제안값.
 */
const val LOAD_TIMEOUT_MS = 8_000L

/**
 * 테스트 진입 파라미터 (KAN-100). 시작 게이트(KAN-98)를 통과한 뒤의 정식 진입 URL에만 붙는다 —
 * 웹은 `screen=test`를 보고 인트로 대신 문항 진행 화면으로 들어간다 (web/src/App.tsx).
 *
 * @property testVersion 세션에 고정된 정의 버전. 웹이 `GET /v0/tests/{testVersion}`으로 정의를 받는다
 * @property voiceSet 세션에 고정된 음성 문항 세트 (KAN-205). 웹이 정의 조회의 `?voiceSet=`에 그대로
 *   넣는다 — 빠지면 세트 1의 문항이 와서 세션의 세트와 갈리고 제출이 전부 422다
 * @property sessionId 진행 스냅샷을 세션별로 가르는 식별자. 업로드가 붙는 세션과 같은 값이어야 한다
 */
data class TestEntry(val testVersion: String, val voiceSet: Int, val sessionId: String)

/**
 * WebView가 로드할 최종 URL. 브리지 버전과 앱 버전을 쿼리로 실어 보낸다 —
 * 스큐 판정의 주체는 웹이므로(§5) 앱은 자기 버전을 알리기만 하면 된다.
 *
 * [testEntry]가 있으면 테스트 진입 URL, 없으면 인트로 URL이다. 두 URL을 한 함수로 묶은 이유:
 * 스큐 파라미터는 어느 쪽에도 빠지면 안 되는데(빠지면 웹이 업데이트 안내를 띄운다) 조립을
 * 나누면 한쪽만 고치는 실수가 생긴다.
 *
 * @param campaignToken App Link로 들어온 공유 유입 계측 코드 ([parseAppLink], KAN-32). 있으면
 *   `c`로 딸려 보내 웹이 만드는 첫 세션에 같은 코드가 실리게 한다 — 웹은 진입 쿼리의 `?c=`를
 *   `session/campaign.ts`로 읽고 `navigation/entryUrl.ts`가 화면을 옮겨도 그 값을 보존한다.
 *   앱이 값을 해석하지 않고 그대로 넘기는 자리라, 링크에서 실려 온 유입 경로가 세션까지 이어진다.
 */
fun buildWebUrl(
    base: String,
    appVersionName: String,
    testEntry: TestEntry? = null,
    campaignToken: String? = null,
): String {
    val separator = if ('?' in base) '&' else '?'
    val query = StringBuilder("bridge=$BRIDGE_CONTRACT_VERSION&app=${encodeQueryValue(appVersionName)}")
    if (testEntry != null) {
        query.append("&screen=test")
        query.append("&testVersion=${encodeQueryValue(testEntry.testVersion)}")
        query.append("&voiceSet=${testEntry.voiceSet}")
        query.append("&sessionId=${encodeQueryValue(testEntry.sessionId)}")
    }
    if (campaignToken != null) {
        query.append("&c=${encodeQueryValue(campaignToken)}")
    }
    return "$base$separator$query"
}

/**
 * 값에 든 `&`·`=`·공백이 쿼리 구조를 깨뜨리지 않게 한다. 앱 버전은 물론 서버가 발급하는
 * testVersion·sessionId도 형식을 앱이 정하지 않으므로 전부 거쳐 간다.
 */
private fun encodeQueryValue(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

/**
 * URL의 origin(스킴://호스트[:포트])을 뽑는다. http(s)가 아니거나(javascript: 등)
 * 파싱이 안 되면 null — allowlist 비교의 입력을 한 가지 꼴로 좁히는 함수다.
 * 기본 포트(80/443)는 표기 유무가 같은 origin이 되도록 지운다.
 */
fun webOrigin(url: String): String? {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase() ?: return null
    val port = when {
        uri.port == -1 -> -1
        scheme == "http" && uri.port == 80 -> -1
        scheme == "https" && uri.port == 443 -> -1
        else -> uri.port
    }
    return if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
}

/**
 * allowlist 검사 (§7). 브리지가 마이크 권한 게이트를 호출하므로 이 검사가 곧 보안 경계다 —
 * allowlist 밖 URL은 WebView 로드도, 브리지 실행도 막는다.
 */
fun isAllowedWebUrl(url: String?, allowedOrigins: Set<String>): Boolean {
    if (url == null) return false
    val origin = webOrigin(url) ?: return false
    return origin in allowedOrigins
}

/**
 * 앱 밖 브라우저로 열어도 되는 호스트 (KAN-177).
 *
 * 지금 여기로 나가는 링크는 개인정보처리방침 하나뿐이다 (`web/src/legal/privacyPolicy.ts`).
 * 그런데도 목록을 두는 이유는 **여는 주소를 정하는 쪽이 웹**이기 때문이다 — WebView에 실린
 * 스크립트가 부르는 메서드라, 넘어온 값을 그대로 열면 앱이 아무 주소나 여는 창구가 된다.
 * Custom Tabs가 주소를 보여 주므로 위장이 쉽지는 않지만, 우리 앱이 여는 페이지라는 사실 자체가
 * 신뢰의 근거가 되는 자리다.
 *
 * **prod 호스트 하나뿐이다.** 방침은 법적 고지라 정본이 하나여야 하고, 그래서 웹 상수도
 * 환경과 무관하게 prod를 가리킨다 (`web/src/legal/privacyPolicy.ts`) — staging 빌드도 같은
 * 문서를 연다. 여기에 `staging.accentury.app`을 함께 두면 **웹이 절대 보내지 않는 호스트로
 * 문을 하나 더 여는 것**이라 뺐다. [APP_LINK_ORIGINS]에 staging이 있는 것과 혼동하지 말 것 —
 * 저쪽은 링크로 앱에 **들어오는** 경로라 릴리스 전 확인에 staging 버킷이 필요하다.
 *
 * 디버그 빌드의 `WEB_URL`(에뮬레이터 로컬 Vite)에서 파생하지 않는 이유도 같다 — 방침 문서는
 * 로컬에 없고 늘 우리 도메인에 있다.
 */
val EXTERNAL_LINK_HOSTS = setOf("accentury.app")

/**
 * 브리지가 받은 외부 URL을 열어도 되는지 판정한다 (KAN-177). 열어도 되면 그 URL, 아니면 null.
 *
 * [isAllowedWebUrl]과 판정 대상이 다르다. 저쪽은 **WebView가 로드할** URL을 origin 단위로
 * 보고, 이쪽은 **앱 밖으로 내보낼** URL을 호스트 단위로 본다 — 포트·경로가 문서마다 다를 수
 * 있어서 origin 일치를 요구하면 방침 문서를 옮기는 날 링크가 조용히 죽는다.
 *
 * https만 통과시킨다. 방침 문서는 어느 환경에서도 HTTPS로 서므로 http를 받아 줄 이유가 없고,
 * `javascript:`·`intent:`·앱 스킴은 [URI]가 host를 주지 않아 자동으로 걸린다.
 *
 * `user@host` 꼴과 역슬래시·공백을 따로 막는 이유는 **파서가 둘**이기 때문이다. 여기서 쓰는
 * `java.net.URI`와 실제로 여는 `android.net.Uri`가 그 문자들에서 host를 다르게 읽을 수 있어,
 * 검사에 쓴 호스트와 열리는 호스트가 갈릴 여지가 남는다. 애초에 방침 URL에는 없는 문자다.
 *
 * 호스트의 `%`도 같이 막는다 (KAN-199 #2). `java.net.URI`는 authority를 디코딩하지 않아
 * `https://%61ccentury.app/...`에서 host가 null이 되고, 지금도 결과적으로는 거절된다. 그런데
 * **iOS Foundation은 디코딩해서 `accentury.app`으로 읽어 통과시킨다** — 같은 입력에 두 앱이
 * 다르게 답하는 자리다. 보안 결함은 아니지만(양쪽 다 자기가 검사한 호스트를 그대로 연다)
 * 계약이 갈리므로 양쪽에서 명시적으로 거절해 판정을 하나로 맞춘다. 방침 URL에는 없는 문자라
 * 잃는 것이 없고, 명시해 두면 파서를 바꾸는 날 조용히 통과하는 일도 없다.
 */
fun externalUrlToOpen(url: String?, allowedHosts: Set<String> = EXTERNAL_LINK_HOSTS): String? {
    if (url == null) return null
    if (url.any { it == '\\' || it.isWhitespace() || it.code < 0x20 }) return null
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return null
    }
    if (uri.scheme?.lowercase() != "https") return null
    if (uri.userInfo != null) return null
    // authority를 디코딩하지 않는 파서라 host가 이미 null이지만, 판정을 iOS와 맞추려면
    // "왜 거절했는가"가 코드에 남아야 한다 (위 문단)
    if (uri.rawAuthority?.contains('%') == true) return null
    val host = uri.host?.lowercase() ?: return null
    return if (host in allowedHosts) url else null
}
