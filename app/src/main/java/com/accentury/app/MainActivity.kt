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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.accentury.app.testflow.TestFlowPhase
import com.accentury.app.ui.theme.AccenturyTheme
import com.accentury.app.upload.UploadRequest
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
 * 녹음 오버레이가 웹 위로 덮이고 걷히는 데 쓰는 페이드 길이 (KAN-146).
 * 200ms는 Material의 짧은 전환 구간(150~250ms) 안이다. 더 짧으면 덮기·걷기가 여전히 "툭"
 * 나타났다 사라지는 것으로 읽히고, 더 길면 [다음] 뒤 다음 문항까지가 느려진 것처럼 느껴진다.
 * 문항당 두 번(등장·퇴장) 겪는 전환이라 길이에 인색한 쪽이 맞다.
 */
private const val OVERLAY_FADE_MS = 200

/*
 * [다음] 뒤 결과를 기다리며 오버레이를 붙들어 두는 상한 (KAN-146).
 * 업로드가 실패하면 결과는 영영 나오지 않으므로 상한이 없으면 화면이 걷히지 않는다. 2초는 로컬·
 * LTE에서 몇 백 ms에 끝나는 정상 업로드를 넉넉히 덮으면서, 실패했을 때 사용자가 "멈췄다"고
 * 느끼기 전에 웹으로 돌려보내는 값이다 — 그 뒤는 업로드 상태 바의 [재시도]가 받는다.
 */
private const val SUBMIT_HOLD_TIMEOUT_MS = 2_000L

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
            view.evaluateJavascript(itemResultDeliveryJs(result), null)
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
             * 퇴장 페이드 동안에도 그릴 내용이 필요하다 (KAN-146). phase가 Web으로 바뀌는 순간
             * 문항과 단계가 함께 사라지는데, 오버레이는 아직 화면에 남아 사라지는 중이다.
             * 단계까지 붙드는 이유: 문항만 기억하면 퇴장하는 동안 '제출 중…'이 다시 녹음 화면으로
             * 되돌아가 보인다. 등장·전환할 때는 현재 phase가 곧바로 있으므로 이 기억이 한 프레임
             * 늦어도 상관없고, 퇴장할 때만 마지막 값이 쓰인다.
             */
            val livePhase = phase.takeIf {
                it is TestFlowPhase.Recording || it is TestFlowPhase.Submitting
            }
            var lastLivePhase by remember { mutableStateOf<TestFlowPhase?>(null) }
            // LaunchedEffect가 아니라 SideEffect다 — 한 프레임 안에서 등장과 퇴장이 연달아 일어나면
            // 키가 바뀌며 이펙트가 실행 전에 취소돼 기억이 비고, 퇴장 페이드가 빈 화면으로 돈다.
            SideEffect {
                if (livePhase != null) lastLivePhase = livePhase
            }
            val overlayPhase = livePhase ?: lastLivePhase

            /*
             * 붙들어 둔 화면의 상한 (KAN-146). 업로드가 실패하면 결과는 영영 나가지 않으므로
             * 컨트롤러 혼자서는 이 화면을 걷을 수 없다 — 시간을 아는 쪽이 여기라서 여기서 건다.
             * attemptId를 키로 두어, 결과가 먼저 나가 다음 문항으로 넘어가면 이 타이머는 취소된다.
             */
            val submittingAttemptId = (phase as? TestFlowPhase.Submitting)?.attemptId
            LaunchedEffect(submittingAttemptId) {
                val attemptId = submittingAttemptId ?: return@LaunchedEffect
                delay(SUBMIT_HOLD_TIMEOUT_MS)
                flow.onSubmitTimeout(attemptId)
            }

            // 권한 게이트가 서 있는 동안에는 오버레이를 띄우지 않는다 — 예전 when 분기의 순서가
            // 주던 우선순위를 그대로 옮긴 것이다. 게이트는 페이드 없이 즉시 서고 걷힌다.
            val gateShowing = startRequested && !testEntered || phase is TestFlowPhase.NeedsPermission
            when {
                // 시작 게이트. 통과하면 테스트 URL이 로드되고 조건이 풀려 오버레이가 사라진다.
                startRequested && !testEntered -> PermissionGate(onGranted = { testEntered = true })

                // 문항 진입 시점의 게이트 — 통과하면 기다리던 문항의 녹음으로 곧장 들어간다.
                phase is TestFlowPhase.NeedsPermission -> PermissionGate(onGranted = flow::onPermissionGranted)
            }

            // 패키지까지 적는 이유: 바깥 Column 때문에 ColumnScope 확장이 먼저 잡혀,
            // Box 안에서 화면 전체를 덮어야 할 오버레이가 열 방향 배치로 해석된다.
            androidx.compose.animation.AnimatedVisibility(
                visible = !gateShowing && livePhase != null,
                enter = fadeIn(tween(OVERLAY_FADE_MS)),
                exit = fadeOut(tween(OVERLAY_FADE_MS)),
            ) {
                // 퇴장 중에는 phase가 이미 Web이라 붙들어 둔 마지막 값이 쓰인다.
                val start = when (val shown = overlayPhase) {
                    is TestFlowPhase.Recording -> shown.start
                    is TestFlowPhase.Submitting -> shown.start
                    else -> null
                } ?: return@AnimatedVisibility
                val submitting = overlayPhase is TestFlowPhase.Submitting

                /*
                 * 녹음 상태 되감기는 오버레이가 완전히 걷힌 뒤다 (KAN-146).
                 * [다음] 자리에서 즉시 reset()을 부르면 퇴장 페이드 동안 화면이 대기 상태로 바뀌어,
                 * 방금까지 [재녹음][다음]이던 자리가 '● 녹음' 하나로 갈아치워진 채 사라진다.
                 *
                 * 조건은 "지금 보고 있던 것이 그대로 이어지는가"다. 회전은 이 컴포지션을 통째로
                 * 버렸다가 다시 만들므로 dispose가 돌지만 그때 phase는 그대로다 — 그 경우 되감으면
                 * 진행 중인 녹음이나 기다리는 중인 제출이 죽는다.
                 *
                 * 이어짐의 판정이 방향에 따라 다른 이유:
                 * - 녹음 중이었다면 같은 문항의 녹음이거나 그 문항의 제출로 넘어간 것까지가 이어짐이다.
                 *   [다음]으로 제출에 들어갈 때 되감으면 방금 그린 '내 억양' 곡선이 제출 화면에서 사라진다.
                 * - 제출을 기다리던 중이었다면 같은 문항의 제출만 이어짐이다. 제출에서 녹음으로 되돌아온
                 *   것은 그 문항을 처음부터 다시 하는 것이므로(웹이 결과를 못 받고 문항을 다시 열었을 때
                 *   생긴다) 반드시 되감아야 한다 — 안 그러면 이미 제출해 PCM이 빠져나간 확인 화면이
                 *   그대로 뜨고, 거기서 [다음]은 아무 일도 못 한다.
                 */
                DisposableEffect(start.itemId, submitting) {
                    onDispose {
                        val current = flow.phase
                        val continues = if (submitting) {
                            current is TestFlowPhase.Submitting && current.start.itemId == start.itemId
                        } else when (current) {
                            is TestFlowPhase.Recording -> current.start.itemId == start.itemId
                            is TestFlowPhase.Submitting -> current.start.itemId == start.itemId
                            else -> false
                        }
                        if (!continues) viewModel.reset()
                    }
                }

                RecordingOverlay(
                    start = start,
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
                                    itemId = start.itemId,
                                    wavBytes = WavWriter.toWavBytes(pcm),
                                    durationMs = durationMs,
                                    clientQuality = AudioQuality.measure(pcm),
                                ),
                                label = "${start.itemNumber}번 문항",
                            )
                            // 업로드 완료를 기다리지 않고 웹으로 돌아간다 — 결과는 준비되는 대로
                            // 위의 LaunchedEffect가 따로 실어 보낸다.
                            flow.onRecordingFinished(attemptId, durationMs, quality)
                        }
                    },
                    onExit = {
                        // 이탈은 페이드를 기다리지 않고 그 자리에서 되감는다 — 녹음 중단·마이크 해제·
                        // PCM 폐기는 FR-DP-02가 즉시를 요구한다. 퇴장 동안 대기 화면이 잠깐 비치는
                        // 대가는 받아들인다.
                        viewModel.reset()
                        // 하네스와 달리 진행 전체를 초기화하지 않는다 — 나가기는 이 문항을 다시
                        // 시도하겠다는 뜻이라, 앞 문항들의 대기 시도까지 버리면 이미 끝난 업로드의
                        // 결과가 웹에 영영 도착하지 않는다 (TestFlowController.onRecordingExit 주석).
                        flow.onRecordingExit()
                    },
                )
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
    Surface(modifier = Modifier.fillMaxSize()) {
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
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(headline)
            Text(supporting)
            Button(onClick = onButtonClick) {
                Text(buttonLabel)
            }
        }
    }
}
