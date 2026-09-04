package com.accentury.app.testflow

import androidx.compose.runtime.saveable.SaverScope
import com.accentury.app.audio.QualityStatus
import com.accentury.app.bridge.VoiceItemStart
import com.accentury.app.upload.UploadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /*
     * KAN-146으로 뒤집힌 결정이다. 예전에는 [다음] 즉시 웹으로 돌아갔지만, 결과는 업로드가 끝나야
     * 나가므로 그 사이 웹의 대기 화면이 한 번 드러났다. 진행이 업로드를 기다리지 않는다는 원칙은
     * 그대로다 - 붙드는 것은 화면뿐이고 대기 시도는 즉시 등록된다(아래 조립 테스트가 그걸 본다).
     */
    @Test
    fun `녹음을 마치면 결과가 나갈 때까지 화면을 붙든다`() {
        val controller = TestFlowController()
        val start = voiceItem()
        controller.onStartVoiceItem(start, micGranted = true)

        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        assertEquals(TestFlowPhase.Submitting(start, "at_1"), controller.phase)
    }

    /*
     * 결과를 조립했다는 것과 웹이 그것을 받아 다음 문항을 그렸다는 것은 다르다. 조립 자리에서 놓으면
     * 걷힌 아래에 아직 앞 문항의 대기 화면이 남아 한 프레임 드러난다(실기에서 33ms 노출로 확인).
     */
    @Test
    fun `결과를 조립한 것만으로는 화면을 놓지 않는다`() {
        val controller = TestFlowController()
        val start = voiceItem()
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        controller.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1")))

        assertEquals(TestFlowPhase.Submitting(start, "at_1"), controller.phase)
    }

    @Test
    fun `주입이 끝나면 그때 웹으로 돌아간다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        controller.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1")))

        controller.onResultDelivered("at_1")

        assertEquals(TestFlowPhase.Web, controller.phase)
    }

    /*
     * 앞 문항의 뒤늦은 주입 완료가 새로 뜬 화면을 걷어버리면, 사용자는 녹음 화면이 이유 없이
     * 사라지는 것을 본다.
     */
    @Test
    fun `다른 시도의 주입 완료는 지금 화면을 걷지 않는다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_3", number = 3)
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_3", durationMs = 3_200, quality = QualityStatus.NORMAL)

        controller.onResultDelivered("at_1")

        assertEquals(TestFlowPhase.Submitting(start, "at_3"), controller.phase)
    }

    @Test
    fun `업로드가 진행 중인 동안에는 화면을 계속 붙든다`() {
        val controller = TestFlowController()
        val start = voiceItem()
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        controller.onUploadsChanged(mapOf("at_1" to UploadState.InFlight))

        assertEquals(TestFlowPhase.Submitting(start, "at_1"), controller.phase)
    }

    /*
     * 업로드가 실패하면 결과는 영영 조립되지 않는다. 그걸 아는 자리에서 상한까지 기다리면 오버레이는
     * "제출 중…"이라 말하는데 같은 화면의 업로드 상태 바는 이미 "업로드 실패 [재시도]"를 띄운다 -
     * 한 화면이 서로 다른 두 말을 하는 구간이라 상한을 기다리지 않고 놓는다.
     */
    @Test
    fun `업로드 실패가 확정되면 상한을 기다리지 않고 웹으로 돌려보낸다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        controller.onUploadsChanged(mapOf("at_1" to UploadState.Failed(retryable = true, message = "연결 실패")))

        assertEquals(TestFlowPhase.Web, controller.phase)
    }

    /*
     * 상한은 위 두 경로(결과 도착·실패 확정) 어느 쪽도 오지 않는 경우를 받는 최후 안전망이다 -
     * 프로세스 사망 복원처럼 업로드 키 자체가 사라져 상태를 물어볼 곳이 없을 때가 그렇다.
     */
    @Test
    fun `업로드 자취가 없으면 상한이 지나서야 웹으로 돌려보낸다`() {
        val controller = TestFlowController()
        val start = voiceItem()
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        controller.onUploadsChanged(emptyMap())
        assertEquals(TestFlowPhase.Submitting(start, "at_1"), controller.phase)

        controller.onSubmitTimeout("at_1", emptyMap())

        assertEquals(TestFlowPhase.Web, controller.phase)
    }

    /*
     * 결과가 먼저 나가 다음 문항이 뜬 뒤 뒤늦게 도착한 타이머가 새 화면을 걷어버리면, 사용자는
     * 녹음 화면이 이유 없이 사라지는 것을 본다.
     */
    @Test
    fun `다른 시도의 상한 통지는 지금 화면을 걷지 않는다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_2")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_2", durationMs = 3_200, quality = QualityStatus.NORMAL)

        controller.onSubmitTimeout("at_1", emptyMap())

        assertEquals(TestFlowPhase.Submitting(start, "at_2"), controller.phase)
    }

    @Test
    fun `제출을 기다리는 중에 회전해도 같은 화면이 유지된다`() {
        val controller = TestFlowController()
        val start = voiceItem()
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        val restored = rotate(controller)

        assertEquals(TestFlowPhase.Submitting(start, "at_1"), restored.phase)
    }

    /*
     * 완화된 가드를 못 박는다 (KAN-146). 가드가 지키려는 것은 아직 손에 있는 녹음인데, 제출 뒤에는
     * PCM이 이미 업로드로 넘어가 잃을 것이 없다. 반대로 여기서 막으면 웹이 다음 문항으로 넘어갔는데
     * 네이티브가 따라가지 못해 진행이 멈춘다 - 진행의 정본은 웹이다.
     */
    @Test
    fun `제출을 기다리는 중 도착한 다음 문항 요청은 받아준다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId = "item_1"), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        val next = voiceItem(itemId = "item_3", number = 3)
        controller.onStartVoiceItem(next, micGranted = true)

        assertEquals(TestFlowPhase.Recording(next), controller.phase)
    }

    /*
     * 화면이 다음 문항으로 넘어가도 앞 시도는 대기 목록에 그대로 남아야 한다 - 붙드는 것은 화면뿐이지
     * 진행이 업로드를 기다리는 것이 아니다.
     */
    @Test
    fun `다음 문항으로 넘어가도 앞 시도의 결과는 그대로 실려 나간다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId = "item_1"), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        controller.onStartVoiceItem(voiceItem(itemId = "item_3", number = 3), micGranted = true)

        val results = controller.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1")))

        assertEquals(listOf("item_1"), results.map { it.itemId })
        // 앞 시도의 결과가 나가면서 새 문항의 녹음 화면을 실수로 걷어버리면 안 된다
        assertEquals(TestFlowPhase.Recording(voiceItem(itemId = "item_3", number = 3)), controller.phase)
    }

    /*
     * 타이머는 업로드가 목록에 오르기 전 한 프레임에도 걸릴 수 있다. 발화 시점에 다시 확인하지 않으면
     * 그새 시작된(또는 백그라운드에서 계속되던) 업로드를 시간이 끊어, 없애려던 대기 화면이 그 자리에
     * 생긴다.
     */
    @Test
    fun `상한이 와도 업로드가 진행 중이면 화면을 계속 붙든다`() {
        val controller = TestFlowController()
        val start = voiceItem()
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        controller.onSubmitTimeout("at_1", mapOf("at_1" to UploadState.InFlight))

        assertEquals(TestFlowPhase.Submitting(start, "at_1"), controller.phase)
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
    fun `PCM 없는 제출은 시도 등록 없이 웹으로 돌아간다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(), micGranted = true)

        controller.onRecordingExit()

        assertEquals(TestFlowPhase.Web, controller.phase)
        assertTrue(controller.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1"))).isEmpty())
    }

    @Test
    fun `돌아가기는 앞 문항의 대기 시도까지 버리지 않는다 - 진행 전체를 초기화하지 않는다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId = "item_1"), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        controller.onStartVoiceItem(voiceItem(itemId = "item_2", number = 2), micGranted = true)
        controller.onRecordingExit()

        val results = controller.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1")))
        assertEquals(listOf("item_1"), results.map { it.itemId })
    }

    @Test
    fun `돌아간 문항은 다시 요청할 수 있다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingExit()

        controller.onStartVoiceItem(start, micGranted = true)

        assertEquals(TestFlowPhase.Recording(start), controller.phase)
    }

    /*
     * 같은 문항의 재녹음은 여전히 막지 않는다. 다만 앞 시도는 여기서 밀려난다 (KAN-147, 지라
     * 코멘트 #2) - 한 문항에 살아 있는 시도가 둘이면 상태 바에 앞 시도의 [재시도]가 그대로 서 있고,
     * 그걸 누르면 같은 문항에 분석 작업이 둘 생겨 웹이 결과를 두 번 받는다.
     */
    @Test
    fun `같은 문항을 다시 녹음하면 앞 시도가 밀려난다 - 한 문항에 분석 작업이 둘 생기지 않는다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        assertEquals(
            emptyList<String>(),
            controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL),
        )

        controller.onStartVoiceItem(start, micGranted = true)
        val superseded =
            controller.onRecordingFinished("at_2", durationMs = 4_100, quality = QualityStatus.NORMAL)

        // 밀려난 시도의 업로드를 실제로 폐기하는 것은 호출자 몫이라 attemptId만 돌려준다.
        assertEquals(listOf("at_1"), superseded)

        val results = controller.onUploadsChanged(
            mapOf(
                "at_1" to UploadState.Done("job_1"),
                "at_2" to UploadState.Done("job_2"),
            ),
        )
        assertEquals(listOf("at_2"), results.map { it.attemptId })
        assertEquals(listOf("item_1"), results.map { it.itemId })
    }

    @Test
    fun `다른 문항의 대기 시도는 밀려나지 않는다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId = "item_1"), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        controller.onStartVoiceItem(voiceItem(itemId = "item_2", number = 2), micGranted = true)
        val superseded =
            controller.onRecordingFinished("at_2", durationMs = 3_200, quality = QualityStatus.NORMAL)

        assertEquals(emptyList<String>(), superseded)
        val results = controller.onUploadsChanged(
            mapOf(
                "at_1" to UploadState.Done("job_1"),
                "at_2" to UploadState.Done("job_2"),
            ),
        )
        assertEquals(listOf("at_1", "at_2"), results.map { it.attemptId })
    }

    /*
     * 재녹음 전환 - 서버가 이 녹음을 못 쓰겠다고 답한 경우다 (KAN-147, 2026-08-25 B안).
     * 전송 실패는 여기로 오지 않고 [재시도]가 계속 서 있는다. 웹은 네이티브 쪽 실패를 통지받지
     * 않아 그 문항의 대기 화면에 멈춰 있으므로, 네이티브가 같은 문항의 녹음 화면을 다시 열어도
     * 진행을 앞지르지 않는다.
     */
    @Test
    fun `업로드를 포기하면 그 문항의 녹음 화면을 다시 연다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        // 실패가 확정되면 화면은 웹으로 돌아가 있고 대기 시도만 남는다 (KAN-146).
        controller.onUploadsChanged(mapOf("at_1" to UploadState.Failed(retryable = false, message = "timeout")))
        assertEquals(TestFlowPhase.Web, controller.phase)

        assertTrue(controller.onUploadGivenUp("at_1", micGranted = true))

        assertEquals(TestFlowPhase.Recording(start, afterUploadFailure = true), controller.phase)
        // 결과가 영영 조립되지 않을 시도라 대기 목록에서도 빠진다.
        assertTrue(controller.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1"))).isEmpty())
    }

    /*
     * 제출을 기다리는 중에 포기가 확정되는 경로. 같은 문항의 녹음으로 되돌아가는 것은
     * continuesFrom이 이미 다루는 "제출에서 녹음으로 되돌아온 것"이라 호출자 쪽 되감기가 돈다.
     */
    @Test
    fun `제출을 기다리는 중에 포기해도 같은 문항의 녹음으로 되돌아간다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        assertTrue(controller.onUploadGivenUp("at_1", micGranted = true))

        assertEquals(TestFlowPhase.Recording(start, afterUploadFailure = true), controller.phase)
    }

    /*
     * MainActivity에서 포기 결선과 결과 전달 결선은 같은 키(uploads)로 도는 별개의 이펙트다.
     * 어느 쪽이 먼저 돌든 결과가 같아야 한다 - 이 두 테스트가 그 순서 독립성을 못 박는다.
     * onUploadsChanged가 Web으로 내리는 조건은 "Submitting에서 그 시도가 실패"뿐이라, 포기가
     * 먼저 열어 둔 Recording은 건드리지 않는다.
     */
    @Test
    fun `포기가 먼저 돌아도 뒤따른 실패 통지가 다시 연 녹음 화면을 걷지 않는다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        val failed = mapOf("at_1" to UploadState.Failed(retryable = false, message = "timeout"))

        assertTrue(controller.onUploadGivenUp("at_1", micGranted = true))
        assertTrue(controller.onUploadsChanged(failed).isEmpty())

        assertEquals(TestFlowPhase.Recording(start, afterUploadFailure = true), controller.phase)
    }

    @Test
    fun `실패 통지가 먼저 돌아도 뒤따른 포기가 같은 녹음 화면을 연다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        val failed = mapOf("at_1" to UploadState.Failed(retryable = false, message = "timeout"))

        assertTrue(controller.onUploadsChanged(failed).isEmpty())
        assertEquals(TestFlowPhase.Web, controller.phase)
        assertTrue(controller.onUploadGivenUp("at_1", micGranted = true))

        assertEquals(TestFlowPhase.Recording(start, afterUploadFailure = true), controller.phase)
        // 폐기 뒤 uploads가 비어 두 이펙트가 다시 돌아도 화면은 그대로다.
        assertTrue(controller.onUploadsChanged(emptyMap()).isEmpty())
        assertFalse(controller.onUploadGivenUp("at_1", micGranted = true))
        assertEquals(TestFlowPhase.Recording(start, afterUploadFailure = true), controller.phase)
    }

    /*
     * 권한 팝업이 한 번 끼어도 재녹음 사유는 살아남아야 한다 (KAN-147). 게이트가 그 값을 들고
     * 있지 않으면 통과 직후 열리는 녹음 화면에서 "왜 다시 녹음하는지"가 사라진다.
     */
    @Test
    fun `권한이 회수됐으면 게이트를 먼저 세운다 - 다시 열 문항과 사유를 그대로 들고 있는다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        assertTrue(controller.onUploadGivenUp("at_1", micGranted = false, message = "녹음이 너무 깁니다"))

        assertEquals(
            TestFlowPhase.NeedsPermission(start, afterUploadFailure = true, failureMessage = "녹음이 너무 깁니다"),
            controller.phase,
        )

        controller.onPermissionGranted()

        assertEquals(
            TestFlowPhase.Recording(start, afterUploadFailure = true, failureMessage = "녹음이 너무 깁니다"),
            controller.phase,
        )
    }

    /*
     * 게이트에 선 채로 회전해도 사유를 잃지 않는다 - 저장 형식은 두 페이즈의 재녹음 사유를
     * 같은 자리에 담는다.
     */
    @Test
    fun `게이트에 선 재녹음 사유는 회전을 넘겨도 남는다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        controller.onUploadGivenUp("at_1", micGranted = false, message = "소리가 너무 작습니다")

        val restored = rotate(controller)

        assertEquals(
            TestFlowPhase.NeedsPermission(start, afterUploadFailure = true, failureMessage = "소리가 너무 작습니다"),
            restored.phase,
        )
        restored.onPermissionGranted()
        assertEquals(
            TestFlowPhase.Recording(start, afterUploadFailure = true, failureMessage = "소리가 너무 작습니다"),
            restored.phase,
        )
    }

    /*
     * 왜 다시 녹음해야 하는지는 서버만 안다 (KAN-147, B안). 앱이 지어낸 일반 문구로 덮으면
     * 사용자는 다음 녹음에서 같은 실패를 반복한다.
     */
    @Test
    fun `서버가 준 거절 문구가 다시 열린 녹음 화면까지 실려 간다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)

        assertTrue(controller.onUploadGivenUp("at_1", micGranted = true, message = "녹음이 너무 깁니다"))

        assertEquals(
            TestFlowPhase.Recording(start, afterUploadFailure = true, failureMessage = "녹음이 너무 깁니다"),
            controller.phase,
        )
    }

    @Test
    fun `다시 열린 녹음 화면은 회전해도 서버 문구를 잃지 않는다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        controller.onUploadGivenUp("at_1", micGranted = true, message = "녹음이 너무 깁니다")

        val restored = rotate(controller)

        assertEquals(
            TestFlowPhase.Recording(start, afterUploadFailure = true, failureMessage = "녹음이 너무 깁니다"),
            restored.phase,
        )
    }

    /*
     * 앞 문항의 뒤늦은 포기가 손에 든 녹음을 갈아치우면 사용자는 방금 녹음하던 것을 잃는다.
     * 시도는 거둬가되(업로드는 폐기해야 한다) 화면은 건드리지 않는다.
     */
    @Test
    fun `다른 문항을 녹음하는 중에 온 포기는 화면을 건드리지 않는다`() {
        val controller = TestFlowController()
        controller.onStartVoiceItem(voiceItem(itemId = "item_1"), micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        val next = voiceItem(itemId = "item_2", number = 2)
        controller.onStartVoiceItem(next, micGranted = true)

        assertTrue(controller.onUploadGivenUp("at_1", micGranted = true))

        assertEquals(TestFlowPhase.Recording(next), controller.phase)
        assertTrue(controller.onUploadsChanged(mapOf("at_1" to UploadState.Done("job_1"))).isEmpty())
    }

    @Test
    fun `같은 문항을 다시 녹음하는 중에 온 포기도 화면을 건드리지 않는다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        controller.onUploadsChanged(mapOf("at_1" to UploadState.Failed(retryable = true, message = "timeout")))
        controller.onStartVoiceItem(start, micGranted = true)

        assertTrue(controller.onUploadGivenUp("at_1", micGranted = true))

        // 이미 서 있던 녹음 화면이라 재개 표식이 붙지 않는다.
        assertEquals(TestFlowPhase.Recording(start), controller.phase)
    }

    @Test
    fun `모르는 시도의 포기는 아무 일도 하지 않는다 - 이미 밀려난 시도가 여기로 온다`() {
        val controller = TestFlowController()

        assertTrue(!controller.onUploadGivenUp("at_unknown", micGranted = true))

        assertEquals(TestFlowPhase.Web, controller.phase)
    }

    /*
     * 대기 시도가 어느 문항의 것이었는지까지 저장한다 (KAN-147) - 복원 뒤에 포기가 확정돼도
     * 녹음 화면을 다시 세울 수 있어야 한다. 문항 문구, 번호, 가이드 곡선이 전부 필요한데
     * 결과 조립용 메타에는 itemId밖에 없다.
     */
    @Test
    fun `복원 뒤에 온 포기도 그 문항의 녹음 화면을 다시 연다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        controller.onUploadsChanged(mapOf("at_1" to UploadState.Failed(retryable = true, message = "timeout")))

        val restored = rotate(controller)
        assertEquals(TestFlowPhase.Web, restored.phase)

        assertTrue(restored.onUploadGivenUp("at_1", micGranted = true))

        assertEquals(TestFlowPhase.Recording(start, afterUploadFailure = true), restored.phase)
    }

    @Test
    fun `다시 열린 녹음 화면은 회전해도 그 이유를 잃지 않는다`() {
        val controller = TestFlowController()
        val start = voiceItem(itemId = "item_1")
        controller.onStartVoiceItem(start, micGranted = true)
        controller.onRecordingFinished("at_1", durationMs = 3_200, quality = QualityStatus.NORMAL)
        controller.onUploadGivenUp("at_1", micGranted = true)

        val restored = rotate(controller)

        assertEquals(TestFlowPhase.Recording(start, afterUploadFailure = true), restored.phase)
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
