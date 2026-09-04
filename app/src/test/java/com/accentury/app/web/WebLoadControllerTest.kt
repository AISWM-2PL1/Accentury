package com.accentury.app.web

import org.junit.Assert.assertEquals
import org.junit.Test

class WebLoadControllerTest {

    @Test
    fun `초기 상태는 Loading이고 로드 완료 시 Ready가 된다`() {
        val controller = WebLoadController()
        assertEquals(WebLoadState.Loading, controller.state)
        controller.onPageFinished()
        assertEquals(WebLoadState.Ready, controller.state)
    }

    @Test
    fun `메인 프레임 오류는 실패 화면으로 보낸다`() {
        val controller = WebLoadController()
        controller.onMainFrameError()
        assertEquals(WebLoadState.Failed, controller.state)
    }

    @Test
    fun `오류 뒤에 오는 onPageFinished가 실패를 덮어쓰지 않는다 - 크롬 오류 페이지도 완료 콜백을 쏜다`() {
        val controller = WebLoadController()
        controller.onMainFrameError()
        controller.onPageFinished()
        assertEquals(WebLoadState.Failed, controller.state)
    }

    @Test
    fun `타임아웃은 Loading일 때만 실패로 판정한다`() {
        val loading = WebLoadController()
        loading.onTimeout()
        assertEquals(WebLoadState.Failed, loading.state)
    }

    @Test
    fun `로드가 이미 끝났으면 늦게 도착한 타임아웃은 무시한다`() {
        val controller = WebLoadController()
        controller.onPageFinished()
        controller.onTimeout()
        assertEquals(WebLoadState.Ready, controller.state)
    }

    @Test
    fun `로드 후 내비게이션 오류도 실패 화면으로 보낸다 - Ready에 머무르지 않는다`() {
        val controller = WebLoadController()
        controller.onPageFinished()
        controller.onMainFrameError()
        assertEquals(WebLoadState.Failed, controller.state)
    }

    @Test
    fun `같은 WebView에서 새 URL을 로드하면 다시 Loading이다 - 인트로 Ready를 이어받지 않는다`() {
        val controller = WebLoadController()
        controller.onPageFinished()
        controller.onNavigationStarted()
        assertEquals(WebLoadState.Loading, controller.state)
    }

    @Test
    fun `새 URL 로드도 완료 오류 타임아웃 판정을 처음부터 다시 받는다`() {
        val controller = WebLoadController()
        controller.onPageFinished()
        controller.onNavigationStarted()
        controller.onTimeout()
        assertEquals(WebLoadState.Failed, controller.state)
        // WebView를 새로 만들지 않았으므로 재생성 키는 그대로다.
        assertEquals(0, controller.attempt)
    }

    @Test
    fun `재시도는 attempt를 올리고 Loading으로 되돌린다 - WebView 재생성 키`() {
        val controller = WebLoadController()
        controller.onMainFrameError()
        assertEquals(0, controller.attempt)
        controller.retry()
        assertEquals(1, controller.attempt)
        assertEquals(WebLoadState.Loading, controller.state)
    }

    @Test
    fun `재시도 후에도 같은 전이 규칙이 적용된다`() {
        val controller = WebLoadController()
        controller.onTimeout()
        controller.retry()
        controller.onPageFinished()
        assertEquals(WebLoadState.Ready, controller.state)
        assertEquals(1, controller.attempt)
    }
}
