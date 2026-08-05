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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.accentury.app.recording.RecordingScreen
import com.accentury.app.ui.theme.AccenturyTheme

private val DEV_QUESTIONS = listOf(
    "마! 니 어데 가노?",
    "밥은 뭇나?",
    "고마 치아라 마",
)

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
        var questionIndex by remember { mutableIntStateOf(0) }
        RecordingScreen(
            questionText = DEV_QUESTIONS[questionIndex % DEV_QUESTIONS.size],
            questionIndex = (questionIndex % DEV_QUESTIONS.size) + 1,
            totalQuestions = DEV_QUESTIONS.size,
            onNext = { _, _ -> questionIndex++ },
            onExit = { questionIndex = 0 },
        )
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
