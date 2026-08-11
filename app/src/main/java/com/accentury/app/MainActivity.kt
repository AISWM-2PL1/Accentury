package com.accentury.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import com.accentury.app.audio.WavWriter
import com.accentury.app.bridge.ItemAttempt
import com.accentury.app.bridge.assembleItemResult
import com.accentury.app.permission.MicPermissionController
import com.accentury.app.permission.MicPermissionState
import com.accentury.app.recording.RecordingScreen
import com.accentury.app.recording.RecordingViewModel
import com.accentury.app.ui.theme.AccenturyTheme
import com.accentury.app.upload.OkHttpUploadClient
import com.accentury.app.upload.UploadManager
import com.accentury.app.upload.UploadRequest
import com.accentury.app.upload.UploadStatusBar
import com.accentury.app.web.WebViewHost
import com.accentury.app.web.buildWebUrl
import com.accentury.app.web.webOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

private val DEV_QUESTIONS = listOf(
    "마! 니 어데 가노?",
    "밥은 뭇나?",
    "고마 치아라 마",
)

// 에뮬레이터에서 호스트 머신을 가리키는 주소. 실기기 테스트는 이 값만 바꾸면 된다.
private const val DEV_BASE_URL = "http://10.0.2.2:8080"

// KAN-9 세션 클라이언트가 붙으면 서버가 발급한 세션 값으로 교체된다.
private const val DEV_SESSION_ID = "dev-session"
private const val DEV_SESSION_TOKEN = "dev-token"

// 브리지가 붙기 전까지 조립된 payload를 눈으로 확인하는 통로.
private const val BRIDGE_TAG = "BridgeResult"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AccenturyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // 인트로(웹) → [시작하기] 브리지 → 마이크 권한 게이트 순서 (KAN-97).
                    // 인트로는 ux-ui.md §7 정본대로 WebView 원격 로드다 — 웹의 [시작하기]가
                    // AccenturyBridge.requestMicPermission()을 호출하면 게이트로 전환한다.
                    // 회전 등 구성 변경으로 인트로가 다시 뜨면 안 되므로 rememberSaveable로 남긴다.
                    var started by rememberSaveable { mutableStateOf(false) }
                    if (started) {
                        PermissionGate(modifier = Modifier.padding(innerPadding))
                    } else {
                        WebViewHost(
                            url = buildWebUrl(BuildConfig.WEB_URL, BuildConfig.VERSION_NAME),
                            allowedOrigins = setOfNotNull(webOrigin(BuildConfig.WEB_URL)),
                            onRequestMicPermission = { started = true },
                            // 녹음 화면 전환 결선은 Stage 4 몫이다. 여기서는 계약만 채운다.
                            onStartVoiceItem = {},
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 마이크 권한 게이트 (KAN-98). 거부하면 테스트를 시작할 수 없다 — 부분 응시 없음 (2026-07-27 확정).
 * 판단 로직은 [MicPermissionController]에 있고, 여기는 Android API 결선(팝업·설정 딥링크·
 * ON_RESUME 재확인)과 상태별 화면만 담당한다. 문구 확정 전이라 무디자인이다 (KAN-97 방식 준용).
 */
@Composable
private fun PermissionGate(modifier: Modifier = Modifier) {
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
        MicPermissionState.Granted -> RecordingHarness(modifier = modifier)

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

@Composable
private fun GateScreen(
    headline: String,
    supporting: String,
    buttonLabel: String,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
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

@Composable
private fun RecordingHarness(modifier: Modifier = Modifier) {
    var questionIndex by remember { mutableIntStateOf(0) }

    // rememberCoroutineScope는 컴포지션이 살아 있는 동안 취소할 방법이 없고,
    // 자식 하나가 실패하면 형제 업로드까지 같이 죽는다. SupervisorJob으로 업로드끼리 격리하고
    // 직렬화·바이트 처리는 UI 스레드 밖(Default)에서 돌린다. 회전 시 취소되는 건 하네스라 감수한다.
    val uploadScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    val uploadManager = remember(uploadScope) {
        UploadManager(
            client = OkHttpUploadClient(DEV_BASE_URL),
            scope = uploadScope,
            sessionId = DEV_SESSION_ID,
            sessionToken = DEV_SESSION_TOKEN,
        )
    }
    // 스코프만 취소하면 register~start 사이의 시도가 InFlight·원본으로 남을 수 있다.
    // clearAll을 먼저 불러 음성 바이트·상태를 확정 폐기한 뒤 스코프를 내린다 (FR-DP-02).
    DisposableEffect(uploadScope) {
        onDispose {
            uploadManager.clearAll()
            uploadScope.cancel()
        }
    }
    // UploadState는 itemId를 들고 있지 않아, 실패 표시에 쓸 문항 라벨은 하네스가 따로 기억한다.
    val labels = remember(uploadScope) { mutableStateMapOf<String, String>() }
    // 브리지 계약(KAN-89) 조립에 필요한 녹음 쪽 메타. 업로드가 Done이 되는 순간 소비하고 지운다.
    val attempts = remember(uploadScope) { mutableStateMapOf<String, ItemAttempt>() }
    val uploads by uploadManager.uploads.collectAsStateWithLifecycle()

    // 브리지(WebView JavascriptInterface)는 KAN-11에서 붙는다. 그전까지는 로그가 유일한 소비자다.
    LaunchedEffect(uploads) {
        attempts.keys.toList().forEach { attemptId ->
            val meta = attempts[attemptId] ?: return@forEach
            val result = assembleItemResult(meta, uploads) ?: return@forEach
            attempts.remove(attemptId) // 같은 시도를 두 번 내보내지 않는다.
            if (BuildConfig.DEBUG) android.util.Log.d(BRIDGE_TAG, result.toJson())
        }
    }

    // RecordingScreen이 기본값으로 잡는 것과 같은 인스턴스. onNext에서 PCM을 꺼내려면 여기서도 필요하다.
    val viewModel: RecordingViewModel = viewModel()

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            val itemNumber = (questionIndex % DEV_QUESTIONS.size) + 1
            RecordingScreen(
                questionText = DEV_QUESTIONS[questionIndex % DEV_QUESTIONS.size],
                questionIndex = itemNumber,
                totalQuestions = DEV_QUESTIONS.size,
                onNext = { attemptId, durationMs, quality ->
                    // consumeRecording은 PCM을 넘기면서 뷰모델에서 지운다 (FR-DP-02: 보관하지 않음).
                    viewModel.consumeRecording()?.let { pcm ->
                        val itemId = "item_$itemNumber"
                        labels[attemptId] = "${itemNumber}번 문항"
                        attempts[attemptId] = ItemAttempt(
                            itemId = itemId,
                            attemptId = attemptId,
                            durationMs = durationMs,
                            quality = quality,
                        )
                        uploadManager.enqueue(
                            UploadRequest(
                                attemptId = attemptId,
                                itemId = itemId,
                                wavBytes = WavWriter.toWavBytes(pcm),
                                durationMs = durationMs,
                                clientQuality = AudioQuality.measure(pcm),
                            ),
                        )
                    }
                    // 업로드 완료를 기다리지 않고 바로 다음 문항으로 넘어간다.
                    questionIndex++
                },
                onExit = {
                    // 문항 이탈이면 아직 안 끝난 업로드의 음성 바이트도 남길 이유가 없다 (FR-DP-02).
                    uploadManager.clearAll()
                    labels.clear()
                    attempts.clear()
                    questionIndex = 0
                },
                viewModel = viewModel,
            )
        }
        UploadStatusBar(
            uploads = uploads,
            labelOf = { attemptId -> labels[attemptId] ?: "문항" },
            onRetry = uploadManager::retry,
            onEndTest = {
                // 스코프를 통째로 갈아끼우는 대신 매니저가 직접 폐기한다. 진행 중 전송까지 끊기고,
                // SupervisorJob 스코프는 그대로 살아 있어 다음 테스트가 같은 매니저를 쓴다.
                viewModel.reset()
                uploadManager.clearAll()
                labels.clear()
                attempts.clear()
                questionIndex = 0
            },
        )
    }
}
