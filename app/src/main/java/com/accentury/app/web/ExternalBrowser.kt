package com.accentury.app.web

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.accentury.app.analytics.CrashReports
import com.accentury.app.ui.theme.LightBackground
import com.accentury.app.ui.theme.LightForeground
import androidx.compose.ui.graphics.toArgb

/**
 * 앱 밖 링크를 여는 자리 (KAN-177). `WebViewClient.shouldOverrideUrlLoading`이 "외부 링크가
 * 생기면 여기서 Custom Tabs로 여는 처리를 더한다"고 적어 둔 그 처리다.
 *
 * ## WebView 안에서 열지 않는 이유
 *
 * 그러면 인트로가 사라진다. 앱에는 뒤로 갈 길이 없어서(WebView back을 시스템 뒤로가기로
 * 넘기는 코드가 없다 — 누르면 액티비티가 닫힌다) 방침을 읽은 사람이 테스트로 돌아올 방법이
 * 남지 않는다. allowlist(§7)도 걸린다: 디버그 빌드의 origin은 로컬 Vite라 우리 도메인의
 * 방침 문서가 로드 차단 대상이 된다.
 *
 * Custom Tabs는 인트로 **위에** 시트를 덮는다. 닫으면 인트로가 그대로 남아 있고, 주소창에
 * 우리 도메인이 보여서 사용자가 무엇을 읽는지 확인할 수 있다.
 */
object ExternalBrowser {

    /**
     * [url]을 Custom Tabs로 연다. 호출 전에 [externalUrlToOpen]을 통과한 값이어야 한다 —
     * 이 함수는 검증하지 않고 열기만 한다.
     *
     * Custom Tabs를 지원하는 브라우저가 없으면 `launchUrl`이 알아서 보통 브라우저 인텐트로
     * 내려간다(라이브러리가 하는 일이다). 브라우저가 **하나도** 없는 기기에서만 예외가 나는데,
     * 그 자리에서 앱이 할 수 있는 일은 없으므로 크래시만 막고 흔적을 남긴다 — 방침 링크
     * 하나 때문에 응시하던 앱이 죽는 것이 최악이다.
     */
    fun open(context: Context, url: String) {
        /*
         * 툴바를 크림·잉크로 맞춘다 (KAN-161). 시트가 올라오는 순간 색이 갈리면 앱을 벗어난
         * 것처럼 읽히는데, 실제로는 앱 위에 덮인 창이라 그 인상이 사실과 다르다.
         * 팔레트의 정본은 Compose 테마 하나이므로 상수를 다시 적지 않고 거기서 가져온다.
         */
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(LightBackground.toArgb())
            .setNavigationBarColor(LightBackground.toArgb())
            .setSecondaryToolbarColor(LightForeground.toArgb())
            .build()

        val intent = CustomTabsIntent.Builder()
            // 문서 제목을 툴바에 보인다 — 주소만 있으면 무엇을 읽는 중인지 한눈에 들어오지 않는다
            .setShowTitle(true)
            .setDefaultColorSchemeParams(colors)
            .build()

        try {
            intent.launchUrl(context, url.toUri())
        } catch (_: ActivityNotFoundException) {
            CrashReports.recordExternalLinkFailure("no_browser")
        }
    }
}
