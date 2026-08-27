package com.accentury.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.accentury.app.ui.theme.Spacing

/**
 * 상태 블록의 성격. 스크린 리더 통지 여부만 갈린다 - 색으로는 갈리지 않는다 (KAN-161).
 */
enum class StatusTone { Waiting, Error }

/**
 * 대기·오류 문구 블록 (KAN-148). 웹의 `StatusBlock`과 같은 구성이다 —
 * 문구 + 부연 + 선택적 복구 동작.
 *
 * 오류일 때만 `liveRegion`을 건다. 화면이 이미 떠 있는 상태에서 나타나는 실패 문구는
 * 스크린 리더가 스스로 읽어 줘야 사용자가 알아챈다. 대기 문구에는 걸지 않는다 —
 * 로딩은 곧 바뀔 상태라 매번 읽어 주면 소음이 된다. 웹이 `role="alert"`를 오류에만
 * 붙이는 것과 같은 판단이다.
 *
 * **색 면도, 빨강도 없다** (KAN-161 2단계). 오류를 색으로 알리던 규칙이 사라졌으니
 * 두 톤에 다른 색을 줄 이유도 없다 - 무엇이 잘못됐는지는 잉크 문구가 말하고, 부연은
 * 대기든 오류든 흐린 잉크다. 복구 동작은 [action]에 보조 버튼으로 들어온다.
 */
@Composable
fun StatusBlock(
    tone: StatusTone,
    message: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (tone == StatusTone.Error) {
                    Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        action?.invoke()
    }
}
