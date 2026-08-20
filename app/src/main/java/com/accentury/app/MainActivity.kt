package com.accentury.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accentury.app.audio.AudioQuality
import com.accentury.app.audio.QualityStatus
import com.accentury.app.audio.WavWriter
import com.accentury.app.bridge.VoiceItemStart
import com.accentury.app.bridge.itemResultDeliveryJs
import com.accentury.app.permission.MicPermissionController
import com.accentury.app.permission.MicPermissionState
import com.accentury.app.recording.RecordingScreen
import com.accentury.app.recording.RecordingViewModel
import com.accentury.app.testflow.TestFlowController
import com.accentury.app.testflow.continuesFrom
import com.accentury.app.testflow.TestFlowPhase
import com.accentury.app.ui.components.AccenturyButton
import com.accentury.app.ui.theme.AccenturyTheme
import com.accentury.app.ui.theme.Spacing
import com.accentury.app.upload.UploadRequest
import com.accentury.app.upload.UploadState
import com.accentury.app.upload.UploadStatusBar
import com.accentury.app.upload.UploadViewModel
import com.accentury.app.web.TestEntry
import com.accentury.app.web.WebViewHost
import com.accentury.app.web.buildWebUrl
import com.accentury.app.web.webOrigin
import kotlinx.coroutines.delay

// 에뮬레이터에서 호스트 머신을 가리키는 주소. 실기기 테스트는 이 값만 바꾸면 된다.
private const val DEV_BASE_URL = "http://10.0.2.2:8080"

// KAN-9 세션 클라이언트가 붙으면 서버가 발급한 세션 값으로 교체된다. 업로드와 웹 진입 URL이
// 같은 상수를 쓰는 건 의도다 — 음성이 올라가는 세션과 웹이 진행을 저장하는 세션이 갈리면 안 된다.
private const val DEV_SESSION_ID = "dev-session"
private const val DEV_SESSION_TOKEN = "dev-token"

/*
 * 업로드가 뒷받침하지 않는 붙들기를 걷는 상한 (KAN-146).
 *
 * 업로드가 진행 중인 동안에는 시간으로 걷지 않는다 — 끝날 때까지 현재 문항 화면을 유지한다.
 * 진행 중이 아닌데도 화면이 붙들려 있다면 그건 곧 끝나야 할 짧은 창이거나(주입 완료 통지를 기다리는
 * 몇 십 ms) 영영 끝나지 않을 상태(프로세스 사망 복원으로 업로드가 메모리와 함께 사라진 경우)다.
 * 앞엣것은 이 상한이 오기 전에 스스로 풀리고, 뒤엣것은 이 상한만이 풀 수 있다.
 */
private const val ORPHANED_SUBMIT_TIMEOUT_MS = 2_000L

// 세션에 고정될 정의 버전도 KAN-9 응답이 정본이다. 그전까지는 백엔드가 발행해 둔 정의를
// 가리킨다 - 발행 입력이 DB로 옮겨지면서(KAN-26) classpath seed 파일은 폐기됐고, 지금 이
// 버전을 넣는 것은 backend/src/main/resources/db/migration/V2__test_definition_publish.sql이다.
private const val DEV_TEST_VERSION = "gn-2026.08.1"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AccenturyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TestFlow(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * 인트로(웹) → 시작 게이트 → 테스트 진입(웹) → VOICE 문항마다 녹음 오버레이 (KAN-100).
 *
 * **WebView는 인트로부터 테스트 끝까지 한 인스턴스로 산다.** 진행의 정본이 웹 상태 머신이라
 * WebView를 내리면 어디까지 왔는지가 같이 사라진다 — 네이티브 화면(권한 게이트·녹음)은 화면을
 * 갈아끼우는 대신 그 위를 덮는다. 무엇을 덮을지는 [TestFlowController.phase]가 정하고,
 * 여기는 Android·Compose 결선만 한다.
 */
@Composable
private fun TestFlow(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    fun isMicGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // 웹의 [시작하기]를 눌렀는가 / 시작 게이트를 통과해 테스트로 들어갔는가.
    // 로드할 URL이 이 두 값에서 파생되므로, 회전·프로세스 복원에 증발하면 통과한 게이트가
    // 다시 서고 인트로로 되돌아간다. 그래서 둘 다 저장한다.
    var startRequested by rememberSaveable { mutableStateOf(false) }
    var testEntered by rememberSaveable { mutableStateOf(false) }

    val flow = rememberSaveable(saver = TestFlowController.saver()) { TestFlowController() }

    // 업로드는 테스트 phase 전체를 산다 — 녹음 화면이 내려가도 전송은 계속돼야 하고, 실패한 건은
    // 웹으로 돌아간 뒤에도 상태 바에서 재시도할 수 있어야 한다. 회전(Activity 재생성)도 넘겨야
    // 해서 소유자는 ViewModel이다 (UploadViewModel 주석).
    val uploadViewModel: UploadViewModel = viewModel(
        factory = UploadViewModel.factory(DEV_BASE_URL, DEV_SESSION_ID, DEV_SESSION_TOKEN),
    )
    val uploads by uploadViewModel.uploads.collectAsStateWithLifecycle()

    // 복원된 대기 시도 중 대응 업로드가 없는 건을 한 번만 걷어낸다. 회전은 ViewModel이 살아남아
    // 업로드 키가 그대로라 아무것도 지워지지 않고, 프로세스 사망 복원에서는 전부 정리된다.
    // 아래 결과 소비 이펙트보다 먼저 등록돼(둘 다 메인 스레드) 가짜 대기를 먼저 걷어낸다.
    LaunchedEffect(Unit) {
        flow.pruneAttemptsWithoutUpload(uploadViewModel.uploads.value.keys)
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

    // RecordingScreen이 기본값으로 잡는 것과 같은 인스턴스. onNext에서 PCM을 꺼내려면 여기서도 필요하다.
    val viewModel: RecordingViewModel = viewModel()

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            WebViewHost(
                url = buildWebUrl(
                    base = BuildConfig.WEB_URL,
                    appVersionName = BuildConfig.VERSION_NAME,
                    // 테스트 진입 여부가 곧 로드할 URL이다. 회전·프로세스 복원으로 WebView를
                    // 새로 만들어도 저장된 testEntered가 같은 화면을 다시 세운다.
                    testEntry = if (testEntered) TestEntry(DEV_TEST_VERSION, DEV_SESSION_ID) else null,
                ),
                allowedOrigins = setOfNotNull(webOrigin(BuildConfig.WEB_URL)),
                // 웹의 어휘 답안 제출(KAN-13)이 쓸 토큰. 업로드와 같은 상수를 쓰는 건 의도다 —
                // KAN-9 결선 시 세 자리(업로드·웹 진입 URL·여기)가 같은 세션 값으로 함께 바뀐다.
                sessionToken = { DEV_SESSION_TOKEN },
                onRequestMicPermission = { startRequested = true },
                onStartVoiceItem = { start ->
                    // 브리지 콜백은 postToMain을 타고 오므로 여기는 메인 스레드다.
                    // 시작 게이트를 통과했어도 설정에서 회수됐을 수 있어 진입마다 다시 확인한다.
                    flow.onStartVoiceItem(start, micGranted = isMicGranted())
                },
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
                flow.onSubmitTimeout(attemptId, uploadViewModel.uploads.value)
            }

            when {
                // 시작 게이트. 통과하면 테스트 URL이 로드되고 조건이 풀려 오버레이가 사라진다.
                startRequested && !testEntered -> PermissionGate(onGranted = { testEntered = true })

                // 문항 진입 시점의 게이트 — 통과하면 기다리던 문항의 녹음으로 곧장 들어간다.
                phase is TestFlowPhase.NeedsPermission -> PermissionGate(onGranted = flow::onPermissionGranted)

                overlayStart != null -> {
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
                        viewModel = viewModel,
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
                                flow.onRecordingFinished(attemptId, durationMs, quality)
                            }
                        },
                        onExit = {
                            // 이탈은 그 자리에서 되감는다 — 녹음 중단·마이크 해제·PCM 폐기는
                            // FR-DP-02가 즉시를 요구한다.
                            viewModel.reset()
                            // 하네스와 달리 진행 전체를 초기화하지 않는다 — 나가기는 이 문항을 다시
                            // 시도하겠다는 뜻이라, 앞 문항들의 대기 시도까지 버리면 이미 끝난 업로드의
                            // 결과가 웹에 영영 도착하지 않는다 (TestFlowController.onRecordingExit 주석).
                            flow.onRecordingExit()
                        },
                    )
                }
            }
        }

        UploadStatusBar(
            uploads = uploads,
            labelOf = uploadViewModel::labelOf,
            onRetry = uploadViewModel::retry,
            onEndTest = {
                // 남아 있는 음성 바이트를 전부 폐기하고 인트로로 되돌린다 (FR-DP-02).
                // 컨트롤러의 대기 시도는 남지만 업로드가 사라져 결과로 조립되지 않는다 — 다시
                // 시작하면 웹이 결과를 받지 못한 문항부터 다시 요청하므로 진행은 어긋나지 않는다.
                viewModel.reset()
                uploadViewModel.clearAll()
                startRequested = false
                testEntered = false
            },
        )
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
    viewModel: RecordingViewModel,
    onSubmit: (attemptId: String, durationMs: Long, quality: QualityStatus) -> Unit,
    onExit: () -> Unit,
) {
    // 색을 명시한다 - Surface 기본값은 surface(카드 흰색)라, 그대로 두면 이 오버레이만
    // 흰 배경이 되어 바로 앞뒤 WebView 화면(background #eff6ff)과 어긋난다.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        RecordingScreen(
            questionText = start.prompt,
            questionIndex = start.itemNumber,
            totalQuestions = start.totalItems,
            onNext = onSubmit,
            onExit = onExit,
            guideF0 = start.guideF0,
            submitting = submitting,
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

/** 게이트 화면. 녹음 오버레이와 같은 이유로 [Surface]가 아래 WebView를 가린다. */
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
            modifier = Modifier.fillMaxSize().padding(Spacing.x4),
            verticalArrangement = Arrangement.spacedBy(Spacing.x3, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(headline, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(supporting, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            AccenturyButton(text = buttonLabel, onClick = onButtonClick)
        }
    }
}
