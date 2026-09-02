package com.accentury.app.web

import java.net.URI
import java.net.URLEncoder

/**
 * 앱이 보유한 브리지 계약 버전 (webview-layer.md §5).
 * 규칙: 메서드·필드 추가는 하위호환이라 버전을 유지하고, 삭제·의미 변경 시에만 올린다.
 * ItemResult 5필드(KAN-89 계약)를 바꾸는 변경도 반드시 버전 증가 대상이다.
 */
const val BRIDGE_CONTRACT_VERSION = 1

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
 * @property sessionId 진행 스냅샷을 세션별로 가르는 식별자. 업로드가 붙는 세션과 같은 값이어야 한다
 */
data class TestEntry(val testVersion: String, val sessionId: String)

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
