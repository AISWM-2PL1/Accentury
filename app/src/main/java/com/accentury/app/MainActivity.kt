package com.accentury.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.accentury.app.audio.AudioQuality
import com.accentury.app.audio.QualityStatus
import com.accentury.app.audio.WavWriter
import com.accentury.app.bridge.VoiceItemStart
import com.accentury.app.bridge.itemResultDeliveryJs
import com.accentury.app.bridge.retestFailedDeliveryJs
import com.accentury.app.bridge.retestFailurePayload
import com.accentury.app.permission.MicPermissionController
import com.accentury.app.permission.MicPermissionState
import com.accentury.app.recording.RecordingScreen
import com.accentury.app.audio.RecordingEngine
import com.accentury.app.audio.defaultPcmSource
import com.accentury.app.recording.RecordingViewModel
import com.accentury.app.recording.VoiceCheckScreen
import com.accentury.app.recording.VoiceCheckViewModel
import com.accentury.app.analytics.EventParam
import com.accentury.app.analytics.EventSink
import com.accentury.app.analytics.RecordingEvents
import com.accentury.app.analytics.ShareEvents
import com.accentury.app.analytics.crashIfRequested
import com.accentury.app.analytics.create
import com.accentury.app.analytics.log
import com.accentury.app.analytics.channelParam
import com.accentury.app.share.ResultSharer
import com.accentury.app.session.OkHttpSessionClient
import com.accentury.app.session.RetestOutcome
import com.accentury.app.session.SessionGateController
import com.accentury.app.session.SessionGateScreen
import com.accentury.app.testflow.TestFlowController
import com.accentury.app.testflow.continuesFrom
import com.accentury.app.testflow.TestFlowPhase
import com.accentury.app.ui.components.AccenturyButton
import com.accentury.app.ui.components.HeroIcon
import com.accentury.app.ui.theme.AccenturyTheme
import com.accentury.app.ui.theme.Radius
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.upload.UploadRequest
import com.accentury.app.upload.UploadState
import com.accentury.app.upload.UploadStatusBar
import com.accentury.app.upload.UploadViewModel
import com.accentury.app.web.AppLinkEntry
import com.accentury.app.web.TestEntry
import com.accentury.app.web.WebViewHost
import com.accentury.app.web.appLinkOrigins
import com.accentury.app.web.buildWebUrl
import com.accentury.app.web.parseAppLink
import com.accentury.app.web.webOrigin
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch


/*
 * 업로드가 뒷받침하지 않는 붙들기를 걷는 상한 (KAN-146).
 *
 * 업로드가 진행 중인 동안에는 시간으로 걷지 않는다 — 끝날 때까지 현재 문항 화면을 유지한다.
 * 진행 중이 아닌데도 화면이 붙들려 있다면 그건 곧 끝나야 할 짧은 창이거나(주입 완료 통지를 기다리는
 * 몇 십 ms) 영영 끝나지 않을 상태(프로세스 사망 복원으로 업로드가 메모리와 함께 사라진 경우)다.
 * 앞엣것은 이 상한이 오기 전에 스스로 풀리고, 뒤엣것은 이 상한만이 풀 수 있다.
 */
private const val ORPHANED_SUBMIT_TIMEOUT_MS = 2_000L

class MainActivity : ComponentActivity() {

    /**
     * App Link로 들어온 진입 (KAN-32 2단계). 링크가 실어 온 계측 코드를 화면 쪽으로 흘려보내는
     * 통로다 — Activity가 받는 Intent를 Compose가 볼 수 있는 상태로 바꾸는 것이 이 필드의 전부다.
     */
    private val appLink = MutableStateFlow<AppLinkEntry?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        /*
         * 스플래시 (KAN-178). `super.onCreate` **앞**이어야 한다 — 이 호출이 하는 일이
         * 창의 테마를 매니페스트의 Theme.Accentury.Starting에서 postSplashScreenTheme
         * (Theme.Accentury)로 갈아 끼우는 것이고, 창이 만들어진 뒤에 바꾸면 늦는다.
         * 유지 조건(setKeepOnScreenCondition)은 걸지 않는다 — 첫 화면이 웹뷰라 붙들 기준이
         * 애매하고, 붙들면 그만큼 사용자가 아무것도 못 하는 시간이 늘어난다.
         */
        installSplashScreen()
        super.onCreate(savedInstanceState)
        /*
         * 시스템 바를 **항상 밝은 배경용**으로 고정한다 (KAN-161 4단계).
         *
         * 인자 없는 `enableEdgeToEdge()`는 `SystemBarStyle.auto`라 night 리소스 설정을 보고
         * 아이콘 색을 정한다. 그런데 이 앱의 배경은 테마와 무관하게 늘 크림(#f3ecd9)이라
         * (Theme.kt의 다크 고정), 시스템 다크에서는 크림 위에 흰 시계·배터리가 얹혀 거의
         * 안 보였다. 화면이 뒤집히지 않는데 시스템 바만 뒤집힌 것이다.
         *
         * `light(...)`로 스타일 자체를 못 박고, 그 뒤 컨트롤러로 한 번 더 세운다. 스타일만
         * 주면 나중에 누가 `enableEdgeToEdge()`를 인자 없는 꼴로 되돌렸을 때 조용히 원래
         * 증상으로 돌아가는데, 컨트롤러 쪽은 그 호출 뒤에 실행돼 마지막 말이 된다.
         * 프레임이 그려지기 전 한 프레임은 `themes.xml`의 `windowLightStatusBar`가 맡는다.
         */
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        // 첫 화면이 그려지기 전에 읽는다 — 인트로가 뜬 뒤에 코드가 들어오면 진입 URL이 한 번
        // 바뀌면서 WebView가 다시 로드된다.
        applyAppLink(intent)

        /*
         * 일부러 내는 테스트 크래시 (KAN-33 AC 9). 디버그 빌드에만 본문이 있다 —
         * 릴리스 변형은 `src/release`의 빈 함수다 (analytics/TestCrash.kt).
         */
        crashIfRequested(intent)

        setContent {
            AccenturyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TestFlow(appLink = appLink, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    /**
     * 앱이 살아 있는 동안 눌린 링크 (KAN-32 2단계). `launchMode="singleTask"`라 새 Activity가
     * 서는 대신 여기로 들어온다. [setIntent]로 갈아 끼우는 이유는 이후 [getIntent]를 읽는 쪽이
     * 첫 Intent를 보지 않게 하기 위해서다.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyAppLink(intent)
        // 이미 떠 있는 앱에도 넣을 수 있어야 한다 — singleTask라 두 번째 `am start`가 여기로 온다.
        crashIfRequested(intent)
    }

    /**
     * Intent가 우리 공유 링크일 때만 [appLink]를 갈아 끼운다 (KAN-32 2단계).
     *
     * **링크가 아니면 지우지 않는다.** singleTask에서는 런처 아이콘 탭도 이 경로로 들어오는데
     * (데이터 없는 MAIN Intent), 그때 값을 비우면 사용자가 앱을 잠깐 내렸다 다시 여는 것만으로
     * 유입 계측이 사라진다 — 링크로 들어와 응시하다 홈으로 나갔다 오는 흐름이 정확히 그 경우다.
     *
     * 회전·프로세스 사망 복원은 원래 VIEW Intent가 [onCreate]로 다시 배달되므로 별도 저장 없이
     * 값이 돌아온다.
     */
    private fun applyAppLink(intent: Intent?) {
        val entry = parseAppLink(intent?.dataString, appLinkOrigins(BuildConfig.WEB_URL)) ?: return
        appLink.value = entry
    }
}

/**
 * 인트로(웹) → 시작 게이트(마이크 권한 → 세션 생성) → 테스트 진입(웹) → VOICE 문항마다 녹음
 * 오버레이 (KAN-100, KAN-34).
 *
 * **WebView는 인트로부터 테스트 끝까지 한 인스턴스로 산다.** 진행의 정본이 웹 상태 머신이라
 * WebView를 내리면 어디까지 왔는지가 같이 사라진다 — 네이티브 화면(권한 게이트·세션 준비·녹음)은
 * 화면을 갈아끼우는 대신 그 위를 덮는다. 무엇을 덮을지는 [TestFlowController.phase]가 정하고,
 * 여기는 Android·Compose 결선만 한다.
 *
 * @param appLink App Link 진입 (KAN-32). Activity가 Intent에서 읽어 흘려보낸다
 */
@Composable
private fun TestFlow(appLink: StateFlow<AppLinkEntry?>, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    /*
     * 링크가 실어 온 계측 코드 (KAN-32 2단계). 두 곳으로 간다 — 웹 진입 URL의 `?c=`와
     * `POST /v0/sessions`의 campaignToken이다. 앱이 세션을 직접 만드는 구조라(KAN-34) URL만으로는
     * 서버 세션에 유입 경로가 남지 않아 둘 다 필요하다.
     */
    val campaignToken = appLink.collectAsStateWithLifecycle().value?.campaignToken

    /*
     * 결과 공유 (KAN-30). Activity 컨텍스트인 이유: 공유 시트와 카톡 전환은 지금 화면 위에 올라와야
     * 하는 UI라, application 컨텍스트로 띄우면 NEW_TASK로 별도 태스크가 되고 공유를 끝낸 사용자가
     * 우리 결과 화면으로 돌아오지 못한다.
     */
    val activity = checkNotNull(LocalActivity.current)

    /*
     * 앱 안 계측 창구 하나 (KAN-33). 공유(FR-SH-06)·재녹음·웹이 브리지로 넘긴 이벤트가 전부
     * 여기로 모인다 — 같은 사건이 두 경로로 가지 않게 하는 것이 이 하나뿐이라는 사실 자체다.
     *
     * 설정(google-services.json)이 없으면 Logcat sink가 온다. 그 판정은 [EventSink.create] 안에
     * 있고 화면은 어느 쪽인지 모른다 — 계측 도구가 바뀌어도 이 아래 코드는 그대로여야 한다.
     */
    val events = remember(context) { EventSink.create(context) }
    val resultSharer = remember(activity, events) {
        ResultSharer.forApp(activity) { channel ->
            // 띄운 통로만 싣는다. 세션·점수는 익명 규칙에서 제외 대상이다 (AppEvents).
            events.log(
                ShareEvents.LAUNCHED,
                mapOf(ShareEvents.PARAM_CHANNEL to EventParam.Text(channelParam(channel))),
            )
        }
    }

    fun isMicGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /*
     * 시작 게이트의 네 칸 (KAN-34, KAN-105).
     *
     * 웹의 [시작하기] → 마이크 권한(KAN-98) → 목소리 점검(KAN-105) → 세션 생성(KAN-9) 순서로
     * 지나야 테스트가 열린다. 앞의 둘은 "지났는가"라는 불리언이지만 뒤의 둘은 **받아 온 값 자체**가
     * 통과 표시다 — 세션은 진입 URL·업로드·브리지 토큰이 전부 거기서 나오므로, 진입 여부를 세션과
     * 따로 들면 "들어갔는데 세션이 없다"는 표현 가능한 어긋남이 생긴다. 그래서 예전의 `testEntered`
     * 불리언을 없애고 [SessionGateController.session]이 그 자리를 대신한다. 목소리 점검도 같은 꼴이다 —
     * 통과의 결과물이 중심 음높이라, 통과 여부를 따로 들면 "지났는데 중심이 없다"가 생긴다.
     *
     * **점검이 권한과 세션 사이인 이유**: 마이크가 방금 열려 확인할 것이 바로 앞에 있고, 아직
     * 네트워크를 쓰기 전이라 전부 기기 안에서 끝난다. 세션 뒤로 밀면 이미 발급된 세션(만료가 도는
     * 자원)을 든 채 점검에 붙들리는 구간이 생긴다.
     *
     * 넷 다 회전·프로세스 복원을 넘긴다. 증발하면 통과한 게이트가 다시 서고 인트로로 되돌아가는데,
     * 세션이 증발하는 경우는 그보다 나빠서 — 응답에서 한 번만 노출되는 토큰이라(Session KDoc)
     * 되찾을 길이 없고 진행 중이던 응시가 통째로 죽는다.
     */
    var startRequested by rememberSaveable { mutableStateOf(false) }
    var micPassed by rememberSaveable { mutableStateOf(false) }
    var voiceCenterHz by rememberSaveable { mutableStateOf<Float?>(null) }
    val sessionGate = rememberSaveable(saver = SessionGateController.saver()) { SessionGateController() }
    val sessionClient = remember { OkHttpSessionClient(BuildConfig.API_BASE_URL) }
    val session = sessionGate.session

    val flow = rememberSaveable(saver = TestFlowController.saver()) { TestFlowController() }

    /*
     * 업로드는 테스트 phase 전체를 산다 — 녹음 화면이 내려가도 전송은 계속돼야 하고, 실패한 건은
     * 웹으로 돌아간 뒤에도 상태 바에서 재시도할 수 있어야 한다. 회전(Activity 재생성)도 넘겨야
     * 해서 소유자는 ViewModel이다 (UploadViewModel 주석).
     *
     * 세션이 생긴 뒤에야 만든다 (KAN-34). 업로드가 어느 세션으로 나가는지는 만드는 순간 정해지는데
     * (UploadManager가 생성자에서 받는다) 인트로 시점에는 그 값이 아직 없다. sessionId를 키로 주어
     * 회전에서는 같은 인스턴스를 되찾고(올라가던 음성과 재시도 통로가 그대로 살아남는다) 다른
     * 세션에서는 다른 인스턴스가 되게 한다 — 종료한 응시의 업로드가 새 세션에 섞이지 않는다.
     */
    val uploadViewModel: UploadViewModel? = if (session == null) {
        null
    } else {
        viewModel<UploadViewModel>(
            key = session.sessionId,
            factory = UploadViewModel.factory(BuildConfig.API_BASE_URL, session.sessionId, session.sessionToken),
        )
    }
    // 세션 전에는 올라간 것이 없으니 빈 목록이 정확한 답이다 — 아래 이펙트·상태 바가 전부
    // 이 값만 읽으므로 널 검사가 화면 쪽으로 번지지 않는다.
    val uploads: Map<String, UploadState> =
        if (uploadViewModel == null) emptyMap() else uploadViewModel.uploads.collectAsStateWithLifecycle().value

    // 복원된 대기 시도 중 대응 업로드가 없는 건을 한 번만 걷어낸다. 회전은 ViewModel이 살아남아
    // 업로드 키가 그대로라 아무것도 지워지지 않고, 프로세스 사망 복원에서는 전부 정리된다.
    // 아래 결과 소비 이펙트보다 먼저 등록돼(둘 다 메인 스레드) 가짜 대기를 먼저 걷어낸다.
    LaunchedEffect(Unit) {
        flow.pruneAttemptsWithoutUpload(uploadViewModel?.uploads?.value?.keys.orEmpty())
    }

    // 결과를 웹에 넣으려면 evaluateJavascript를 부를 인스턴스가 필요하다.
    // 로드 실패 화면·재시도 구간에는 WebView가 아예 없으므로 nullable이다.
    var webView by remember { mutableStateOf<WebView?>(null) }

    /*
     * 업로드가 끝난 시도를 웹으로 흘려보낸다.
     *
     * WebView가 없는 동안에는 아예 꺼내지 않는다 — onUploadsChanged는 꺼낸 결과를 대기 목록에서
     * 지우므로, 받을 곳이 없을 때 부르면 그 문항의 결과가 영영 사라진다. webView를 키로 둔 덕에
     * WebView가 돌아오면 그때 밀린 결과가 실려 나간다.
     *
     * 남는 유실 경로는 하나다: WebView는 있는데 페이지가 아직 수신 지점(window.AccenturyWeb)을
     * 설치하기 전이면, 주입한 JS가 조용히 아무 일도 하지 않는다. 이건 받아들인다 — 웹이 그 문항을
     * 제출된 것으로 표시하지 않으므로 화면에 [녹음 화면 다시 열기]가 남고, 다시 녹음하면 복구된다.
     */
    LaunchedEffect(uploads, webView) {
        val view = webView ?: return@LaunchedEffect
        flow.onUploadsChanged(uploads).forEach { result ->
            // 완료 콜백에서 화면을 놓는다 (KAN-146) — 주입이 끝났다는 것은 웹이 결과를 받아 다음
            // 문항을 그리기 시작했다는 뜻이다. 조립 자리에서 놓으면 그 사이 한 프레임 동안 걷힌
            // 아래에 아직 앞 문항의 대기 화면이 남아 드러난다. 콜백은 메인 스레드로 온다.
            view.evaluateJavascript(itemResultDeliveryJs(result)) { handed ->
                // "true"는 수신 지점이 실제로 있어 결과를 넘겼다는 뜻이다. 없으면 웹은 아직 앞 문항을
                // 그리고 있으므로 여기서 화면을 놓으면 그 대기 화면 위로 걷히게 된다 — 안전망 타이머가
                // 받게 두고 그동안 웹이 수신 지점을 설치할 시간을 준다.
                if (handed == "true") flow.onResultDelivered(result.attemptId)
            }
        }
    }

    /*
     * 녹음을 새로 해야 풀리는 실패를 걷고 그 문항의 녹음 화면을 다시 연다 (KAN-147, B안).
     *
     * 서버가 녹음 자체를 거절한 건(rerecord)만 여기로 온다 - 그것만이 재전송으로 풀리지 않아
     * 복구 경로가 재녹음 하나뿐이다. 전송 실패는 [재시도]가 계속 서 있고, 그 외 서버 거절은
     * 서버 문구를 단 실패 행으로 상태 바에 그대로 남는다.
     *
     * 웹은 네이티브 쪽 실패를 통지받지 않으므로(브리지 표면 최소 원칙) 그 문항의 대기 화면에
     * 그대로 멈춰 있다 - 여기서 화면을 다시 열어도 진행을 앞지르지 않는 근거다.
     *
     * 위 결과 전달 이펙트와 같은 키(uploads)로 돌고, 그보다 뒤에 선언한다. 정확성은 순서에 기대지
     * 않는다 - onUploadsChanged는 Submitting일 때만, onResultDelivered는 기다리던 attemptId일 때만
     * phase를 내리므로 여기서 연 녹음 화면을 걷지 못한다. 뒤에 둔 것은 읽는 순서다: 나갈 결과를
     * 먼저 내보내고, 결과가 나올 일이 없는 실패를 정리한다.
     *
     * webView를 키로 두지 않는 이유: 이 정리는 웹에 아무것도 넣지 않는다. 로드 실패 화면 등
     * WebView가 없는 구간에서도 실패한 업로드의 바이트는 즉시 폐기되어야 한다 (FR-DP-02).
     *
     * 폐기는 컨트롤러가 그 시도를 실제로 거둬갔을 때만 한다 - false는 이미 밀려났거나 모르는
     * 시도라는 뜻이라, 그때 폐기하면 같은 키를 쓰는 다른 흐름의 상태를 건드릴 수 있다.
     */
    LaunchedEffect(uploads) {
        uploads.forEach { (attemptId, state) ->
            if (state !is UploadState.Failed || !state.rerecord) return@forEach
            // 서버 문구를 그대로 실어 보낸다 - 왜 다시 녹음해야 하는지는 서버만 안다.
            if (flow.onUploadGivenUp(attemptId, micGranted = isMicGranted(), message = state.message)) {
                uploadViewModel?.discard(attemptId)
            }
        }
    }

    // RecordingScreen이 기본값으로 잡는 것과 같은 인스턴스. onNext에서 PCM을 꺼내려면 여기서도 필요하다.
    // 엔진을 여기서 만들어 넣는 이유는 PCM 소스 선택에 Context가 필요해서다 (defaultPcmSource).
    // 프로퍼티 없이 빌드하면 소스는 그대로 AudioRecorder라 동작이 달라지지 않는다.
    val appContext = context.applicationContext
    val recordingFactory = remember(appContext) {
        viewModelFactory {
            initializer { RecordingViewModel(RecordingEngine(defaultPcmSource(appContext))) }
        }
    }
    val viewModel: RecordingViewModel = viewModel(factory = recordingFactory)

    // 목소리 점검도 같은 소스를 쓴다 - 디버그의 가짜 마이크가 점검 화면에도 그대로 흐르고,
    // 점검이 잰 중심이 실제 문항에서 쓸 마이크와 같은 경로에서 나온다.
    val voiceCheckFactory = remember(appContext) {
        VoiceCheckViewModel.factory(defaultPcmSource(appContext))
    }
    val voiceCheckViewModel: VoiceCheckViewModel = viewModel(factory = voiceCheckFactory)

    /*
     * 브리지 getSessionToken(KAN-13)이 읽을 토큰 자리 (KAN-34).
     *
     * 그 메서드는 값을 동기로 돌려주므로 JS 스레드에서 그대로 실행된다 — WebViewHost의
     * originAllowed가 AtomicBoolean인 것과 같은 이유로, 메인 스레드가 갱신해 두고 JS 스레드는
     * 읽기만 하는 자리를 하나 둔다.
     *
     * 공급자 람다가 세션을 직접 붙잡지 않는 이유가 하나 더 있다: 이 람다는 WebView를 만들 때 한 번
     * 브리지에 실려 들어가 그대로 산다. 그 시점의 세션(인트로에서는 null)을 캡처하면 이후 세션이
     * 생겨도 브리지는 영영 빈 토큰을 돌려준다.
     */
    val bridgeToken = remember { AtomicReference("") }
    SideEffect { bridgeToken.set(session?.sessionToken.orEmpty()) }

    val scope = rememberCoroutineScope()

    /**
     * 결과 화면의 [다시 테스트하기] (KAN-34 2단계, KAN-107).
     *
     * 세션 게이트 화면이 아니라 여기서 요청을 거는 이유: 재응시가 벌어지는 자리에는 그 화면이 없다.
     * 사용자는 결과 화면(웹)을 보고 있고 그 화면은 요청이 도는 동안에도 그대로 있어야 한다 —
     * 실패하면 돌아갈 곳이 거기다.
     *
     * 이 함수는 브리지 콜백(postToMain)을 타고 메인 스레드에서 불린다. 진행 중 판정과 상태 전이는
     * 전부 [SessionGateController]가 하고 여기서는 요청을 걸어 결과를 넘겨줄 뿐이다.
     */
    fun startRetest() {
        // null이면 이미 요청이 나가 있거나 버릴 세션이 없다 — 어느 쪽이든 할 일은 없다.
        val previousToken = sessionGate.beginRetest() ?: return
        scope.launch {
            /*
             * 이전 토큰을 실어 서버가 이전 세션과 결과를 **즉시** 폐기하게 한다 (KAN-107).
             * 폐기와 발급이 한 요청이라, 실패하면 이전 세션이 그대로 살아 있는 것도 보장된다.
             */
            val result = sessionClient.create(
                appVersion = BuildConfig.VERSION_NAME,
                previousToken = previousToken,
                // 재응시도 같은 유입이다 (KAN-32) — 공유 링크로 들어온 사람이 한 번 더 보는 것까지가
                // 그 링크가 만든 응시라, 코드를 그대로 물려준다.
                campaignToken = campaignToken,
            )
            when (val outcome = sessionGate.onRetestResult(result)) {
                is RetestOutcome.Replaced -> {
                    /*
                     * 새 세션을 든 채 인트로로 돌린다. 진입 URL은 startRequested를 함께 보므로
                     * (위 buildWebUrl) 이 한 줄이 곧 인트로 리로드다.
                     *
                     * micPassed는 되돌리지 않는다 — 권한이 이미 허용이면 다시 묻지 않는 것이
                     * KAN-34 AC다. 그사이 설정에서 회수됐다면 문항 진입 시점의 게이트가 잡는다
                     * (onStartVoiceItem의 micGranted 재확인).
                     *
                     * 업로드는 세션이 바뀌면서 자연히 갈린다 — uploadViewModel의 키가 sessionId라
                     * 새 세션은 새 인스턴스를 받고, 끝난 응시의 업로드가 섞이지 않는다.
                     */
                    startRequested = false
                }

                is RetestOutcome.Failed -> {
                    /*
                     * 결과 화면은 그대로 살아 있다 — 왜 아무 일도 일어나지 않았는지 그 화면에
                     * 회신한다. WebView가 없는 구간(로드 실패 화면)이면 회신할 상대가 없는데,
                     * 그때는 재응시 버튼을 누를 화면도 없으므로 도달하지 않는 조합이다.
                     *
                     * evaluateJavascript는 메인 스레드에서만 부를 수 있다. 이 스코프는 컴포지션의
                     * 것이라 기본 디스패처가 메인이므로(rememberCoroutineScope) 따로 옮기지 않는다.
                     */
                    webView?.evaluateJavascript(
                        retestFailedDeliveryJs(retestFailurePayload(outcome)),
                        null,
                    )
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            WebViewHost(
                url = buildWebUrl(
                    base = BuildConfig.WEB_URL,
                    appVersionName = BuildConfig.VERSION_NAME,
                    /*
                     * 세션이 곧 로드할 URL이다 (KAN-34). testVersion은 서버가 이 세션에 고정한
                     * 값이고(§3.1) sessionId는 웹이 진행 스냅샷을 가르는 키다 — 음성이 올라가는
                     * 세션과 웹이 진행을 저장하는 세션이 갈리면 안 되므로 둘 다 같은 응답에서 온다.
                     * 회전·프로세스 복원으로 WebView를 새로 만들어도 저장된 세션이 같은 화면을 다시 세운다.
                     *
                     * 세션만으로 진입을 정하지 않고 [startRequested]를 함께 보는 이유는 재응시다
                     * (KAN-34 2단계). 재응시는 새 세션을 **손에 든 채** 인트로로 돌아가는 흐름이라,
                     * 세션의 존재만으로 진입 URL을 만들면 인트로가 뜰 새도 없이 테스트가 다시 열린다.
                     * "세션이 있다"는 응시할 준비가 됐다는 뜻이고, "시작을 눌렀다"가 들어가겠다는 뜻이다.
                     */
                    testEntry = if (startRequested) {
                        session?.let { TestEntry(it.testVersion, it.sessionId) }
                    } else {
                        null
                    },
                    /*
                     * 링크가 실어 온 계측 코드 (KAN-32). 응시 도중에 새 링크가 들어오면 이 URL
                     * 문자열이 바뀌어 WebViewHost가 다시 로드한다 — 웹이 sessionId 스냅샷에서
                     * 진행을 복원하고, 응시 중에 자기 링크를 다시 누르는 일 자체가 드물어 그
                     * 리로드를 감수한다 (KAN-32 결정).
                     */
                    campaignToken = campaignToken,
                ),
                allowedOrigins = setOfNotNull(webOrigin(BuildConfig.WEB_URL)),
                // 웹의 어휘 답안 제출(KAN-13)이 쓸 토큰. 업로드·웹 진입 URL과 같은 세션에서 온다.
                sessionToken = { bridgeToken.get() },
                onRequestMicPermission = { startRequested = true },
                onStartVoiceItem = { start ->
                    // 브리지 콜백은 postToMain을 타고 오므로 여기는 메인 스레드다.
                    // 시작 게이트를 통과했어도 설정에서 회수됐을 수 있어 진입마다 다시 확인한다.
                    flow.onStartVoiceItem(start, micGranted = isMicGranted())
                },
                onStartRetest = { startRetest() },
                /*
                 * 탭은 여기서 세지 않는다 (FR-SH-06). 그 한 건은 웹이 `share_clicked`로 이미
                 * 세고, 앱 안에서는 브리지 `logEvent`를 타고 같은 창구로 들어온다 — 네이티브가
                 * 이름을 하나 더 붙이면 같은 탭이 앱과 웹에서 다른 축으로 갈린다. 네이티브가 세는
                 * 것은 통로가 실제로 열린 일뿐이고, 그쪽은 [resultSharer]가 통로를 붙여 울린다.
                 * 클릭 수와 실행 수의 차이는 그대로 "눌렀는데 아무 데도 못 간" 비율이다.
                 */
                onShareResult = { resultSharer.share(it) },
                /*
                 * 웹이 센 사건을 앱 스트림으로 넘긴다 (KAN-33). 이름을 여기서 손대지 않는 것이
                 * 요점이다 — 웹과 앱이 같은 이름으로 쌓여야 하나의 퍼널이 되고, 그 정본은
                 * `web/src/analytics/events.ts` 하나다. 값 검증은 브리지가 이미 끝냈다.
                 */
                onLogEvent = { name, params -> events.log(name, params) },
                onWebViewCreated = { webView = it },
                // 내가 들고 있는 인스턴스일 때만 놓는다 — 재생성 순서에 따라 새 WebView가 먼저
                // 등록된 뒤 옛 것이 해제될 수 있고, 그때 방금 받은 참조를 지우면 안 된다.
                onWebViewReleased = { if (webView === it) webView = null },
            )

            // 오버레이는 WebView 위, 업로드 상태 바 아래다 — 녹음 중에도 실패한 업로드의
            // 재시도 통로가 가려지지 않아야 한다.
            val phase = flow.phase

            /*
             * 오버레이가 덮는 두 페이즈는 같은 화면을 쓰고 아래쪽만 다르다 (KAN-146).
             * 전환에 애니메이션을 두지 않는다: 웹이 음성 문항을 먼저 그려야 브리지가 호출되는 구조라
             * 등장에 페이드를 걸면 그 대기 화면이 페이드 내내 비쳐, 없앨 수 있던 노출을 되레 늘린다.
             * 퇴장도 마찬가지로 즉시다 — 걷히는 자리에는 이미 다음 문항이 그려져 있어 건너갈 중간
             * 화면이 없다.
             */
            val overlayStart = when (phase) {
                is TestFlowPhase.Recording -> phase.start
                is TestFlowPhase.Submitting -> phase.start
                else -> null
            }
            val submitting = phase is TestFlowPhase.Submitting

            /*
             * 자취 없는 제출을 걷는 최후 안전망 (KAN-146).
             *
             * 업로드가 살아 있는 동안에는 시간으로 걷지 않는다 — 끝나면 결과가 나가 다음 문항이
             * 그려지고, 실패하면 onUploadsChanged가 그것도 종료로 보고 놓는다. 시간이 개입하면 느린
             * 망에서 업로드가 아직 진행 중인데 화면을 놓아 버려, 이 티켓이 없애려던 대기 화면이 바로
             * 그 구간에 다시 생긴다.
             *
             * 물어볼 업로드 자체가 없을 때만 시간이 걷는다 — 프로세스 사망 복원에서 대기 시도는
             * saver가 살렸는데 업로드는 메모리와 함께 사라진 경우다. attemptId를 키로 두어 결과가
             * 먼저 나가 다음 문항으로 넘어가면 타이머는 취소된다.
             */
            val submittingAttemptId = (phase as? TestFlowPhase.Submitting)?.attemptId
            val awaitedUpload = submittingAttemptId?.let { uploads[it] }
            val holdUnbacked = submittingAttemptId != null && awaitedUpload !is UploadState.InFlight
            LaunchedEffect(submittingAttemptId, holdUnbacked) {
                if (!holdUnbacked) return@LaunchedEffect
                val attemptId = submittingAttemptId ?: return@LaunchedEffect
                delay(ORPHANED_SUBMIT_TIMEOUT_MS)
                // 발화 시점의 업로드 상태를 다시 넘긴다 — 걸 때는 비어 있던 자리가 그새 채워졌을 수 있다.
                flow.onSubmitTimeout(attemptId, uploadViewModel?.uploads?.value.orEmpty())
            }

            when {
                // 시작 게이트 1칸 — 마이크 권한 (KAN-98). 통과 표시를 따로 두는 이유는 뒤에 세션
                // 생성이 이어지기 때문이다: 세션을 기다리는 동안 권한 화면으로 되돌아가면 안 된다.
                startRequested && session == null && !micPassed ->
                    PermissionGate(onGranted = { micPassed = true })

                // 시작 게이트 2칸 — 목소리 점검 (KAN-105). 중심 음높이를 받으면 조건이 풀린다.
                // 마이크가 막 열린 자리라 여기서 확인하고, 잰 값은 이후 모든 문항의 곡선 축이 된다.
                startRequested && session == null && micPassed && voiceCenterHz == null ->
                    VoiceCheckScreen(
                        viewModel = voiceCheckViewModel,
                        onDone = { voiceCenterHz = it },
                    )

                // 시작 게이트 3칸 — 세션 생성 (KAN-34). 확보되면 테스트 URL이 로드되고 조건이
                // 풀려 이 화면이 사라진다. 여기 닿았다는 것은 앞의 두 칸을 이미 지났다는 뜻이라
                // (위 두 분기가 그 경우를 먼저 가져간다) 조건을 다시 적지 않는다.
                startRequested && session == null -> SessionGateScreen(
                    gate = sessionGate,
                    client = sessionClient,
                    appVersion = BuildConfig.VERSION_NAME,
                    campaignToken = campaignToken,
                    onBackToIntro = {
                        startRequested = false
                        micPassed = false
                        // 점검도 함께 되돌린다 - 인트로로 돌아간 뒤 다시 시작하면 마이크를 새로
                        // 열게 되므로, 그 마이크가 잘 잡히는지는 그때 다시 확인해야 맞다.
                        voiceCenterHz = null
                        // 실패 상태를 그대로 두면 다음 [시작하기]가 같은 실패 화면으로 곧장 떨어진다.
                        sessionGate.restart()
                    },
                )

                // 문항 진입 시점의 게이트 — 통과하면 기다리던 문항의 녹음으로 곧장 들어간다.
                phase is TestFlowPhase.NeedsPermission -> PermissionGate(onGranted = flow::onPermissionGranted)

                // 세션 없이 녹음 오버레이가 설 수는 없다(웹이 문항을 그리려면 진입 URL이 열려야 하고,
                // 그 URL은 세션에서 나온다). 그 사실을 조건에 함께 적어 아래 결선의 널 검사를 없앤다.
                overlayStart != null && uploadViewModel != null -> {
                    /*
                     * 녹음 상태 되감기를 [다음] 자리가 아니라 여기서 한다 (KAN-146).
                     * 그 자리에서 즉시 reset()을 부르면 제출을 기다리는 동안 화면이 대기 상태로 바뀌어,
                     * 방금 그린 '내 억양' 곡선이 사라진다.
                     *
                     * 되감을지의 판정(continuesFrom)은 컨트롤러 쪽에 둔다 — 화면 겹침의 정확성을
                     * 좌우하는 판정을 Compose 안에 두면 JVM에서 검증할 수 없다는 것이
                     * TestFlowController를 분리한 이유 그대로다. 여기서는 "언제 물어보는가"만 정한다.
                     */
                    DisposableEffect(phase) {
                        onDispose {
                            if (!continuesFrom(phase, flow.phase)) viewModel.reset()
                        }
                    }

                    RecordingOverlay(
                        start = overlayStart,
                        submitting = submitting,
                        // 업로드 재녹음 전환으로 이 화면이 스스로 다시 열렸는가 (KAN-147).
                        // 제출을 기다리는 중에는 뜻이 없는 값이라 Recording일 때만 본다.
                        afterUploadFailure = (phase as? TestFlowPhase.Recording)?.afterUploadFailure == true,
                        // 그 전환에서 서버가 준 문구. null이면 화면이 기본 안내를 쓴다.
                        failureMessage = (phase as? TestFlowPhase.Recording)?.failureMessage,
                        // 시작 게이트의 점검이 잰 중심 음높이 (KAN-105). 이 자리까지 왔다는 것은
                        // 점검을 지났다는 뜻이라 실제로는 항상 값이 있다.
                        centerHz = voiceCenterHz,
                        viewModel = viewModel,
                        /*
                         * 네이티브 녹음 화면의 [재녹음] (KAN-33). 웹 녹음기가 세는 것과 같은
                         * 사건이라 이름·파라미터를 그대로 맞춘다 — 앱 사용자의 재녹음만 다른
                         * 지표로 갈리면 문항 난이도를 두 표본으로 나눠 보게 된다.
                         *
                         * 사유가 USER 하나인 이유는 이 자리가 실패 없이 사용자가 다시 읽기로 한
                         * 지점이라서다. 서버가 되돌려보낸 재녹음(QUALITY·FAILED)은 웹의 분석 대기
                         * 화면이 소유하고 거기서 이미 센다 (AnalysisWaitingScreen).
                         */
                        onRetake = {
                            events.log(
                                RecordingEvents.RETAKE,
                                mapOf(
                                    // 사람이 읽는 1-기반 번호다 (웹 `item_seq`와 같은 값).
                                    RecordingEvents.PARAM_ITEM_SEQ to
                                        EventParam.Count(overlayStart.itemNumber.toLong()),
                                    RecordingEvents.PARAM_REASON to
                                        EventParam.Text(RecordingEvents.REASON_USER),
                                ),
                            )
                        },
                        onSubmit = { attemptId, durationMs, quality ->
                            // consumeRecording은 PCM을 넘기면서 뷰모델에서 지운다 (FR-DP-02: 보관하지 않음).
                            val pcm = viewModel.consumeRecording()
                            if (pcm == null) {
                                // 올릴 바이트가 없으면 결과도 만들어질 수 없다. 시도로 등록하면 웹이
                                // 오지 않을 결과를 기다리며 그 문항에 멈추므로, 등록 없이 돌려보내
                                // [녹음 화면 다시 열기]로 다시 녹음하게 한다.
                                flow.onRecordingExit()
                            } else {
                                uploadViewModel.enqueue(
                                    UploadRequest(
                                        attemptId = attemptId,
                                        itemId = overlayStart.itemId,
                                        wavBytes = WavWriter.toWavBytes(pcm),
                                        durationMs = durationMs,
                                        clientQuality = AudioQuality.measure(pcm),
                                    ),
                                    label = "${overlayStart.itemNumber}번 문항",
                                )
                                // 화면은 결과가 나갈 때까지 붙들되(Submitting) 진행은 업로드를
                                // 기다리지 않는다 — 대기 시도는 여기서 바로 등록된다.
                                //
                                // 밀려난 앞 시도의 업로드는 여기서 폐기한다 (KAN-147). 남겨두면
                                // 상태 바의 [재시도]가 그대로 서 있고, 그걸 누르면 같은 문항에
                                // 분석 작업이 둘 생긴다. 새 업로드를 먼저 걸고 지우는 순서라
                                // attemptId가 겹치는 경우에도 방금 건 업로드가 살아남는다.
                                flow.onRecordingFinished(attemptId, durationMs, quality)
                                    .forEach(uploadViewModel::discard)
                            }
                        },
                    )
                }
            }
        }

        // 세션 전에는 올라간 것도, 실패한 것도 없다 — 상태 바가 설 이유 자체가 없는 구간이다.
        if (uploadViewModel != null) {
            UploadStatusBar(
                uploads = uploads,
                labelOf = uploadViewModel::labelOf,
                onRetry = uploadViewModel::retry,
            )
        }
    }
}

/**
 * 녹음 화면 오버레이. [Surface]로 아래 WebView를 완전히 가린다 — WebView는 살아 있고 배경만
 * 덮는 구조라, 배경이 없으면 웹 화면이 그대로 비친다.
 */
@Composable
private fun RecordingOverlay(
    start: VoiceItemStart,
    /** 제출한 시도의 결과를 기다리는 중 — 화면은 그대로 두고 하단만 바꾼다 (KAN-146). */
    submitting: Boolean,
    /** 업로드 재녹음 전환으로 이 화면이 스스로 다시 열렸는가 (KAN-147) - 안내 문구가 이유를 밝힌다. */
    afterUploadFailure: Boolean,
    /** 그 전환에서 서버가 준 문구 (KAN-147). null이면 화면이 기본 안내를 쓴다. */
    failureMessage: String?,
    /** 사용자 곡선 y축의 중심 음높이 (KAN-105). 시작 게이트의 목소리 점검이 잰 값이다. */
    centerHz: Float?,
    viewModel: RecordingViewModel,
    /** [재녹음]을 눌렀다 (KAN-33). 되감기 자체는 화면이 하고, 여기는 세기만 한다 */
    onRetake: () -> Unit,
    onSubmit: (attemptId: String, durationMs: Long, quality: QualityStatus) -> Unit,
) {
    // 색을 명시한다 - Surface 기본값은 surface(카드 흰색)라, 그대로 두면 이 오버레이만
    // 흰 배경이 되어 바로 앞뒤 WebView 화면(background #f3ecd9)과 어긋난다.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        RecordingScreen(
            questionText = start.prompt,
            questionIndex = start.itemNumber,
            totalQuestions = start.totalItems,
            onNext = onSubmit,
            guideF0 = start.guideF0,
            submitting = submitting,
            afterUploadFailure = afterUploadFailure,
            failureMessage = failureMessage,
            centerHz = centerHz,
            onRetake = onRetake,
            viewModel = viewModel,
        )
    }
}

/**
 * 마이크 권한 게이트 (KAN-98). 거부하면 테스트를 시작할 수 없다 — 부분 응시 없음 (2026-07-27 확정).
 * 판단 로직은 [MicPermissionController]에 있고, 여기는 Android API 결선(팝업·설정 딥링크·
 * ON_RESUME 재확인)과 상태별 화면만 담당한다. 문구 확정 전이라 무디자인이다 (KAN-97 방식 준용).
 *
 * 통과는 [onGranted]로 알리고 그 뒤 어디로 갈지는 호출자가 정한다 — 같은 게이트를 테스트 시작과
 * VOICE 문항 진입 두 곳에서 쓰는데 통과 후 할 일이 서로 다르기 때문이다(테스트 URL 로드 vs
 * 기다리던 문항의 녹음 재개). 두 호출 지점은 각자의 컨트롤러를 가져 상태를 공유하지 않는다.
 */
@Composable
private fun PermissionGate(onGranted: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 이 컴포지션은 항상 MainActivity 안에서 돈다 — 게이트는 Activity 없이 열릴 수 없다.
    val activity = checkNotNull(LocalActivity.current)

    fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // remember만 쓰면 회전·재생성에 Denied/PermanentlyDenied가 증발해 안내 화면부터 다시
    // 시작한다 — 영구 거부의 "설정 딥링크만" 경로를 잃지 않도록 저장하고, 복원은 실제
    // 권한과 대조한다 (프로세스 사망 중 설정 변경 가능).
    val controller = rememberSaveable(saver = MicPermissionController.saver(::isGranted)) {
        MicPermissionController(initiallyGranted = isGranted())
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 거부 직후의 rationale 값이 영구 거부 판별 기준이다 — 결과 도착 시점에 읽어야 한다.
        controller.onPermissionResult(
            granted = granted,
            canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO,
            ),
        )
    }

    // 설정 앱에서 허용하고 돌아오면 재시작 없이 통과해야 한다 — ON_RESUME마다 재확인한다.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.onReturnedToApp(isGranted())
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (controller.state) {
        // 통보는 렌더가 아니라 이펙트에서 한다 — onGranted가 상위 상태를 바꿔 이 게이트를
        // 걷어내므로, 컴포지션 도중에 부르면 컴포지션 중 상태 변경이 된다.
        MicPermissionState.Granted -> LaunchedEffect(Unit) { onGranted() }

        MicPermissionState.Rationale -> GateScreen(
            headline = "발음 분석에 마이크가 필요해요",
            supporting = "음성은 분석 즉시 삭제돼요",
            buttonLabel = "마이크 허용",
            onButtonClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
            modifier = modifier,
        )

        MicPermissionState.Denied -> GateScreen(
            headline = "마이크를 허용해야 시작할 수 있어요",
            supporting = "발음을 들어야 분석할 수 있어요 · 음성은 분석 즉시 삭제돼요",
            buttonLabel = "다시 허용하기",
            onButtonClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
            modifier = modifier,
        )

        MicPermissionState.PermanentlyDenied -> GateScreen(
            headline = "설정에서 마이크를 허용해 주세요",
            supporting = "권한 창을 더 띄울 수 없어요 · 설정에서 허용하면 이어서 시작할 수 있어요",
            buttonLabel = "설정 열기",
            onButtonClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
            modifier = modifier,
        )
    }
}

/**
 * 게이트 화면. 녹음 오버레이와 같은 이유로 [Surface]가 아래 WebView를 가린다.
 *
 * 배치는 시안(`prototype/src/app/App.tsx` MicScreen)을 따른다 — 히어로 아이콘, 카피,
 * 안심 문구 카드, 바닥의 주버튼. 권한을 묻는 화면이라 "무엇을 왜 가져가는지"가 카드에
 * 먼저 보이고 버튼이 마지막에 오는 순서가 중요하다.
 */
@Composable
private fun GateScreen(
    headline: String,
    supporting: String,
    buttonLabel: String,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.x6),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.x6, Alignment.CenterVertically),
            ) {
                // 마이크 선화. 잉크 한 색이라 아래 녹음 버튼 안의 아이콘과 같은 그림이다 -
                // "이 앱이 쓰는 것"과 "지금 허락을 구하는 것"이 같다는 게 그림으로 읽힌다.
                // 설명을 달지 않는 것은 바로 아래 제목이 이미 마이크 이야기를 하기 때문이다.
                HeroIcon(painter = painterResource(R.drawable.outline_mic_24))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.x2),
                ) {
                    Text(
                        headline,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                AssuranceCard()
            }

            AccenturyButton(
                text = buttonLabel,
                onClick = onButtonClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 마이크를 왜 달라는지 세 줄로 답하는 카드 (시안). 권한 요청 앞에서 사용자가 실제로 궁금해하는
 * 것은 "무엇에 쓰는가"와 "안전한가" 둘이라, 그 답을 버튼보다 먼저 보이는 자리에 둔다.
 */
@Composable
private fun AssuranceCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.xl))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.xl))
            .padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        ASSURANCES.forEach { text ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
            ) {
                /*
                 * 줄머리는 잉크 점 하나다 (KAN-161 4단계). 📊·🏆·🔒였는데, 잉크 한 색 화면에서
                 * 이모지는 색을 가진 유일한 물건이라 세 줄이 그림 밖으로 튀었다 (정본 §7).
                 * 그림을 잉크 선화 셋으로 바꾸려면 자산이 필요한데, 이 세 줄이 나르는 정보는
                 * 전부 글에 있어서 그림이 없어도 잃는 것이 없다 - 점은 목록이라는 표시만 한다.
                 */
                Box(
                    modifier = Modifier
                        .size(ASSURANCE_BULLET)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val ASSURANCES = listOf(
    "실시간 억양 곡선 분석",
    "발음 정확도 점수 측정",
    "음성은 분석 즉시 삭제",
)

/** 안심 문구 줄머리 점 */
private val ASSURANCE_BULLET = 6.dp
