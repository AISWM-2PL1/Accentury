package com.accentury.app.upload

sealed interface UploadState {

    data object InFlight : UploadState

    data class Done(val analysisJobId: String) : UploadState

    /**
     * 업로드 한 건이 실패로 확정된 상태.
     *
     * @property retryable 같은 바이트를 다시 보낼 값어치가 있는가. 화면의 [재시도] 버튼이 이 값 하나로 선다.
     * @property message 사용자에게 보일 이유. 서버 거절이면 서버가 준 문구고, 전송 실패면
     *   예외 종류로 고른 안내 문구다 (KAN-147) - OkHttp 예외 문구가 화면에 새지 않는다.
     * @property rerecord 같은 바이트를 다시 보내봐야 소용없고 녹음을 새로 해야 한다는 뜻 (KAN-147).
     *   서버가 녹음 자체를 거절한 코드(길이·용량·음량)에만 붙는다. 호출자(MainActivity)가 이 값을 보고
     *   업로드를 폐기하고 그 문항의 녹음 화면을 다시 연다.
     *
     * [retryable]과 [rerecord]는 동시에 true가 되지 않는다 - 재전송과 재녹음은 서로 다른 복구
     * 경로라, 둘을 함께 세우면 화면이 어느 쪽을 권하는지 말할 수 없다.
     */
    data class Failed(
        val retryable: Boolean,
        val message: String?,
        val rerecord: Boolean = false,
    ) : UploadState {
        init {
            // 문서로만 둔 불변식은 리팩터링 한 번에 깨진다. 만드는 자리에서 막아 두 복구 경로가
            // 한 화면에 겹치는 상태 자체가 생기지 않게 한다.
            require(!(retryable && rerecord)) { "재전송(retryable)과 재녹음(rerecord)은 함께 설 수 없다" }
        }
    }
}
