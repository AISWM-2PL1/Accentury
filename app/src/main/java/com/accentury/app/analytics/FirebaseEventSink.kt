package com.accentury.app.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

private const val TAG = "FirebaseEventSink"

/**
 * 이벤트를 Firebase Analytics로 흘려보내는 [EventSink] (KAN-33).
 *
 * 사용자 식별자를 붙이지 않는다 — `setUserId`를 부르는 곳이 앱 전체에 없고, 광고 식별자 수집은
 * 매니페스트의 `google_analytics_adid_collection_enabled=false`가 끈다. 웹에서
 * `allow_google_signals`·`allow_ad_personalization_signals`를 끈 것과 같은 요구다.
 *
 * SDK가 붙여 주는 축(기기·OS·앱 버전·앱 인스턴스)이 이 sink가 존재하는 이유다. 앱 안 이벤트를
 * WebView의 gtag로 보내면 그 축이 없는 데다 앱 사용자가 웹 트래픽으로 세어진다 (`track.ts`).
 */
class FirebaseEventSink(private val analytics: FirebaseAnalytics) : EventSink {
    override fun log(name: String, params: Map<String, EventParam>) {
        try {
            analytics.logEvent(name, params.toBundle())
        } catch (t: Throwable) {
            /*
             * 계측이 사용자 흐름을 끊으면 안 된다 (EventSink.log 규칙). Throwable까지 잡는 이유는
             * 이 자리가 SDK 경계라서다 - 초기화 경합이나 Play 서비스 부재로 Exception이 아닌
             * 오류(NoClassDefFoundError 등)가 올라오는 경로가 있고, 그때도 잃어도 되는 것은
             * 이벤트 하나뿐이다. 값은 남기지 않는다 (KAN-38 마스킹 원칙).
             */
            Log.w(TAG, "이벤트 전송 실패: $name")
        }
    }
}

/**
 * 이 빌드가 쓸 sink를 고른다 — **설정 유무를 판정하는 유일한 자리다.**
 *
 * google-services.json이 없으면 FirebaseApp이 초기화되지 않고([firebaseAnalyticsOrNull]),
 * 호출자는 그 사실을 모른 채 [LogcatEventSink]를 받는다. 판정을 여기 하나로 모으는 이유는
 * 카카오 키 분기와 같다 (`AccenturyApplication`): "설정이 없다"를 화면마다 다시 물으면 어떤
 * 화면은 묻는 것을 잊고, 그 화면만 초기화되지 않은 SDK를 부르게 된다.
 *
 * 컴패니언 확장인 이유는 파일을 나누기 위해서다. 판정에 Firebase 클래스가 필요한데, 창구
 * 정의(`AppEvents.kt`)까지 SDK에 묶이면 화면이 보는 계약과 전송 수단이 한 파일에 섞인다.
 */
fun EventSink.Companion.create(context: Context): EventSink =
    eventSinkFor(firebaseAnalyticsOrNull(context))

/**
 * 판정의 순수한 절반 — Firebase가 없으면 Logcat이다. JVM 테스트가 이 규칙을 못박는다
 * (`FirebaseAnalytics` 인스턴스는 계측기 없이 만들 수 없어 null 쪽만 검증 대상이다).
 */
internal fun eventSinkFor(analytics: FirebaseAnalytics?): EventSink =
    if (analytics == null) LogcatEventSink else FirebaseEventSink(analytics)

/**
 * 설정이 있는 빌드에서만 계측기를 돌려준다.
 *
 * `FirebaseApp.getApps`가 판정 근거다 — google-services 플러그인이 만든 리소스가 있으면 SDK의
 * ContentProvider가 앱 시작 시점에 이미 초기화를 끝내 놓는다. 그 리소스가 없으면 목록이 비고,
 * 그 상태에서 `FirebaseAnalytics.getInstance`를 부르면 예외가 난다.
 *
 * runCatching으로 한 겹 더 감싸는 이유: 설정이 반쯤 들어간 상태(파일은 있는데 값이 우리 패키지와
 * 다른 경우 등)에서 초기화가 실패하는 경로가 남는다. 그때도 앱은 계측 없이 그냥 돌아야 한다.
 */
private fun firebaseAnalyticsOrNull(context: Context): FirebaseAnalytics? = runCatching {
    if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseAnalytics.getInstance(context)
}.getOrNull()

/**
 * 파라미터를 Firebase가 받는 [Bundle]로 옮긴다. 타입이 여기서 갈린다 — 숫자를 putString으로
 * 넣으면 GA4에서 측정항목이 아니라 차원이 되어 평균·P95를 낼 수 없다 ([EventParam] KDoc).
 */
private fun Map<String, EventParam>.toBundle(): Bundle {
    val bundle = Bundle(size)
    for ((key, param) in this) {
        when (param) {
            is EventParam.Text -> bundle.putString(key, param.value)
            is EventParam.Count -> bundle.putLong(key, param.value)
            is EventParam.Amount -> bundle.putDouble(key, param.value)
        }
    }
    return bundle
}
