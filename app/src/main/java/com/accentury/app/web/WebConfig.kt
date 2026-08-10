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
 * WebView가 로드할 최종 URL. 브리지 버전과 앱 버전을 쿼리로 실어 보낸다 —
 * 스큐 판정의 주체는 웹이므로(§5) 앱은 자기 버전을 알리기만 하면 된다.
 */
fun buildWebUrl(base: String, appVersionName: String): String {
    val separator = if ('?' in base) '&' else '?'
    val encodedApp = URLEncoder.encode(appVersionName, Charsets.UTF_8.name())
    return "$base${separator}bridge=$BRIDGE_CONTRACT_VERSION&app=$encodedApp"
}

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
