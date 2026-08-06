package com.accentury.app.upload

data class UploadRequest(
    val attemptId: String,
    val itemId: String,
    val wavBytes: ByteArray,
    val durationMs: Long,
) {
    // ByteArray는 기본 equals가 참조 비교라 data class 자동 구현을 쓸 수 없다.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UploadRequest) return false
        return attemptId == other.attemptId &&
            itemId == other.itemId &&
            durationMs == other.durationMs &&
            wavBytes.contentEquals(other.wavBytes)
    }

    override fun hashCode(): Int {
        var result = attemptId.hashCode()
        result = 31 * result + itemId.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + wavBytes.contentHashCode()
        return result
    }
}
