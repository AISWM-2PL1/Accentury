package com.accentury.app.analytics

import com.accentury.app.share.ShareChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppEventsTest {

    @Test
    fun `네이티브가 세는 공유 이벤트는 실행 하나뿐이다`() {
        /*
         * 탭은 웹의 `share_clicked`가 세고 앱 안에서는 브리지로 넘어온다. 네이티브가 같은 탭에
         * 이름을 하나 더 붙이면 앱과 웹의 같은 사건이 다른 축으로 갈린다 — 그 이름이 다시 생기면
         * 이 테스트가 아니라 컴파일이 먼저 막히지만, 남는 하나의 이름은 여기서 못박는다.
         */
        assertEquals("share_launched", ShareEvents.LAUNCHED)
        assertEquals("channel", ShareEvents.PARAM_CHANNEL)
    }

    @Test
    fun `공유 통로는 집계에 쓸 snake_case 값으로 나간다`() {
        // enum 이름이 아니라 이 문자열이 집계 축이다 — 리팩터링으로 바뀌면 지난 집계와 갈라진다.
        assertEquals("kakao", channelParam(ShareChannel.KAKAO))
        assertEquals("system_sheet", channelParam(ShareChannel.SYSTEM_SHEET))
    }

    @Test
    fun `기본 sink는 무슨 값을 받아도 던지지 않는다`() {
        /*
         * 호출자가 공유 버튼 탭과 카톡 전환 직후라, 계측값 하나 때문에 그 흐름이 끊기면 안 된다.
         * 유닛 테스트의 android.util.Log는 android.jar 스텁이라 기본값만 돌려준다
         * (`isReturnDefaultValues`) — 여기서 확인하는 것은 로그 내용이 아니라 **밖으로 새는 것이
         * 없다**는 사실이다.
         */
        LogcatEventSink.log(ShareEvents.LAUNCHED)
        LogcatEventSink.log(
            ShareEvents.LAUNCHED,
            mapOf(ShareEvents.PARAM_CHANNEL to EventParam.Text(channelParam(ShareChannel.KAKAO))),
        )
    }

    @Test
    fun `설정이 없으면 Firebase sink가 아니라 Logcat 폴백이 선택된다`() {
        /*
         * google-services.json이 없는 것이 정상 상태다 (app/build.gradle.kts). 그때 FirebaseApp이
         * 초기화되지 않아 계측기를 만들 수 없는데, 그 사실을 판정하는 자리는 한 곳이어야 한다 —
         * 화면마다 다시 물으면 어느 화면 하나가 묻는 것을 잊고 초기화되지 않은 SDK를 부른다.
         */
        assertSame(LogcatEventSink, eventSinkFor(null))
    }

    @Test
    fun `재녹음 이벤트의 이름과 값은 웹 스키마와 같다`() {
        // 웹 녹음기와 네이티브 녹음 화면은 같은 사건이라 한 지표로 쌓여야 한다
        // (web/src/analytics/events.ts). 이름이 갈리면 문항 난이도를 두 표본으로 나눠 보게 된다.
        assertEquals("recording_retake", RecordingEvents.RETAKE)
        assertEquals("item_seq", RecordingEvents.PARAM_ITEM_SEQ)
        assertEquals("reason", RecordingEvents.PARAM_REASON)
        // 사유만 대문자다 — 웹 `RetakeReason`의 표기를 그대로 따른다.
        assertEquals("USER", RecordingEvents.REASON_USER)
    }
}
