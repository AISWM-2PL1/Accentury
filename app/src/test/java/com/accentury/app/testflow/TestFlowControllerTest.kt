package com.accentury.app.testflow

import androidx.compose.runtime.saveable.SaverScope
import com.accentury.app.audio.QualityStatus
import com.accentury.app.bridge.VoiceItemStart
import com.accentury.app.upload.UploadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestFlowControllerTest {

    @Test
    fun `처음에는 웹이 전면이다`() {
        val controller = TestFlowController()
        assertEquals(TestFlowPhase.Web, controller.phase)
    }

    @Test
    fun `권한이 있으면 VOICE 진입이 곧장 녹음 화면으로 간다`() {
        val controller = TestFlowController()
        val start = voiceItem()

        controller.onStartVoiceItem(start, micGranted = true)

        assertEquals(TestFlowPhase.Recording(start), controller.phase)
    }

    @Test
    fun `권한이 없으면 게이트를 먼저 세운다 - 설정에서 회수된 경우`() {
        val controller = TestFlowController()
        val start = voiceItem()

        controller.onStartVoiceItem(start, micGranted = false)

        assertEquals(TestFlowPhase.NeedsPermission(start), controller.phase)
    }

    @Test
    fun `게이트를 통과하면 기다리던 문항으로 이어간다 - 문항을 잃지 않는다`() {
        val controller = TestFlowController()
        val start = voiceItem()
        controller.onStartVoiceItem(start, micGranted = false)

        controller.onPermissionGranted()

        assertEquals(TestFlowPhase.Recording(start), controller.phase)
    }

    @Test
    fun `녹음 중 도착한 중복 요청은 무시한다 - 진행 중인 녹음을 갈아치우지 않는다`() {
        val controller = TestFlowController()
        val first = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(first, micGranted = true)

        controller.onStartVoiceItem(voiceItem(itemId = "item_2", number = 2), micGranted = true)

        assertEquals(TestFlowPhase.Recording(first), controller.phase)
    }

    @Test
    fun `게이트가 서 있는 동안 도착한 요청도 무시한다`() {
        val controller = TestFlowController()
        val first = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(first, micGranted = false)

        controller.onStartVoiceItem(voiceItem(itemId = "item_2", number = 2), micGranted = true)

        assertEquals(TestFlowPhase.NeedsPermission(first), controller.phase)
    }

    @Test
    fun `게이트가 없을 때 오는 허용 통지는 무시한다 - 설정 복귀 재확인이 화면을 바꾸지 않는다`() {
        val controller = TestFlowController()

        controller.onPermissionGranted()

        assertEquals(TestFlowPhase.Web, controller.phase)
    }

    @Test
    fun `녹음 중에 오는 허용 통지도 화면을 되돌리지 않는다`() {
        val controller = TestFlowController()
        val start = voiceItem()
        controller.onStartVoiceItem(start, micGranted = true)

        controller.onPermissionGranted()

        assertEquals(TestFlowPhase.Recording(start), controller.phase)
    }

    @Test
    fun `녹음을 마치면 업로드를 기다리지 않고 웹으로 돌아간다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted = true)

        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        assertEquals(TestFlowPhase.Web, controller.phase)
    }

    @Test
    fun `업로드가 끝나면 그 시도의 결과가 웹으로 나갈 준비가 된다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId = "item_1"), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        val results = controller.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1")))

        assertEquals(1, results.size)
        val result = results.single()
        assertEquals("item_1", result.itemId)
        assertEquals("at_1", result.attemptId)
        assertEquals("job_1", result.analysisJobId)
        assertEquals(3_200L, result.durationMs)
        assertEquals(QualityStatus.NORMAL, result.qualityStatus)
    }

    @Test
    fun `아직 진행 중인 업로드는 결과를 내보내지 않는다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        assertTrue(controller.onUploadsChanged(mapOf("at_1" to UploadState.InFlight)).isEmpty())
    }

    @Test
    fun `실패한 업로드는 재시도로 완료될 때 비로소 결과가 된다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        val whileFailed = controller.onUploadsChanged(
            mapOf("at_1" to UploadState.Failed(retryable = true, message = "timeout")),
        )
        assertTrue(whileFailed.isEmpty())

        val afterRetry = controller.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1")))
        assertEquals(listOf("at_1"), afterRetry.map { it.attemptId })
    }

    @Test
    fun `같은 시도를 두 번 내보내지 않는다 - 웹은 문항당 결과 1회를 전제로 진행한다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        val uploads = mapOf("at_1" to UploadState.Done("job_1"))

        assertEquals(1, controller.onUploadsChanged(uploads).size)
        assertTrue(controller.onUploadsChanged(uploads).isEmpty())
    }

    @Test
    fun `녹음 화면 밖에서 온 종료 통지는 시도로 등록하지 않는다`() {
        val controller = TestFlowController()

        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        assertEquals(TestFlowPhase.Web, controller.phase)
        assertTrue(controller.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1"))).isEmpty())
    }

    @Test
    fun `문항에서 나가면 시도 등록 없이 웹으로 돌아간다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted = true)

        controller.onRecordingExit()

        assertEquals(TestFlowPhase.Web, controller.phase)
        assertTrue(controller.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1"))).isEmpty())
    }

    @Test
    fun `나가기는 앞 문항의 대기 시도까지 버리지 않는다 - 진행 전체를 초기화하지 않는다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId = "item_1"), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        controller.onStartVoiceItem(voiceItem(itemId = "item_2", number = 2), micGranted = true)
        controller.onRecordingExit()

        val results = controller.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1")))
        assertEquals(listOf("item_1"), results.map { it.itemId })
    }

    @Test
    fun `나간 문항은 다시 요청할 수 있다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingExit()

        controller.onStartVoiceItem(start, micGranted = true)

        assertEquals(TestFlowPhase.Recording(start), controller.phase)
    }

    @Test
    fun `같은 문항을 다시 녹음해도 막지 않는다 - 중복 제출은 웹 상태 머신이 거른다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_2", durationMs = 4_100, quality = QualityStatus.NORMAL)

        val results = controller.onUploadsChanged(
            mapOf(
                "at_1" to UploadState.Done("job_1"),
                "at_2" to UploadState.Done("job_2"),
            ),
        )
        assertEquals(listOf("at_1", "at_2"), results.map { it.attemptId })
        assertEquals(listOf("item_1", "item_1"), results.map { it.itemId })
    }

    @Test
    fun `여러 문항이 한꺼번에 끝나도 등록 순서대로 나간다`() {
        val controller = TestFlowController()
        listOf("item_1", "item_2", "item_3").forEachIndexed { index, itemId ->
            controller.onStartVoiceItem(voiceItem(itemId = itemId, number = index + 1), micGranted = true)
            controller.onRecordingFinished("at_${index + 1}", durationMs = 3_000, quality = QualityStatus.NORMAL)
        }

        val results = controller.onUploadsChanged(
            mapOf(
                "at_2" to UploadState.Done("job_2"),
                "at_3" to UploadState.Done("job_3"),
                "at_1" to UploadState.Done("job_1"),
            ),
        )

        assertEquals(listOf("item_1", "item_2", "item_3"), results.map { it.itemId })
    }

    @Test
    fun `녹음 중 회전해도 녹음 화면이 유지된다 - 뷰모델은 살아남고 화면만 사라지면 진행이 멈춘다`() {
        val controller = TestFlowController()
        val start = voiceItem()
        controller.onStartVoiceItem(start, micGranted = true)

        val restored = rotate(controller)

        assertEquals(TestFlowPhase.Recording(start), restored.phase)
    }

    @Test
    fun `게이트 중 회전해도 게이트와 대기 문항이 유지된다`() {
        val controller = TestFlowController()
        val start = voiceItem()
        controller.onStartVoiceItem(start, micGranted = false)

        val restored = rotate(controller)

        assertEquals(TestFlowPhase.NeedsPermission(start), restored.phase)
        restored.onPermissionGranted()
        assertEquals(TestFlowPhase.Recording(start), restored.phase)
    }

    @Test
    fun `회전해도 대기 시도가 남아 뒤늦은 업로드 완료가 결과로 나간다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId = "item_1"), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.TOO_QUIET)

        val restored = rotate(controller)

        val results = restored.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1")))
        assertEquals(1, results.size)
        assertEquals("item_1", results.single().itemId)
        assertEquals(3_200L, results.single().durationMs)
        assertEquals(QualityStatus.TOO_QUIET, results.single().qualityStatus)
    }

    @Test
    fun `이미 내보낸 결과는 회전 뒤에도 다시 나가지 않는다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        val uploads = mapOf("at_1" to UploadState.Done("job_1"))
        controller.onUploadsChanged(uploads)

        val restored = rotate(controller)

        assertTrue(restored.onUploadsChanged(uploads).isEmpty())
    }

    @Test
    fun `업로드가 남아 있는 시도는 정리에서 살아남아 결과가 된다 - 회전 경로`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId = "item_1"), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        val restored = rotate(controller)
        restored.pruneAttemptsWithoutUpload(setOf("at_1"))

        val results = restored.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1")))
        assertEquals(listOf("item_1"), results.map { it.itemId })
    }

    @Test
    fun `업로드가 사라진 시도는 걷어낸다 - 프로세스 사망 복원의 가짜 대기`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId = "item_1"), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        val restored = rotate(controller)
        restored.pruneAttemptsWithoutUpload(emptySet())

        // 뒤늦게 같은 키의 완료가 들어와도 이 시도는 다시 살아나지 않는다.
        assertTrue(restored.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1"))).isEmpty())
    }

    @Test
    fun `정리는 아는 시도만 남긴다 - 섞여 있어도 등록 순서를 지킨다`() {
        val controller = TestFlowController()
        listOf("item_1", "item_2", "item_3").forEachIndexed { index, itemId ->
            controller.onStartVoiceItem(voiceItem(itemId = itemId, number = index + 1), micGranted = true)
            controller.onRecordingFinished("at_${index + 1}", durationMs = 3_000, quality = QualityStatus.NORMAL)
        }

        controller.pruneAttemptsWithoutUpload(setOf("at_1", "at_3"))

        val results = controller.onUploadsChanged(
            mapOf(
                "at_1" to UploadState.Done("job_1"),
                "at_2" to UploadState.Done("job_2"),
                "at_3" to UploadState.Done("job_3"),
            ),
        )
        assertEquals(listOf("at_1", "at_3"), results.map { it.attemptId })
    }

    @Test
    fun `정리는 지금 덮여 있는 화면을 건드리지 않는다`() {
        val controller = TestFlowController()
        val start = voiceItem()
        controller.onStartVoiceItem(start, micGranted = true)

        controller.pruneAttemptsWithoutUpload(emptySet())

        assertEquals(TestFlowPhase.Recording(start), controller.phase)
    }

    @Test
    fun `저장값이 깨져 있으면 복원하지 않는다 - 새 컨트롤러로 시작한다`() {
        assertNull(with(TestFlowController.saver()) { restore("{not json") })
    }

    /** rememberSaveable이 구성 변경에서 하는 일(save → restore)을 그대로 흉내 낸다. */
    private fun rotate(controller: TestFlowController): TestFlowController {
        val saver = TestFlowController.saver()
        val saved = with(saver) { SaverScope { true }.save(controller) }
        return checkNotNull(saved?.let { with(saver) { restore(it) } })
    }

    private fun voiceItem(itemId: String = "item_1", number: Int = 1) = VoiceItemStart(
        itemId = itemId,
        prompt = "마! 니 어데 가노?",
        itemNumber = number,
        totalItems = 3,
        maxDurationMs = 15_000,
    )
}
