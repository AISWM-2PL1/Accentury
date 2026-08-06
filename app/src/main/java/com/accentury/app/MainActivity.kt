package com.accentury.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accentury.app.audio.AudioQuality
import com.accentury.app.audio.WavWriter
import com.accentury.app.recording.RecordingScreen
import com.accentury.app.recording.RecordingViewModel
import com.accentury.app.ui.theme.AccenturyTheme
import com.accentury.app.upload.OkHttpUploadClient
import com.accentury.app.upload.UploadManager
import com.accentury.app.upload.UploadRequest
import com.accentury.app.upload.UploadStatusBar
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AccenturyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PermissionGate(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun PermissionGate(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    if (granted) {
        RecordingHarness(modifier = modifier)
    } else {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("발음 분석에 마이크가 필요해요")
            Text("음성은 분석 즉시 삭제돼요")
            Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("마이크 허용")
            }
        }
    }
}

@Composable
private fun RecordingHarness(modifier: Modifier = Modifier) {
    var questionIndex by remember { mutableIntStateOf(0) }
    // [테스트 종료]로 이 값을 올리면 스코프·매니저·라벨이 통째로 새로 만들어진다 (하네스용 초기화).
    var runId by remember { mutableIntStateOf(0) }

    // rememberCoroutineScope는 컴포지션이 살아 있는 동안 취소할 방법이 없고,
    // 자식 하나가 실패하면 형제 업로드까지 같이 죽는다. SupervisorJob으로 업로드끼리 격리하고
    // 직렬화·바이트 처리는 UI 스레드 밖(Default)에서 돌린다. 회전 시 취소되는 건 하네스라 감수한다.
    val uploadScope = remember(runId) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(uploadScope) { onDispose { uploadScope.cancel() } }

    val uploadManager = remember(uploadScope) {
        UploadManager(
            client = OkHttpUploadClient(DEV_BASE_URL),
            scope = uploadScope,
            sessionId = DEV_SESSION_ID,
            sessionToken = DEV_SESSION_TOKEN,
        )
    }
    // UploadState는 itemId를 들고 있지 않아, 실패 표시에 쓸 문항 라벨은 하네스가 따로 기억한다.
    val labels = remember(uploadScope) { mutableStateMapOf<String, String>() }
    val uploads by uploadManager.uploads.collectAsStateWithLifecycle()

    // RecordingScreen이 기본값으로 잡는 것과 같은 인스턴스. onNext에서 PCM을 꺼내려면 여기서도 필요하다.
    val viewModel: RecordingViewModel = viewModel()

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            val itemNumber = (questionIndex % DEV_QUESTIONS.size) + 1
            RecordingScreen(
                questionText = DEV_QUESTIONS[questionIndex % DEV_QUESTIONS.size],
                questionIndex = itemNumber,
                totalQuestions = DEV_QUESTIONS.size,
                onNext = { attemptId, durationMs ->
                    // consumeRecording은 PCM을 넘기면서 뷰모델에서 지운다 (FR-DP-02: 보관하지 않음).
                    viewModel.consumeRecording()?.let { pcm ->
                        labels[attemptId] = "${itemNumber}번 문항"
                        uploadManager.enqueue(
                            UploadRequest(
                                attemptId = attemptId,
                                itemId = "item_$itemNumber",
                                wavBytes = WavWriter.toWavBytes(pcm),
                                durationMs = durationMs,
                                clientQuality = AudioQuality.measure(pcm),
                            ),
                        )
                    }
                    // 업로드 완료를 기다리지 않고 바로 다음 문항으로 넘어간다.
                    questionIndex++
                },
                onExit = { questionIndex = 0 },
                viewModel = viewModel,
            )
        }
        UploadStatusBar(
            uploads = uploads,
            labelOf = { attemptId -> labels[attemptId] ?: "문항" },
            onRetry = uploadManager::retry,
            onEndTest = {
                viewModel.reset()
                questionIndex = 0
                runId++
            },
        )
    }
}
