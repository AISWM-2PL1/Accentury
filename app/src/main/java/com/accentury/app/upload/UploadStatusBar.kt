package com.accentury.app.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.accentury.app.ui.components.AccenturyButton
import com.accentury.app.ui.components.ButtonVariant
import com.accentury.app.ui.theme.Spacing

/**
 * 성공(Done)은 조용히 넘어가고, 진행 중 개수와 실패 건의 복구 경로만 보여준다.
 *
 * 복구 경로는 [재시도] 하나다. 재시도 불가 실패에도 출구를 두지 않는다 (KAN-191, ux-ui.md
 * §4-D·§4-F): 이 바는 **정상 진행 중에 떠 있는 화면**이라 여기에 이탈 버튼을 달면 KAN-147이
 * 지운 것을 되살리는 셈이고, 업로드 한 건 때문에 아직 멀쩡한 응시 전체에 나가는 문이 생긴다.
 * 흐름은 여기서 끝나지 않는다 — 남은 문항을 마저 풀면 분석 대기 화면으로 이어지고 출구는
 * 거기 있다([다시 녹음] / 세션 단위 실패면 [다시 테스트하기]).
 *
 * 무디자인 하네스다 — 여기서는 상태가 보이는지만 확인한다.
 */
@Composable
fun UploadStatusBar(
    uploads: Map<String, UploadState>,
    labelOf: (attemptId: String) -> String,
    onRetry: (attemptId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = summarize(uploads)
    if (summary.inFlight == 0 && summary.failed.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.x4, vertical = Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        if (summary.inFlight > 0) {
            Text("업로드 중 ${summary.inFlight}건", style = MaterialTheme.typography.labelLarge)
        }

        summary.failed.forEach { (attemptId, failed) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                Text(
                    "${labelOf(attemptId)} 업로드 실패 — ${failed.message ?: "알 수 없는 오류"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                // 재시도 불가 실패는 같은 바이트를 다시 보내도 결과가 같으므로 버튼을 주지 않는다.
                if (failed.retryable) {
                    AccenturyButton(
                        text = "재시도",
                        variant = ButtonVariant.Secondary,
                        onClick = { onRetry(attemptId) },
                    )
                }
            }
        }
    }
}

internal data class UploadSummary(
    val inFlight: Int,
    val failed: List<Pair<String, UploadState.Failed>>,
)

/** 표시 로직의 순수한 부분. 실패 목록은 업로드를 넣은 순서를 그대로 따른다. */
internal fun summarize(uploads: Map<String, UploadState>): UploadSummary = UploadSummary(
    inFlight = uploads.values.count { it is UploadState.InFlight },
    failed = uploads.entries.mapNotNull { (attemptId, state) ->
        (state as? UploadState.Failed)?.let { attemptId to it }
    },
)
