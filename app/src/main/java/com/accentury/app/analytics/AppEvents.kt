package com.accentury.app.analytics

import android.util.Log
import com.accentury.app.share.ShareChannel

private const val TAG = "AppEvents"

/**
 * 앱 안 익명 이벤트 창구 (FR-SH-06, KAN-33 선행 seam).
 *
 * 웹의 `analytics/track.ts`와 같은 자리다 — 화면이 "이 일이 일어났다"고 말할 창구 하나를
 * 만들어 두는 것까지가 여기의 일이고, **전송(Firebase)은 KAN-33이 이 인터페이스 뒤에 붙인다.**
 * 그렇게 나눈 이유도 웹과 같다: 계측 도구가 붙을 때 화면 코드를 한 줄도 건드리지 않기
 * 위해서다. 세는 사건은 그대로고 보내는 방법만 바뀐다.
 *
 * 앱 안 이벤트를 웹이 아니라 네이티브가 보내는 것이 KAN-33의 결정이다 — 같은 사건이 두 경로로
 * 두 번 세어지면 안 되므로, WebView 안의 [track]은 웹 단독 실행에서만 돈다 (`App.tsx`).
 *
 * ## 익명 규칙
 *
 * 세션 id·세션 토큰·점수·등급 코드는 파라미터에 싣지 않는다. 하나라도 섞이면 "익명 계측"이라는
 * 전제가 깨지고, 그 값들이 계측 서버에 남을 이유도 없다. 공유 이벤트가 싣는 것은 채널뿐이다.
 */
fun interface EventSink {
    /**
     * 이벤트 하나를 흘려보낸다. **절대 던지지 않는다** — 호출자는 전부 사용자 흐름의 한복판이라
     * (공유 버튼 탭, 카톡 전환) 계측값 하나 때문에 그 흐름이 끊기면 안 된다. 계측은 실패해도
     * 되는 일이고 나머지는 아니다 (`track.ts`와 같은 판단이다).
     *
     * @param name GA4 스타일 snake_case 이벤트명 ([ShareEvents] 참고)
     * @param params 이벤트에 실을 값. 익명 규칙을 지키는 값만 넣는다
     */
    fun log(name: String, params: Map<String, String>)
}

/**
 * 파라미터 없는 이벤트. 기본값을 [EventSink.log]에 직접 달지 못해 확장으로 뺐다 — `fun interface`의
 * 추상 메서드에는 기본값을 줄 수 없다(Kotlin 제약).
 *
 * 인터페이스를 SAM으로 남기는 편을 골랐다: KAN-33이 Firebase sink를 람다 하나로 끼울 수 있어야
 * 하고, 테스트도 기록용 sink를 람다로 만든다.
 */
fun EventSink.log(name: String) = log(name, emptyMap())

/**
 * 태그 없는 빌드(지금)의 기본값 — Logcat에만 남긴다.
 *
 * 웹에서 큐(`window.dataLayer`)가 없을 때 [track]이 아무 일도 하지 않는 것과 같은 자리다.
 * 개발에서 이벤트가 실제로 도는지 눈으로 확인할 유일한 통로이기도 하다.
 */
object LogcatEventSink : EventSink {
    override fun log(name: String, params: Map<String, String>) {
        Log.d(TAG, "$name $params")
    }
}

/**
 * 결과 공유 이벤트 (FR-SH-06). 이름은 KAN-33이 Firebase에 심을 이벤트명과 같다.
 *
 * ## 여기서 세는 것의 한계 — "전송 완료"는 셀 수 없다
 *
 * [LAUNCHED]는 **카톡(또는 공유 시트)을 띄우는 데 성공했다**까지다. 카카오 SDK는 카톡 앱으로
 * 넘긴 뒤 사용자가 실제로 보냈는지를 돌려주지 않고, OS 공유 시트도 마찬가지다 — 우리 앱은
 * 전환 시점에 백그라운드로 내려가므로 그 뒤를 관측할 방법이 클라이언트에는 없다.
 *
 * 실제 전송 수는 카카오 개발자 콘솔의 **공유 웹훅(서버 콜백)** 으로만 알 수 있다 — 서버가 받는
 * 값이라 BE 후속 작업이다. 그때까지 [TAPPED]와 [LAUNCHED]의 차이가 말해 주는 것은 "눌렀는데
 * 아무 통로도 열리지 않은 비율"까지다.
 */
object ShareEvents {
    /** 결과 화면의 [친구에게 공유하기]를 눌렀다 (웹의 `share_clicked`에 대응하는 앱 안 사건) */
    const val TAPPED = "share_tapped"

    /** 공유 화면을 실제로 띄웠다. 위 한계 참고 — 보냈다는 뜻이 아니다 */
    const val LAUNCHED = "share_launched"

    /** [LAUNCHED]의 유일한 파라미터. 어느 통로가 얼마나 쓰이는지가 카카오 경로의 값을 판단할 근거다 */
    const val PARAM_CHANNEL = "channel"
}

/**
 * 공유 통로를 계측 파라미터 값으로 바꾼다. 순수 함수라 조합을 테스트로 못박는다.
 *
 * enum 이름(`KAKAO`)을 그대로 쓰지 않는 이유: 계측 값은 집계 축이라 한 번 쌓이면 이름을 바꿀 수
 * 없는데, enum 상수는 코드 사정으로 언제든 바뀔 수 있는 이름이다. 둘을 여기서 끊어 두면
 * 리팩터링이 지난 집계와 새 집계를 갈라놓지 않는다. 값 표기는 이벤트명과 같은 snake_case다.
 */
fun channelParam(channel: ShareChannel): String = when (channel) {
    ShareChannel.KAKAO -> "kakao"
    ShareChannel.SYSTEM_SHEET -> "system_sheet"
}
