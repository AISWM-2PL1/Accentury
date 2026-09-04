package com.accentury.app.share

import android.content.ActivityNotFoundException
import android.content.Intent
import com.accentury.app.bridge.SharePayload
import com.kakao.sdk.template.model.FeedTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultSharerTest {

    private val payload = SharePayload(
        imageUrl = "https://cdn.accentury.app/share/grade-a.png",
        text = "내 사투리 등급은 '경상도 원어민'!",
        webTestUrl = "https://accentury.app/?utm_source=kakao",
    )

    @Test
    fun `카카오는 앱 키와 카톡이 둘 다 있을 때만 쓴다`() {
        assertEquals(
            ShareChannel.KAKAO,
            chooseShareChannel(kakaoEnabled = true, talkAvailable = true),
        )
        // 키가 없는 로컬·CI 빌드. 카카오 SDK가 초기화되지 않은 상태라 시트뿐이다.
        assertEquals(
            ShareChannel.SYSTEM_SHEET,
            chooseShareChannel(kakaoEnabled = false, talkAvailable = true),
        )
        // 카톡 미설치. 티켓이 요구한 폴백이 이 칸이다.
        assertEquals(
            ShareChannel.SYSTEM_SHEET,
            chooseShareChannel(kakaoEnabled = true, talkAvailable = false),
        )
        assertEquals(
            ShareChannel.SYSTEM_SHEET,
            chooseShareChannel(kakaoEnabled = false, talkAvailable = false),
        )
    }

    @Test
    fun `피드 템플릿은 payload 값을 그대로 싣는다`() {
        val template = buildFeedTemplate(payload)

        assertEquals(payload.text, template.content.title)
        assertEquals(payload.imageUrl, template.content.imageUrl)
        assertEquals(payload.webTestUrl, template.content.link.webUrl)
        assertEquals(payload.webTestUrl, template.content.link.mobileWebUrl)
    }

    @Test
    fun `카드 버튼은 수신자를 자기 테스트로 보낸다`() {
        // 카드 본문과 버튼의 도착지가 갈리면 캠페인 유입 집계도 갈린다 - 같은 URL이어야 한다.
        val button = buildFeedTemplate(payload).buttons?.single()

        assertEquals("나도 테스트하기", button?.title)
        assertEquals(payload.webTestUrl, button?.link?.webUrl)
        assertEquals(payload.webTestUrl, button?.link?.mobileWebUrl)
    }

    @Test
    fun `카드에는 점수 자리가 없다`() {
        // 수신자는 남의 결과를 열어 보는 게 아니라 자기 테스트를 새로 응시한다 (KAN-30 요구).
        // description을 비워 두는 결정이 여기서 회귀 방지 대상이 된다.
        val content = buildFeedTemplate(payload).content

        assertNull(content.description)
        // 자산 규격 확정 전이라 크기를 박지 않는다 - 값이 없어야 원본 비율대로 그려진다.
        assertNull(content.imageWidth)
        assertNull(content.imageHeight)
    }

    @Test
    fun `공유 시트 본문은 문구와 링크를 함께 싣는다`() {
        // 미리보기 카드가 없는 앱(문자 등)으로도 가므로 링크가 본문 안에 글자로 있어야 한다.
        assertEquals(
            "내 사투리 등급은 '경상도 원어민'!\nhttps://accentury.app/?utm_source=kakao",
            systemShareText(payload),
        )
    }

    /**
     * [ResultSharer]의 협력자를 전부 기록으로 바꾼 하네스. Intent는 유닛 테스트에서 android.jar
     * 스텁이라 내용을 확인할 수 없으므로(`isReturnDefaultValues`) **몇 번, 어느 통로로** 띄웠는지만 본다 —
     * 인텐트 내용은 [systemShareText]·[buildFeedTemplate] 쪽에서 순수 함수로 검증한다.
     */
    private class Harness(
        kakaoEnabled: Boolean = true,
        talkAvailable: Boolean = true,
        private val talkError: Throwable? = null,
        private val kakaoResult: (FeedTemplate) -> Pair<Intent?, Throwable?> = { Intent() to null },
        private val kakaoError: Throwable? = null,
        private val callbackBeforeKakaoError: Boolean = false,
        private val throwOnLaunch: Boolean = false,
    ) {
        var talkChecks = 0
        var kakaoCalls = 0
        var launches = 0
        val launched = mutableListOf<ShareChannel>()
        var lastTemplate: FeedTemplate? = null

        val sharer = ResultSharer(
            kakaoEnabled = kakaoEnabled,
            isTalkAvailable = {
                talkChecks++
                if (talkError != null) throw talkError
                talkAvailable
            },
            shareViaKakao = { template, onResult ->
                kakaoCalls++
                lastTemplate = template
                if (kakaoError == null || callbackBeforeKakaoError) {
                    val (intent, error) = kakaoResult(template)
                    onResult(intent, error)
                }
                if (kakaoError != null) throw kakaoError
            },
            launch = {
                launches++
                if (throwOnLaunch) throw ActivityNotFoundException("공유를 받을 앱이 없다")
            },
            onLaunched = { launched += it },
            // 프로덕션 조립(Intent.createChooser)은 JVM 스텁에서 null이라 여기서 갈아끼운다.
            // 실제 본문은 systemShareText 테스트가 지킨다.
            buildSheetIntent = { Intent() },
        )
    }

    @Test
    fun `카카오가 인텐트를 주면 그것만 띄운다`() {
        val h = Harness()

        h.sharer.share(payload)

        assertEquals(1, h.kakaoCalls)
        assertEquals(1, h.launches)
        assertEquals(listOf(ShareChannel.KAKAO), h.launched)
    }

    @Test
    fun `카카오에 넘기는 템플릿은 payload로 조립한 그것이다`() {
        val h = Harness()

        h.sharer.share(payload)

        assertEquals(payload.text, h.lastTemplate?.content?.title)
    }

    @Test
    fun `카카오가 실패하면 공유를 접지 않고 시트로 넘어간다`() {
        // 템플릿 거부·카카오 서버 오류·앱 키 설정 불일치. 사용자가 누른 건 "공유"지 "카톡 공유"가
        // 아니므로, 통로 하나가 막혔다고 아무 일도 일어나지 않으면 안 된다.
        val h = Harness(kakaoResult = { null to IllegalStateException("template rejected") })

        h.sharer.share(payload)

        assertEquals(1, h.kakaoCalls)
        assertEquals(1, h.launches)
        assertEquals(listOf(ShareChannel.SYSTEM_SHEET), h.launched)
    }

    @Test
    fun `오류 없이 인텐트만 비어 와도 시트로 넘어간다`() {
        val h = Harness(kakaoResult = { null to null })

        h.sharer.share(payload)

        assertEquals(listOf(ShareChannel.SYSTEM_SHEET), h.launched)
    }

    @Test
    fun `앱 키가 없으면 카카오를 건드리지도 않는다`() {
        // 미초기화 SDK 접근은 그 자체가 예외다 - 조회조차 하지 않아야 키 없는 빌드가 정상 동작한다.
        val h = Harness(kakaoEnabled = false)

        h.sharer.share(payload)

        assertEquals(0, h.talkChecks)
        assertEquals(0, h.kakaoCalls)
        assertEquals(listOf(ShareChannel.SYSTEM_SHEET), h.launched)
    }

    @Test
    fun `카톡이 없으면 카카오 공유를 부르지 않고 시트로 간다`() {
        val h = Harness(talkAvailable = false)

        h.sharer.share(payload)

        assertEquals(1, h.talkChecks)
        assertEquals(0, h.kakaoCalls)
        assertEquals(listOf(ShareChannel.SYSTEM_SHEET), h.launched)
    }

    @Test
    fun `공유를 받을 앱이 하나도 없어도 예외가 밖으로 새지 않는다`() {
        // 결과 화면을 보던 사용자의 앱이 죽는 것과 공유 한 번 못 하는 것을 바꿀 수 없다.
        val h = Harness(kakaoEnabled = false, throwOnLaunch = true)

        h.sharer.share(payload)

        assertEquals(1, h.launches)
        // 띄우지 못했으므로 계측도 울리지 않는다.
        assertTrue(h.launched.isEmpty())
    }

    @Test
    fun `카톡 설치 조회가 던지면 카카오를 건너뛰고 시트로 간다`() {
        // SDK 미초기화 등으로 조회가 예외로 끝나는 상황. 조회를 믿을 수 없으면 카카오에
        // 템플릿을 넘겨 봐야 같은 자리에서 다시 깨진다.
        val h = Harness(talkError = IllegalStateException("KakaoSdk is not initialized"))

        h.sharer.share(payload)

        assertEquals(1, h.talkChecks)
        assertEquals(0, h.kakaoCalls)
        assertEquals(1, h.launches)
        assertEquals(listOf(ShareChannel.SYSTEM_SHEET), h.launched)
    }

    @Test
    fun `카카오 공유 호출이 동기 예외로 끝나도 앱이 죽지 않고 시트로 간다`() {
        // 콜백조차 오지 않는 실패다. 예외를 밖으로 흘리면 결과 화면을 보던 사용자의 앱이 죽는다.
        val h = Harness(kakaoError = IllegalStateException("KakaoSdk is not initialized"))

        h.sharer.share(payload)

        assertEquals(1, h.kakaoCalls)
        assertEquals(1, h.launches)
        assertEquals(listOf(ShareChannel.SYSTEM_SHEET), h.launched)
    }

    @Test
    fun `콜백으로 이미 폴백한 뒤 예외가 와도 시트는 한 번만 뜬다`() {
        // 카카오 SDK는 콜백을 동기로 부를 수 있다 - 실패 콜백으로 시트를 띄운 직후 같은 호출이
        // 예외로 끝나는 순서가 가능하고, 가드가 없으면 사용자가 공유 시트를 두 번 본다.
        val h = Harness(
            kakaoResult = { null to IllegalStateException("template rejected") },
            kakaoError = RuntimeException("kakao internal"),
            callbackBeforeKakaoError = true,
        )

        h.sharer.share(payload)

        assertEquals(1, h.kakaoCalls)
        assertEquals(1, h.launches)
        assertEquals(listOf(ShareChannel.SYSTEM_SHEET), h.launched)
    }
}
