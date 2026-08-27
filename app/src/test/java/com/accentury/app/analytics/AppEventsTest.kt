package com.accentury.app.analytics

import com.accentury.app.share.ShareChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class AppEventsTest {

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
        LogcatEventSink.log(ShareEvents.TAPPED)
        LogcatEventSink.log(
            ShareEvents.LAUNCHED,
            mapOf(ShareEvents.PARAM_CHANNEL to channelParam(ShareChannel.KAKAO)),
        )
    }
}
