package com.accentury.app.bridge

import com.accentury.app.audio.QualityStatus
import com.accentury.app.upload.UploadState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// toJson마다 새로 만들면 직렬화기 캐시가 매번 버려진다. 파일 안에서 하나만 쓴다.
private val json = Json

/**
 * React가 문항 하나에 대해 돌려받는 전부 (KAN-89 계약).
 *
 * 원본 PCM은 이 타입에 실을 수 없다 — ByteArray/ShortArray 계열 필드를 두지 않는 것이
 * 계약의 핵심이다. 음성은 업로드 경로로만 서버에 가고 JS 브리지는 경유하지 않는다 (FR-DP-02).
 * 필드를 늘릴 일이 생기면 이 5개가 계약이라는 점을 먼저 확인할 것.
 */
@Serializable
data class ItemResult(
    val itemId: String,
    val attemptId: String,
    val analysisJobId: String,
    val durationMs: Long,
    val qualityStatus: QualityStatus,
) {
    /** 브리지가 JS로 넘길 payload. enum은 kotlinx-serialization이 이름 문자열("NORMAL")로 내보낸다. */
    fun toJson(): String = json.encodeToString(serializer(), this)
}

/**
 * 조립에 필요한 녹음 쪽 메타. 여기에도 PCM은 담지 않는다 (FR-DP-02).
 * 업로드가 끝날 때까지 하네스가 attemptId별로 들고 있는 값이다.
 */
data class ItemAttempt(
    val itemId: String,
    val attemptId: String,
    val durationMs: Long,
    val quality: QualityStatus,
)

/**
 * 해당 시도의 업로드가 [UploadState.Done]일 때만 [ItemResult]를 만든다.
 * 진행 중·실패·이미 폐기된(맵에 없는) 키는 아직 React에 줄 게 없다는 뜻이라 null이다.
 */
fun assembleItemResult(meta: ItemAttempt, uploads: Map<String, UploadState>): ItemResult? {
    val done = uploads[meta.attemptId] as? UploadState.Done ?: return null
    return ItemResult(
        itemId = meta.itemId,
        attemptId = meta.attemptId,
        analysisJobId = done.analysisJobId,
        durationMs = meta.durationMs,
        qualityStatus = meta.quality,
    )
}
