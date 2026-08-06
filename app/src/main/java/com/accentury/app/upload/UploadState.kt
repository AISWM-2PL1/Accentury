package com.accentury.app.upload

sealed interface UploadState {

    data object InFlight : UploadState

    data class Done(val analysisJobId: String) : UploadState

    data class Failed(val retryable: Boolean, val message: String?) : UploadState
}
