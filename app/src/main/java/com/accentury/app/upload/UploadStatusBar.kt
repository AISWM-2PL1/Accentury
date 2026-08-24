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
 * 복구 경로는 [재시도] 하나다 - 이탈 UX는 KAN-39 디자인 때 정한다 (KAN-147).
 * 진짜 화면은 KAN-39에서 디자인이 붙는다 — 여기서는 상태가 보이는지만 확인한다.
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
