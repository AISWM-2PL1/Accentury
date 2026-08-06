package com.accentury.app.intro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// KAN-10 활성 테스트 정의 API 연동 시 서버가 내려주는 값으로 교체된다.
// 그때까지는 KAN-10 확정값(음성 5 + 어휘 5 = 10문항)을 상수로 둔다.
internal const val VOICE_ITEM_COUNT = 5
internal const val VOCABULARY_ITEM_COUNT = 5

// ux-ui.md "진입→결과 3분 이내" 목표에서 온 값. 이것도 KAN-10 연동 시 교체 대상이다.
internal const val ESTIMATED_MINUTES = 3

/**
 * 테스트 인트로 (FR-TS-01). 권역 선택·동의 단계 없이 앱의 첫 화면이다.
 * 디자인은 붙이지 않는다 — 무엇을 하는 테스트인지 알리고 마이크 권한 게이트로 넘기는 것까지가 이 화면의 일이다.
 */
@Composable
fun IntroScreen(onStart: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("사투리 억양 테스트", fontSize = 24.sp)
        Text(compositionText(VOICE_ITEM_COUNT, VOCABULARY_ITEM_COUNT), fontSize = 16.sp)
        Text(estimatedDurationText(ESTIMATED_MINUTES), fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        // 기본 Button의 최소 터치 타겟이 48dp라 ux-ui.md 최소선을 그대로 충족한다.
        Button(onClick = onStart) { Text("시작하기") }
    }
}

/** 문항 구성 한 줄 요약. 이모지는 음성/단어를 눈으로 구분시키는 용도다. */
internal fun compositionText(voiceCount: Int, vocabularyCount: Int): String =
    "🎤 음성 ${voiceCount}문항 + 📝 단어 ${vocabularyCount}문항 (총 ${voiceCount + vocabularyCount}문항)"

/** 예상 소요 시간 문구. 정확한 값이 아니라 각오를 잡아주는 값이라 "약"을 붙인다. */
internal fun estimatedDurationText(minutes: Int): String = "예상 소요 시간 약 ${minutes}분"
