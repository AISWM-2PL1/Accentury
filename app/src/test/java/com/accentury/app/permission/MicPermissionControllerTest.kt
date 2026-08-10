package com.accentury.app.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class MicPermissionControllerTest {

    @Test
    fun `이미 허용된 상태로 게이트에 들어오면 안내 화면 없이 바로 통과한다`() {
        val controller = MicPermissionController(initiallyGranted = true)
        assertEquals(MicPermissionState.Granted, controller.state)
    }

    @Test
    fun `허용 전이면 OS 팝업에 앞서 자체 안내 화면부터 보여준다`() {
        val controller = MicPermissionController(initiallyGranted = false)
        assertEquals(MicPermissionState.Rationale, controller.state)
    }

    @Test
    fun `팝업에서 허용하면 통과한다`() {
        val controller = MicPermissionController(initiallyGranted = false)
        controller.onPermissionResult(granted = true, canAskAgain = false)
        assertEquals(MicPermissionState.Granted, controller.state)
    }

    @Test
    fun `거부해도 재요청이 가능하면 Denied - 다시 요청 버튼 경로`() {
        val controller = MicPermissionController(initiallyGranted = false)
        controller.onPermissionResult(granted = false, canAskAgain = true)
        assertEquals(MicPermissionState.Denied, controller.state)
    }

    @Test
    fun `거부인데 재요청까지 막혔으면 PermanentlyDenied - 설정 딥링크만 남는다`() {
        val controller = MicPermissionController(initiallyGranted = false)
        controller.onPermissionResult(granted = false, canAskAgain = false)
        assertEquals(MicPermissionState.PermanentlyDenied, controller.state)
    }

    @Test
    fun `재요청에서 다시 거부하면 영구 거부로 굳는다 - Android 2회 거부 정책`() {
        val controller = MicPermissionController(initiallyGranted = false)
        controller.onPermissionResult(granted = false, canAskAgain = true)
        controller.onPermissionResult(granted = false, canAskAgain = false)
        assertEquals(MicPermissionState.PermanentlyDenied, controller.state)
    }

    @Test
    fun `영구 거부 후 설정에서 허용하고 돌아오면 재시작 없이 통과한다`() {
        val controller = MicPermissionController(initiallyGranted = false)
        controller.onPermissionResult(granted = false, canAskAgain = false)
        controller.onReturnedToApp(granted = true)
        assertEquals(MicPermissionState.Granted, controller.state)
    }

    @Test
    fun `설정에서 허용하지 않고 돌아오면 상태가 그대로다`() {
        val controller = MicPermissionController(initiallyGranted = false)
        controller.onPermissionResult(granted = false, canAskAgain = false)
        controller.onReturnedToApp(granted = false)
        assertEquals(MicPermissionState.PermanentlyDenied, controller.state)
    }

    @Test
    fun `허용 뒤에 도착한 복귀 재확인이 상태를 되돌리지 않는다`() {
        val controller = MicPermissionController(initiallyGranted = false)
        controller.onPermissionResult(granted = true, canAskAgain = false)
        controller.onReturnedToApp(granted = false)
        assertEquals(MicPermissionState.Granted, controller.state)
    }

    @Test
    fun `회전 후 복원돼도 영구 거부가 유지된다 - 설정 딥링크 경로를 잃지 않는다`() {
        val controller = MicPermissionController.restored(
            saved = MicPermissionState.PermanentlyDenied,
            currentlyGranted = false,
        )
        assertEquals(MicPermissionState.PermanentlyDenied, controller.state)
    }

    @Test
    fun `회전 후 복원돼도 소프트 거부가 유지된다`() {
        val controller = MicPermissionController.restored(
            saved = MicPermissionState.Denied,
            currentlyGranted = false,
        )
        assertEquals(MicPermissionState.Denied, controller.state)
    }

    @Test
    fun `복원 시점에 실제로 허용돼 있으면 저장값과 무관하게 통과한다`() {
        val controller = MicPermissionController.restored(
            saved = MicPermissionState.PermanentlyDenied,
            currentlyGranted = true,
        )
        assertEquals(MicPermissionState.Granted, controller.state)
    }

    @Test
    fun `저장값은 Granted인데 실제로는 회수됐으면 처음부터 다시 묻는다`() {
        val controller = MicPermissionController.restored(
            saved = MicPermissionState.Granted,
            currentlyGranted = false,
        )
        assertEquals(MicPermissionState.Rationale, controller.state)
    }
}
