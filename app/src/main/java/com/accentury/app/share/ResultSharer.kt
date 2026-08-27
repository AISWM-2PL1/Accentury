package com.accentury.app.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import com.accentury.app.BuildConfig
import com.accentury.app.bridge.SharePayload
import com.kakao.sdk.share.ShareClient
import com.kakao.sdk.template.model.Button
import com.kakao.sdk.template.model.Content
import com.kakao.sdk.template.model.FeedTemplate
import com.kakao.sdk.template.model.Link

private const val TAG = "ResultSharer"

/** 카드 버튼 문구. 수신자가 누르면 자기 테스트가 열린다 — 남의 결과를 보는 링크가 아니다 (KAN-30). */
private const val SHARE_BUTTON_TITLE = "나도 테스트하기"

/**
 * 결과가 나가는 통로 (KAN-30).
 *
 * 티켓이 요구한 폴백은 [SYSTEM_SHEET]다 — 카카오 문서가 권하는 WebSharerClient(브라우저로 카카오
 * 공유 페이지를 여는 방식)를 쓰지 않는 이유는 두 가지다. 카톡이 없는 사용자에게 카톡 웹 공유를
 * 들이미는 건 답이 아니고(그 사람이 쓰는 메신저로 보내야 한다), CustomTabs 의존성이 따라붙는다.
 */
enum class ShareChannel { KAKAO, SYSTEM_SHEET }

/**
 * 어느 통로로 갈지. 두 조건이 모두 참일 때만 카카오다 — 순수 함수라 조합을 테스트로 못박는다.
 *
 * @param kakaoEnabled 카카오 앱 키가 주입돼 SDK가 초기화됐는가 (AccenturyApplication)
 * @param talkAvailable 카톡이 깔려 있고 공유를 받을 수 있는가 (ShareClient.isKakaoTalkSharingAvailable)
 */
fun chooseShareChannel(kakaoEnabled: Boolean, talkAvailable: Boolean): ShareChannel =
    if (kakaoEnabled && talkAvailable) ShareChannel.KAKAO else ShareChannel.SYSTEM_SHEET

/**
 * 카카오 피드 템플릿 조립 (KAN-30). 순수 함수 — 값이 payload에서 어떻게 옮겨 붙는지가 이 함수의 전부다.
 *
 * description을 비운다: 피드 카드의 부제 자리인데 여기 넣을 값이 점수뿐이라 아예 두지 않는다.
 * imageWidth/imageHeight도 지정하지 않는다 — 카드 이미지 규격이 아직 확정 전(디자인 자산 대기)이라,
 * 지금 숫자를 박으면 실제 자산과 어긋난 비율로 잘려 나온다. 값이 없으면 카카오가 이미지 원본
 * 비율대로 그린다.
 *
 * 링크와 버튼이 같은 URL을 쓴다: 카드 어디를 눌러도 도착지가 같아야 한다 (카드 본문 탭과 버튼 탭이
 * 다른 곳으로 가면 캠페인 유입 집계도 갈린다).
 */
fun buildFeedTemplate(payload: SharePayload): FeedTemplate {
    val link = Link(webUrl = payload.webTestUrl, mobileWebUrl = payload.webTestUrl)
    return FeedTemplate(
        content = Content(
            title = payload.text,
            imageUrl = payload.imageUrl,
            link = link,
        ),
        buttons = listOf(Button(title = SHARE_BUTTON_TITLE, link = link)),
    )
}

/**
 * OS 공유 시트에 실을 본문. [buildSystemShareIntent]에서 떼어낸 이유는 테스트다 — Intent는
 * 유닛 테스트에서 android.jar 스텁이라(`isReturnDefaultValues`) getter가 값을 돌려주지 않아
 * 조립 결과를 확인할 수 없다. 문자열 조립만 여기로 빼면 그 부분은 JVM에서 검증된다.
 *
 * 문구와 링크를 줄바꿈으로 잇는다: 시트로 갈 상대는 문자·인스타 DM처럼 미리보기 카드가 없는 곳도
 * 있어서, 링크가 본문 안에 글자로 들어 있어야 한다.
 */
fun systemShareText(payload: SharePayload): String = "${payload.text}\n${payload.webTestUrl}"

/**
 * OS 공유 시트 인텐트 (KAN-30 폴백). text/plain인 이유: imageUrl은 우리 서버의 원격 URL이라
 * EXTRA_STREAM으로 넘길 수 있는 로컬 content:// URI가 아니다 — 이미지를 붙이려면 먼저 내려받아
 * FileProvider로 내보내야 하는데, 링크 미리보기를 지원하는 앱이면 그 URL로 카드를 알아서 그린다.
 */
fun buildSystemShareIntent(payload: SharePayload): Intent {
    val send = Intent(Intent.ACTION_SEND)
    send.type = "text/plain"
    send.putExtra(Intent.EXTRA_TEXT, systemShareText(payload))
    // 기본 앱을 기억시키지 않는다(createChooser) - 공유 상대는 매번 달라진다.
    return Intent.createChooser(send, null)
}

/**
 * 결과 공유 실행 (KAN-30). 결정은 위의 순수 함수들이 하고, 여기는 부수효과의 순서만 맡는다.
 *
 * 협력자를 전부 생성자로 받는 이유는 테스트다 — 카카오 SDK도 startActivity도 JVM에서 돌지 않는데,
 * 정작 검증하고 싶은 건 "성공하면 한 번 띄운다 / 실패하면 시트로 넘어간다"는 순서 자체다.
 * 프로덕션 결선은 [forApp] 한 곳에 모여 있다.
 *
 * **모든 호출이 메인 스레드라고 전제한다.** 진입점이 브리지의 shareResult 하나뿐이고 그쪽이
 * postToMain을 거치므로(AccenturyBridge) 별도 동기화를 두지 않는다.
 *
 * **카카오 SDK 호출은 던질 수 있다고 전제한다.** SDK가 초기화되지 않았거나 내부에서 깨지면
 * `isKakaoTalkSharingAvailable`·`shareDefault`가 콜백이 아니라 동기 예외로 끝나는데, 그게 밖으로
 * 새면 결과 화면을 보던 사용자의 앱이 죽는다. 두 호출 모두 시트 폴백으로 받는다.
 *
 * @param kakaoEnabled 카카오 앱 키가 있는가. false면 [isTalkAvailable]·[shareViaKakao]는 불리지 않는다
 * @param isTalkAvailable 카톡 설치 여부 조회. 던지면 "카톡 없음"으로 읽어 시트로 간다
 * @param shareViaKakao 카카오에 템플릿을 넘기고 실행할 인텐트를 돌려받는다. 결과는 (intent, error) 쌍이다.
 *   콜백은 동기로 불릴 수 있고, 호출 자체가 던져도 시트로 폴백한다 (둘이 겹쳐도 시트는 한 번뿐)
 * @param launch 인텐트 실행 (프로덕션: Activity.startActivity)
 * @param onLaunched 실제로 띄운 통로. 3단계에서 계측이 여기 물렸다 (`analytics/AppEvents.kt`의
 *   `share_launched`) — 폴백이 얼마나 도는지를 모르면 카카오 경로의 값을 판단할 수 없다.
 *   띄우지 못한 경우([launchOrIgnore]가 삼킨 경우)에는 부르지 않는다: 열리지 않은 화면을
 *   "띄웠다"로 세면 통로 하나가 통째로 막힌 기기가 집계에서 정상으로 보인다
 * @param buildSheetIntent 시트 인텐트 조립. 기본값이 곧 프로덕션 구현이라 결선에서 넘길 일은 없고,
 *   테스트만 갈아끼운다 — [Intent.createChooser]는 유닛 테스트에서 android.jar 스텁이라 null을
 *   돌려주고, 그러면 시트 경로가 인텐트를 만드는 자리에서 죽어 정작 검증하려던 폴백 순서에
 *   닿지 못한다 (`isReturnDefaultValues`)
 */
class ResultSharer(
    private val kakaoEnabled: Boolean,
    private val isTalkAvailable: () -> Boolean,
    private val shareViaKakao: (FeedTemplate, (Intent?, Throwable?) -> Unit) -> Unit,
    private val launch: (Intent) -> Unit,
    private val onLaunched: (ShareChannel) -> Unit = {},
    private val buildSheetIntent: (SharePayload) -> Intent = ::buildSystemShareIntent,
) {
    fun share(payload: SharePayload) {
        val channel = chooseShareChannel(
            kakaoEnabled = kakaoEnabled,
            // 키가 없으면 SDK가 초기화되지 않았으므로 조회 자체를 하지 않는다 (미초기화 접근은 예외다).
            talkAvailable = kakaoEnabled && talkAvailableOrFalse(),
        )
        when (channel) {
            ShareChannel.KAKAO -> shareViaKakaoOrSheet(payload)
            ShareChannel.SYSTEM_SHEET -> launchSheet(payload)
        }
    }

    /**
     * 카톡 설치 조회. 예외는 "카톡 없음"으로 읽는다 — 조회에 실패했다는 건 카카오 경로를 믿을 수
     * 없다는 뜻이고(SDK 초기화 상태 불일치, 카카오 내부 오류), 그 상태로 카카오에 템플릿을
     * 넘겨 봐야 같은 자리에서 다시 깨진다. 여기서 false를 돌려주면 [chooseShareChannel]이
     * 시트로 보낸다.
     */
    private fun talkAvailableOrFalse(): Boolean = try {
        isTalkAvailable()
    } catch (e: Exception) {
        Log.w(TAG, "카톡 설치 조회 실패 - 카톡 없음으로 본다", e)
        false
    }

    /**
     * 카카오 경로. 실패의 모양이 둘이라 폴백도 두 자리에 있다 — 콜백으로 오는 실패(템플릿 거부,
     * 카카오 서버 오류)와 호출 자체가 던지는 동기 예외(SDK 미초기화 [IllegalStateException],
     * 카카오 내부 오류)다. 후자를 감싸지 않으면 결과 화면을 보던 사용자의 앱이 그대로 죽는다.
     *
     * [handled]는 그 둘이 겹칠 때를 막는다: 카카오 SDK는 콜백을 동기로 부를 수 있어서, 콜백이
     * 이미 시트를 띄운 뒤 같은 호출이 예외로 끝나는 순서가 가능하다. 가드가 없으면 사용자가
     * 공유 시트를 두 번 보게 된다.
     */
    private fun shareViaKakaoOrSheet(payload: SharePayload) {
        var handled = false
        try {
            shareViaKakao(buildFeedTemplate(payload)) { intent, error ->
                if (!handled) {
                    handled = true
                    if (intent == null) {
                        /*
                         * 카카오 쪽 실패는 공유의 끝이 아니라 통로 하나가 막힌 것이다 (템플릿 거부,
                         * 카카오 서버 오류, 앱 키 설정 불일치 등). 사용자가 누른 건 "공유"지 "카톡 공유"가
                         * 아니므로, 아무 일도 일어나지 않은 화면을 보여주는 대신 시트로 넘긴다.
                         */
                        Log.w(TAG, "카카오 공유 실패 - 시스템 공유 시트로 폴백", error)
                        launchSheet(payload)
                    } else {
                        launchOrIgnore(intent, ShareChannel.KAKAO)
                    }
                }
            }
        } catch (e: Exception) {
            if (handled) return
            handled = true
            Log.w(TAG, "카카오 공유 호출이 예외로 끝났다 - 시스템 공유 시트로 폴백", e)
            launchSheet(payload)
        }
    }

    private fun launchSheet(payload: SharePayload) {
        launchOrIgnore(buildSheetIntent(payload), ShareChannel.SYSTEM_SHEET)
    }

    /**
     * 받을 앱이 하나도 없는 기기에서 [launch]가 [ActivityNotFoundException]을 던진다. 이걸
     * 밖으로 흘리면 결과 화면을 보던 사용자의 앱이 죽는다 — 공유 하나 못 한 것과 바꿀 수 없다.
     */
    private fun launchOrIgnore(intent: Intent, channel: ShareChannel) {
        try {
            launch(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "공유를 받을 앱이 없다", e)
            return
        }
        onLaunched(channel)
    }

    companion object {
        /**
         * 프로덕션 결선. [Activity] 컨텍스트를 받는 이유: 공유 시트와 카톡 전환은 지금 화면 위에
         * 올라와야 하는 UI다 — application 컨텍스트로 띄우면 NEW_TASK 플래그가 필요하고, 그러면
         * 공유를 끝낸 뒤 우리 결과 화면이 아니라 별도 태스크로 돌아간다.
         *
         * @param onLaunched 실제로 띄운 통로를 받는다. 기본값이 빈 함수인 이유는 이 결선의 관심사가
         *   공유이지 계측이 아니기 때문이다 — 계측을 붙이는 곳은 화면(MainActivity)이다 (KAN-30 3단계)
         */
        fun forApp(activity: Activity, onLaunched: (ShareChannel) -> Unit = {}): ResultSharer = ResultSharer(
            kakaoEnabled = BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank(),
            isTalkAvailable = { ShareClient.instance.isKakaoTalkSharingAvailable(activity) },
            shareViaKakao = { template, onResult ->
                ShareClient.instance.shareDefault(activity, template) { result, error ->
                    onResult(result?.intent, error)
                }
            },
            launch = { intent -> activity.startActivity(intent) },
            onLaunched = onLaunched,
        )
    }
}
