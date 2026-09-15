package com.accentury.app.analytics

import android.util.Log
import com.accentury.app.share.ShareChannel

private const val TAG = "AppEvents"

/**
 * 앱 안 익명 이벤트 창구 (FR-SH-06, KAN-33).
 *
 * 웹의 `analytics/track.ts`와 같은 자리다 — 화면이 "이 일이 일어났다"고 말할 창구 하나이고,
 * 실제 전송(Firebase)은 이 인터페이스 뒤에 있다 ([FirebaseEventSink]). 그렇게 나눈 이유는 웹과
 * 같다: 보내는 방법이 바뀌어도 화면 코드는 그대로다. 설정이 없는 빌드에서 [LogcatEventSink]로
 * 내려가는 것도 화면이 모르는 사정이다.
 *
 * 앱 안 이벤트를 웹이 아니라 네이티브가 보내는 것이 KAN-33의 결정이다 — 같은 사건이 두 경로로
 * 두 번 세어지면 안 되므로, WebView 안의 [track]은 웹 단독 실행에서만 돈다 (`App.tsx`).
 * 앱 안에서 웹이 세는 사건은 브리지를 건너 이 창구로 들어온다 (`AccenturyBridge.logEvent`).
 *
 * ## 익명 규칙
 *
 * 세션 id·세션 토큰·문항 내용·점수 원값은 파라미터에 싣지 않는다. 하나라도 섞이면 "익명 계측"이라는
 * 전제가 깨지고, 그 값들이 계측 서버에 남을 이유도 없다. 공유 이벤트가 싣는 것은 채널뿐이다.
 *
 * **등급 코드는 예외다.** `tier_assigned` 하나에만 `tier_code`와 종합 점수의 10점 단위 버킷이
 * 실린다 (FR-AN-09 익명 집계 카운터 범위, 정본은 `web/src/analytics/events.ts`). 등급은 5개뿐인
 * 집계 축이고 점수는 10점 눈금으로 뭉개져 개인을 특정할 수 없다 — 이 둘이 없으면 KAN-21의
 * "등급 분포 편향" 트리거를 판단할 계기판이 아예 없다. 그 이벤트를 보내는 것은 결과 화면(웹)이라
 * 앱에서는 브리지를 건너 들어온다.
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
    fun log(name: String, params: Map<String, EventParam>)

    /** [EventSink.create]를 걸어 둘 자리. 판정에 Firebase가 필요해 구현은 FirebaseEventSink.kt에 있다. */
    companion object
}

/**
 * 파라미터 값 하나. GA4가 받는 세 가지(문자열·정수·실수)를 그대로 옮긴 타입이다.
 *
 * 문자열 하나로 뭉치지 않는 이유가 이 티켓의 AC를 직접 건드린다. 숫자를 문자열로 실으면 GA4가
 * 그 값을 **차원(dimension)** 으로 잡아 평균·백분위를 낼 수 없다 — "대기 화면 체류 시간의
 * 평균·P95를 대시보드에서 바로 확인한다"가 그 자리에서 깨진다. 숫자로 실어야 측정항목(metric)이
 * 되고, `duration_ms`·`elapsed_ms`·`count`가 전부 그 대상이다 (`web/src/analytics/events.ts`).
 *
 * [Count]와 [Amount]를 나누는 것은 Bundle에 putLong·putDouble 중 무엇을 부를지의 문제다. 전부
 * 실수로 보내면 `item_seq`·`pending_item_count` 같은 개수가 대시보드에 3.0으로 찍힌다.
 */
sealed interface EventParam {
    /** 집계 축이 되는 코드값 (`channel`, `reason`, `tier_code`) */
    data class Text(val value: String) : EventParam

    /** 정수 — 개수·순번·밀리초 */
    data class Count(val value: Long) : EventParam

    /** 실수 — 지금 스키마에는 없지만 웹이 보낸 JSON에 소수가 오면 여기로 온다 */
    data class Amount(val value: Double) : EventParam
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
 * 설정(google-services.json)이 없는 빌드의 기본값 — Logcat에만 남긴다.
 *
 * 웹에서 태그가 없을 때 [track]이 아무 데도 보내지 않는 것과 같은 자리다. 개발에서 이벤트가
 * 실제로 도는지 눈으로 확인할 유일한 통로이기도 하다.
 */
object LogcatEventSink : EventSink {
    override fun log(name: String, params: Map<String, EventParam>) {
        Log.d(TAG, "$name $params")
    }
}

/**
 * 결과 공유 이벤트 (FR-SH-06). 이름은 KAN-33이 Firebase에 심을 이벤트명과 같다.
 *
 * ## 탭은 네이티브가 세지 않는다
 *
 * [친구에게 공유하기] 탭을 세는 이름은 웹의 `share_clicked` 하나다. 웹은 실행을 가리지 않고 그
 * 탭을 세고, 앱 안에서는 그 이벤트가 브리지 `logEvent`를 타고 이 창구로 들어온다 — 네이티브가
 * 같은 탭에 이름을 하나 더 붙이면 같은 사람의 같은 탭이 앱과 웹에서 다른 축으로 갈려, 공유
 * 퍼널을 보려면 두 이름을 합집합으로 세야 한다. 그래서 네이티브가 세는 것은 **통로가 실제로
 * 열렸다는 결과 사건([LAUNCHED])뿐**이다.
 *
 * 알 수 있는 것은 그대로다 — `share_clicked` 수와 [LAUNCHED] 수의 차이가 곧 "눌렀는데 아무
 * 통로도 열리지 않은 비율"이고, 이름만 셋에서 둘로 줄었다.
 *
 * ## 여기서 세는 것의 한계 — "전송 완료"는 셀 수 없다
 *
 * [LAUNCHED]는 **카톡(또는 공유 시트)을 띄우는 데 성공했다**까지다. 카카오 SDK는 카톡 앱으로
 * 넘긴 뒤 사용자가 실제로 보냈는지를 돌려주지 않고, OS 공유 시트도 마찬가지다 — 우리 앱은
 * 전환 시점에 백그라운드로 내려가므로 그 뒤를 관측할 방법이 클라이언트에는 없다.
 *
 * 실제 전송 수는 카카오 개발자 콘솔의 **공유 웹훅(서버 콜백)** 으로만 알 수 있다 — 서버가 받는
 * 값이라 BE 후속 작업이다.
 */
object ShareEvents {
    /** 공유 화면을 실제로 띄웠다. 위 한계 참고 — 보냈다는 뜻이 아니고, 못 열었으면 부르지 않는다 */
    const val LAUNCHED = "share_launched"

    /** [LAUNCHED]의 유일한 파라미터. 어느 통로가 얼마나 쓰이는지가 카카오 경로의 값을 판단할 근거다 */
    const val PARAM_CHANNEL = "channel"
}

/**
 * 네이티브 녹음 화면이 세는 이벤트 (KAN-33).
 *
 * 이름·파라미터·값 표기는 `web/src/analytics/events.ts`의 `recording_retake`와 **정확히** 같다.
 * 웹 녹음기(브라우저 단독 실행)와 네이티브 녹음 화면은 사람이 하는 같은 일이라 한 지표로 쌓여야
 * 하고, 어긋나면 같은 사건이 이름이 다른 두 축으로 갈린다.
 *
 * 사유는 [REASON_USER] 하나뿐이다. 나머지 둘(QUALITY·FAILED)은 서버가 되돌려보낸 문항을 다시
 * 녹음하는 경우인데, 그 자리는 웹(분석 대기 화면)이 소유하고 이미 거기서 센다 —
 * `AnalysisWaitingScreen`이 사유를 아는 유일한 곳이라 네이티브가 다시 셀 이유도 방법도 없다.
 */
object RecordingEvents {
    /** 녹음을 마친 뒤 [재녹음]으로 되돌아갔다 */
    const val RETAKE = "recording_retake"

    /** 사람이 읽는 1-기반 문항 번호 (정의의 `seq`가 아니다 — VoiceItemStart.itemNumber) */
    const val PARAM_ITEM_SEQ = "item_seq"

    /** 재녹음 사유. 값 표기는 이벤트명과 달리 대문자다 (웹 `RetakeReason`이 그렇다) */
    const val PARAM_REASON = "reason"

    /** 아무 문제 없이 사용자가 다시 읽기로 한 것 */
    const val REASON_USER = "USER"
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
