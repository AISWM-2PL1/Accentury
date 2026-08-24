package com.accentury.app.testflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.accentury.app.audio.QualityStatus
import com.accentury.app.bridge.ItemAttempt
import com.accentury.app.bridge.ItemResult
import com.accentury.app.bridge.VoiceItemStart
import com.accentury.app.bridge.assembleItemResult
import com.accentury.app.upload.UploadState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// 저장/복원마다 새로 만들면 직렬화기 캐시가 매번 버려진다. 파일 안에서 하나만 쓴다.
private val json = Json

/**
 * 웹 위에 지금 무엇이 겹쳐 있는지 (KAN-100).
 *
 * WebView는 어느 페이즈에서도 살아 있고 네이티브 화면은 그 위를 덮을 뿐이다 —
 * 진행의 정본은 웹 상태 머신이라 웹을 내리면 돌아갈 자리를 잃는다.
 */
sealed interface TestFlowPhase {

    /** 웹이 전면. 문항 진행·결과 표시는 전부 여기서 돈다. */
    data object Web : TestFlowPhase

    /**
     * VOICE 진입 요청이 왔는데 마이크 권한이 없다 — 게이트(KAN-98)를 다시 세운다.
     * 시작 게이트에서 한 번 허용받았어도 설정에서 회수될 수 있어, 진입마다 확인이 필요하다.
     * 통과하면 [pending]으로 녹음을 이어간다 — 권한 때문에 문항 하나를 잃지 않는다.
     */
    data class NeedsPermission(val pending: VoiceItemStart) : TestFlowPhase

    /**
     * 녹음 화면이 웹 위를 덮고 있다.
     *
     * [afterUploadFailure]는 이 화면이 업로드 포기([TestFlowController.onUploadGivenUp])로 스스로
     * 다시 열린 것인지다 (KAN-147). 사용자가 [다음]을 누르고 웹으로 돌아간 뒤에 벌어지는 일이라,
     * 이유를 적어두지 않으면 녹음 화면이 까닭 없이 되돌아온 것으로 보인다. 기본값 false는
     * 웹 요청으로 정상 진입한 경우다.
     */
    data class Recording(
        val start: VoiceItemStart,
        val afterUploadFailure: Boolean = false,
    ) : TestFlowPhase

    /**
     * 녹음은 끝났고 그 시도의 결과가 웹에 닿기를 기다리는 중이다 (KAN-146). 오버레이는 그대로 서 있다.
     *
     * 예전에는 [다음]을 누른 순간 웹으로 돌아갔다. 그런데 결과는 업로드가 끝나야 나가므로 그 사이
     * 웹의 대기 화면이 잠깐 드러났다가 다음 문항으로 교체됐다 — 음성 문항마다 반복되는 깜빡임의
     * 마지막 조각이다. 결과가 나갈 때까지 화면을 붙들면 그 순간 자체가 없어진다.
     *
     * 놓는 자리는 셋이다: 결과 주입이 끝나면 [onResultDelivered], 업로드가 실패로 확정되면
     * [onUploadsChanged], 그 둘 어느 쪽도 오지 않으면(업로드 자취가 사라진 복원 경로 등)
     * [onSubmitTimeout]이 받는다.
     */
    data class Submitting(val start: VoiceItemStart, val attemptId: String) : TestFlowPhase
}

/**
 * 화면에 떠 있던 [shown]이 [current]로 그대로 이어지는가 (KAN-146).
 *
 * 오버레이가 컴포지션에서 빠질 때 녹음 상태를 되감을지 정하는 판정이다. 회전은 컴포지션을 통째로
 * 버렸다가 다시 만들므로 페이즈가 그대로여도 이 자리를 지나가는데, 그때 되감으면 진행 중인 녹음이나
 * 기다리는 중인 제출이 죽는다.
 *
 * 판정이 방향에 따라 다르다:
 * - 녹음 중이었다면 같은 문항의 녹음이거나 **그 문항의 제출로 넘어간 것까지** 이어짐이다.
 *   [다음]으로 제출에 들어갈 때 되감으면 방금 그린 '내 억양' 곡선이 제출 화면에서 사라진다.
 * - 제출을 기다리던 중이었다면 **같은 문항의 제출만** 이어짐이다. 제출에서 녹음으로 되돌아온 것은
 *   그 문항을 처음부터 다시 하는 것이므로(웹이 결과를 못 받고 문항을 다시 열었을 때 생긴다) 되감아야
 *   한다 — 안 그러면 이미 제출해 PCM이 빠져나간 확인 화면이 그대로 뜨고, 거기서 [다음]은 아무 일도
 *   못 한다.
 *
 * 여기 있는 이유는 [TestFlowController]가 분리된 이유와 같다 — 화면 겹침의 정확성을 좌우하는 판정을
 * Compose 안에 두면 JVM에서 검증할 수 없다.
 */
fun continuesFrom(shown: TestFlowPhase, current: TestFlowPhase): Boolean = when (shown) {
    is TestFlowPhase.Submitting ->
        current is TestFlowPhase.Submitting && current.start.itemId == shown.start.itemId

    is TestFlowPhase.Recording -> when (current) {
        is TestFlowPhase.Recording -> current.start.itemId == shown.start.itemId
        is TestFlowPhase.Submitting -> current.start.itemId == shown.start.itemId
        else -> false
    }

    // 오버레이가 떠 있지 않던 페이즈는 이어질 것도 없다.
    else -> false
}

/**
 * 웹 ↔ 네이티브 화면 전환 오케스트레이션 (KAN-100). 브리지 콜백·권한 결과·녹음 종료·업로드
 * 완료가 여기로 모인다.
 *
 * MainActivity에서 분리한 이유: 어떤 화면을 겹칠지의 판정과 "끝난 시도를 언제 한 번만 웹으로
 * 돌려주는가"가 진행의 정확성을 좌우하는데, Compose·WebView·업로드에 붙어 있으면 JVM 단위
 * 테스트가 불가능하다 (WebLoadController·MicPermissionController와 같은 구조).
 * Compose snapshot state라 화면은 그대로 따라온다.
 *
 * 호출은 전부 메인 스레드에서 온다 — 브리지(AccenturyBridge)가 postToMain으로 넘기고 나머지는
 * Compose 콜백이다. 그래서 대기 목록에 동기화를 두지 않는다.
 */
class TestFlowController private constructor(
    initialPhase: TestFlowPhase,
    restoredAttempts: List<PendingAttempt>,
) {

    constructor() : this(TestFlowPhase.Web, emptyList())

    /**
     * 대기 시도 하나. [meta]는 결과 조립에 쓰는 브리지 계약 값이고, [start]는 그 시도가 어느 문항의
     * 것이었는지를 화면 단위로 되살리기 위한 원본 요청이다 (KAN-147) - 업로드를 포기했을 때
     * 녹음 화면을 다시 열려면 문항 문구, 번호, 가이드 곡선이 전부 필요한데, [meta]에는 itemId밖에 없다.
     *
     * [start]가 null인 것은 이 필드가 생기기 전 형식으로 저장됐다가 복원된 시도다. 그 시도는
     * 자동 재개를 할 수 없어 웹의 [녹음 화면 다시 열기]로 되돌아간다.
     */
    private data class PendingAttempt(val meta: ItemAttempt, val start: VoiceItemStart?)

    var phase: TestFlowPhase by mutableStateOf(initialPhase)
        private set

    /**
     * 업로드가 끝나기를 기다리는 시도들. 화면이 이 값을 읽지 않으므로(결과는 [onUploadsChanged]의
     * 반환값으로만 나간다) snapshot state로 둘 이유가 없다. 등록 순서대로 내보내려고 LinkedHashMap이다.
     */
    private val pendingAttempts = LinkedHashMap<String, PendingAttempt>().apply {
        restoredAttempts.forEach { put(it.meta.attemptId, it) }
    }

    /**
     * 웹이 VOICE 문항에 진입했다. 녹음 중이거나 권한 게이트가 서 있으면 무시한다 — 브리지 콜백은
     * 임의 타이밍에 오고(§8) 웹 리로드·이중 호출로 같은 요청이 두 번 들어올 수 있는데, 뒤늦은
     * 요청이 진행 중인 녹음을 갈아치우면 이미 녹음된 음성을 잃는다.
     *
     * 제출을 기다리는 중([TestFlowPhase.Submitting])에는 받아준다 (KAN-146). 그 가드가 지키려는
     * 것은 아직 손에 있는 녹음인데, 제출 뒤에는 PCM이 이미 업로드로 넘어가 잃을 것이 없다.
     * 반대로 여기서 막으면 웹이 다음 문항으로 넘어갔는데 네이티브가 따라가지 못해 진행이 멈춘다 —
     * 진행의 정본은 웹이므로 웹이 다음 문항을 열면 화면도 따라가야 한다. 앞 시도의 결과는
     * 대기 목록에 그대로 남아 준비되는 대로 실려 나간다.
     *
     * 같은 itemId가 다시 오는 것(재녹음)은 막지 않는다. 결과 유실·재시도 경로에서 자연스러운
     * 흐름이고, 중복 제출은 웹 상태 머신의 가드가 거른다.
     */
    fun onStartVoiceItem(start: VoiceItemStart, micGranted: Boolean) {
        when (phase) {
            is TestFlowPhase.Recording, is TestFlowPhase.NeedsPermission -> return
            else -> Unit
        }
        phase = if (micGranted) TestFlowPhase.Recording(start) else TestFlowPhase.NeedsPermission(start)
    }

    /**
     * 게이트를 통과했다. 기다리던 문항으로 곧장 들어간다 — 웹에 되돌려 다시 요청하게 만들면
     * 사용자가 같은 문항을 두 번 시작하는 셈이다.
     *
     * 게이트가 서 있지 않을 때 오는 허용 통지(설정 복귀 시의 ON_RESUME 재확인 등)는 무시한다.
     */
    fun onPermissionGranted() {
        val pending = (phase as? TestFlowPhase.NeedsPermission)?.pending ?: return
        phase = TestFlowPhase.Recording(pending)
    }

    /**
     * 녹음을 마치고 제출했다. 결과가 웹에 나갈 때까지 [TestFlowPhase.Submitting]으로 화면을 붙든다
     * (KAN-146) — 여기서 곧장 웹으로 돌아가면 결과가 도착하기 전의 대기 화면이 한 번 드러난다.
     *
     * 진행 자체는 여전히 업로드를 기다리지 않는다: 대기 시도는 지금 등록되고, 결과는 준비되는 대로
     * [onUploadsChanged]가 실어 보낸다. 붙드는 것은 화면뿐이고, 그 화면은 주입이 끝나는 대로
     * [onResultDelivered]가 놓는다.
     *
     * 녹음 화면 밖에서 오는 종료 통지는 무시한다. 이탈·회전으로 이미 화면이 내려간 뒤의 뒤늦은
     * 콜백이라 어느 문항의 시도인지 말할 수 없다.
     *
     * 같은 문항의 앞 시도들은 여기서 대기 목록에서 빠지고, 그 attemptId가 반환값으로 나간다
     * (KAN-147, 지라 코멘트 #2). 한 문항에 살아 있는 시도는 하나여야 한다 - 앞 시도가 남아 있으면
     * 상태 바에 그것의 [재시도]가 그대로 서 있고, 그걸 누르면 같은 문항에 분석 작업이 둘 생겨
     * 웹이 결과를 두 번 받는다. 밀려난 업로드의 바이트를 실제로 폐기하는 것은 호출자 몫이다 -
     * 업로드를 이 클래스가 알면 JVM 단위 테스트가 불가능해진다.
     */
    fun onRecordingFinished(
        attemptId: String,
        durationMs: Long,
        quality: QualityStatus,
    ): List<String> {
        val start = (phase as? TestFlowPhase.Recording)?.start ?: return emptyList()
        val superseded = pendingAttempts.values
            .filter { it.meta.itemId == start.itemId && it.meta.attemptId != attemptId }
            .map { it.meta.attemptId }
        superseded.forEach { pendingAttempts.remove(it) }
        pendingAttempts[attemptId] = PendingAttempt(
            meta = ItemAttempt(
                itemId = start.itemId,
                attemptId = attemptId,
                durationMs = durationMs,
                quality = quality,
            ),
            start = start,
        )
        phase = TestFlowPhase.Submitting(start, attemptId)
        return superseded
    }

    /**
     * 이 시도의 업로드를 포기했다 - 재시도 상한을 다 썼거나 서버가 재시도 불가라고 답했다
     * (KAN-147, 2026-08-25 결정: 재시도 2회, 그래도 실패하면 녹음 화면 자동 재개).
     *
     * 웹이 아니라 네이티브가 화면을 다시 여는 이유: 브리지 표면을 최소로 두기로 한 계약이라
     * 웹은 네이티브 쪽 업로드 실패를 통지받지 않는다. 그래서 웹은 결과가 올 때까지 그 문항의 대기
     * 화면에 그대로 머물러 있고 - 바로 그 점이 여기서 화면을 다시 열어도 되는 근거다. 웹이 아직
     * 그 문항을 보여주는 중이므로 새 녹음이 진행을 앞지르지 않는다.
     *
     * 시도를 대기 목록에서 버리는 이유는 그 시도의 결과가 영영 조립되지 않기 때문이다. 남겨두면
     * [onUploadsChanged]가 매번 훑고 지나가는 가짜 대기가 된다. 새 녹음은 새 attemptId를 받는다.
     *
     * 사용자가 이미 다른 무언가를 녹음하는 중([TestFlowPhase.Recording],
     * [TestFlowPhase.NeedsPermission])이면 화면은 건드리지 않는다 - 앞 문항의 뒤늦은 포기가
     * 손에 든 녹음을 갈아치우면 안 된다. [TestFlowPhase.Submitting]에서 같은 문항의 녹음으로
     * 되돌아가는 것은 [continuesFrom]이 이미 다루는 "제출에서 녹음으로 되돌아온 것"이라,
     * 호출자 쪽 되감기가 RecordingViewModel을 초기화해 새 녹음을 받을 상태로 만든다.
     *
     * @return 이 컨트롤러가 시도를 거둬갔는가. false면 이미 밀려났거나 모르는 시도라 할 일이 없다.
     *   true면 호출자가 그 업로드의 바이트와 상태를 폐기한다.
     */
    fun onUploadGivenUp(attemptId: String, micGranted: Boolean): Boolean {
        val dropped = pendingAttempts.remove(attemptId) ?: return false
        when (phase) {
            is TestFlowPhase.Recording, is TestFlowPhase.NeedsPermission -> Unit
            else -> {
                // start가 없는 것은 구버전 형식에서 복원된 시도뿐이다. 다시 열 화면을 만들 수 없어
                // 웹의 [녹음 화면 다시 열기]에 맡긴다 - 업로드 폐기는 그대로 진행한다.
                val start = dropped.start
                if (start != null) {
                    phase = if (micGranted) {
                        TestFlowPhase.Recording(start, afterUploadFailure = true)
                    } else {
                        TestFlowPhase.NeedsPermission(start)
                    }
                }
            }
        }
        return true
    }

    /**
     * 붙들어 둔 화면의 상한 (KAN-146). 업로드가 뒷받침하지 않는 붙들기를 걷는 최후 안전망이다.
     *
     * 업로드가 아직 진행 중이면 걷지 않는다 — 끝날 때까지 현재 문항 화면을 유지하는 것이 이 티켓의
     * 요구고, 여기서 시간으로 끊으면 없애려던 대기 화면이 정확히 그 자리에 생긴다. 발화 시점에 다시
     * 확인하는 이유가 이것이다: 타이머를 걸 때는 업로드가 아직 목록에 안 올라와 있을 수 있고
     * (등록과 화면 반영 사이 한 프레임), 그 사이 앱이 백그라운드로 가면 그 상태가 굳는다.
     *
     * attemptId를 받아 대조하는 이유: 이미 결과가 나가 다음 문항으로 넘어간 뒤 뒤늦게 도착한
     * 타이머가 새로 뜬 화면을 걷어버리면 안 된다.
     */
    fun onSubmitTimeout(attemptId: String, uploads: Map<String, UploadState>) {
        val awaiting = phase as? TestFlowPhase.Submitting ?: return
        if (awaiting.attemptId != attemptId) return
        if (uploads[attemptId] is UploadState.InFlight) return
        phase = TestFlowPhase.Web
    }

    /**
     * 시도를 등록하지 않고 웹으로 돌아간다.
     *
     * 남은 호출처는 PCM 없는 제출 하나뿐이다: 올릴 바이트가 없으면 결과도 만들어질 수 없어,
     * 시도로 등록하면 웹이 오지 않을 결과를 기다리며 그 문항에 멈춘다. 등록 없이 돌려보내
     * [녹음 화면 다시 열기]로 다시 녹음하게 하는 쪽이 정본이다.
     * (그 경로에서는 consumeRecording이 이미 PCM을 가져가 폐기까지 끝냈다 - FR-DP-02)
     *
     * 진행 전체를 초기화하지는 않는다: 진행의 정본은 웹 상태 머신이고 돌아가기는 해당 문항을
     * 다시 시도하겠다는 뜻일 뿐이라, 여기서 앞 문항들의 대기 시도까지 버리면 이미 끝난 업로드의
     * 결과가 웹에 영영 도착하지 않는다.
     *
     * 녹음 화면의 [나가기] 버튼은 KAN-147에서 없앴다 (2026-08-19 결정: 이탈 UX는 KAN-39
     * 디자인 때 정한다).
     */
    fun onRecordingExit() {
        if (phase !is TestFlowPhase.Recording) return
        phase = TestFlowPhase.Web
    }

    /**
     * 대응 업로드가 없는 대기 시도를 걷어낸다. 복원 직후 한 번 부른다.
     *
     * 실제로 지우는 건 프로세스 사망 복원 경로다: 대기 시도는 saver가 살리지만 업로드는 메모리
     * (UploadManager)에만 있어 함께 사라진다 — 남겨두면 [onUploadsChanged]가 영영 조립하지 못할
     * 가짜 대기가 된다. 그 문항은 웹이 결과를 받지 못한 채로 남아 [녹음 화면 다시 열기]로 다시
     * 요청하는 쪽이 정본이다.
     *
     * 회전은 업로드를 든 ViewModel이 살아남아 키가 그대로이므로 아무것도 지우지 않는다.
     */
    fun pruneAttemptsWithoutUpload(knownAttemptIds: Set<String>) {
        pendingAttempts.keys.retainAll(knownAttemptIds)
    }

    /**
     * 업로드 상태가 바뀔 때마다 부른다. 완료된 시도만 [ItemResult]로 조립해 반환하고 대기 목록에서
     * 지운다 — 같은 시도를 두 번 내보내지 않는다(웹은 문항당 결과 1회를 전제로 진행한다).
     * 진행 중·실패는 남겨 둔다: 재시도가 성공하면 그때 실려 나간다.
     *
     * 반환값을 브리지로 넘기는 결선은 호출자(Stage 4) 몫이다. 여기서 evaluateJavascript를 부르지
     * 않는 것이 이 클래스를 JVM에서 검증 가능하게 유지하는 조건이다.
     */
    fun onUploadsChanged(uploads: Map<String, UploadState>): List<ItemResult> {
        val delivered = mutableListOf<ItemResult>()
        val iterator = pendingAttempts.values.iterator()
        while (iterator.hasNext()) {
            val result = assembleItemResult(iterator.next().meta, uploads) ?: continue
            iterator.remove()
            delivered += result
        }
        /*
         * 업로드가 실패했으면 붙들고 있던 화면을 여기서 놓는다 (KAN-146). 결과는 영영 조립되지
         * 않는데, 그걸 이미 아는 자리에서 계속 기다리면 오버레이는 "제출 중…"이라 말하는 동안 그
         * 아래 업로드 상태 바는 같은 화면에서 이미 "업로드 실패 [재시도]"를 띄운다 — 한 화면이 서로
         * 다른 두 말을 하는 구간이라 바로 놓는다.
         *
         * 성공한 경우는 여기서 놓지 않는다. 결과를 조립했다는 것과 웹이 그 결과를 받아 다음 문항을
         * 그렸다는 것은 다르고, 그 사이에 놓으면 걷힌 자리에 아직 앞 문항의 대기 화면이 남아 한
         * 프레임 드러난다. 주입이 끝난 뒤 [onResultDelivered]가 놓는다.
         */
        val awaiting = phase as? TestFlowPhase.Submitting
        if (awaiting != null && uploads[awaiting.attemptId] is UploadState.Failed) {
            phase = TestFlowPhase.Web
        }
        return delivered
    }

    /**
     * 결과 주입이 끝났다 — 웹이 [onUploadsChanged]가 돌려준 결과를 받아 다음 문항으로 넘어갔다는
     * 뜻이다 (KAN-146). 이제 붙들고 있던 화면을 놓는다.
     *
     * 조립 시점이 아니라 주입 완료 시점인 이유: 그 둘 사이에 웹이 다시 그릴 틈이 있어, 조립 자리에서
     * 놓으면 걷힌 아래에 아직 앞 문항의 대기 화면이 남아 한 프레임 드러난다.
     *
     * attemptId를 대조해 지금 기다리는 시도의 주입일 때만 놓는다 — 앞 문항의 뒤늦은 주입이 새로 뜬
     * 화면을 걷어버리면 안 된다.
     */
    fun onResultDelivered(attemptId: String) {
        val awaiting = phase as? TestFlowPhase.Submitting ?: return
        if (awaiting.attemptId != attemptId) return
        phase = TestFlowPhase.Web
    }

    private fun toSavedFlow(): SavedFlow = SavedFlow(
        phase = when (phase) {
            TestFlowPhase.Web -> SavedPhase.WEB
            is TestFlowPhase.NeedsPermission -> SavedPhase.NEEDS_PERMISSION
            is TestFlowPhase.Recording -> SavedPhase.RECORDING
            is TestFlowPhase.Submitting -> SavedPhase.SUBMITTING
        },
        start = when (val current = phase) {
            TestFlowPhase.Web -> null
            is TestFlowPhase.NeedsPermission -> current.pending
            is TestFlowPhase.Recording -> current.start
            is TestFlowPhase.Submitting -> current.start
        },
        attemptId = (phase as? TestFlowPhase.Submitting)?.attemptId,
        afterUploadFailure = (phase as? TestFlowPhase.Recording)?.afterUploadFailure == true,
        attempts = pendingAttempts.values.map {
            SavedAttempt(it.meta.itemId, it.meta.attemptId, it.meta.durationMs, it.meta.quality, it.start)
        },
    )

    companion object {
        /**
         * rememberSaveable 결선용.
         *
         * saver를 두는 이유: 이 앱은 회전을 잠그지 않아(매니페스트에 configChanges·
         * screenOrientation 없음) 녹음 중 회전하면 Activity가 통째로 재생성된다. 그런데
         * RecordingViewModel은 ViewModel이라 녹음·PCM을 그대로 들고 살아남는다 — phase만
         * 증발하면 녹음 화면이 사라진 자리에 웹이 드러나고, 웹은 보내지도 않은 결과를 기다리며
         * 그 문항에 멈춘다. 대기 시도도 마찬가지로, 증발하면 곧 완료될 업로드의 결과가 갈 곳을
         * 잃는다. 어느 쪽이든 사용자가 스스로 빠져나올 수 없는 상태라 저장한다.
         *
         * 복원할 때 실제 권한과 대조하지 않는다(MicPermissionController.saver와 다른 점):
         * 설정에서 권한을 회수하면 OS가 프로세스를 재시작해 이 상태 자체가 남지 않고, 반대로
         * 허용된 채 복원된 NeedsPermission은 게이트의 ON_RESUME 재확인이 곧바로 통과시킨다.
         */
        fun saver(): Saver<TestFlowController, String> = Saver(
            save = { json.encodeToString(SavedFlow.serializer(), it.toSavedFlow()) },
            restore = ::restored,
        )

        /**
         * 저장값이 깨져 있으면(구버전 형식 등) 저장이 없었던 것으로 본다 — null을 돌려주면
         * rememberSaveable이 새 컨트롤러를 만들고, 진행의 정본인 웹이 문항을 다시 요청한다.
         */
        private fun restored(saved: String): TestFlowController? {
            val flow = try {
                json.decodeFromString(SavedFlow.serializer(), saved)
            } catch (_: Exception) {
                return null
            }
            // start 없는 NeedsPermission·Recording·Submitting은 성립하지 않는 조합이라 웹으로 되돌린다
            // (Submitting은 attemptId까지 있어야 한다 — 어느 시도를 기다리는지 모르면 걷을 수도 없다).
            val start = flow.start
            val attemptId = flow.attemptId
            val phase = when {
                start == null -> TestFlowPhase.Web
                flow.phase == SavedPhase.NEEDS_PERMISSION -> TestFlowPhase.NeedsPermission(start)
                flow.phase == SavedPhase.RECORDING ->
                    TestFlowPhase.Recording(start, flow.afterUploadFailure)
                flow.phase == SavedPhase.SUBMITTING && attemptId != null ->
                    TestFlowPhase.Submitting(start, attemptId)
                else -> TestFlowPhase.Web
            }
            return TestFlowController(
                initialPhase = phase,
                restoredAttempts = flow.attempts.map {
                    PendingAttempt(
                        meta = ItemAttempt(it.itemId, it.attemptId, it.durationMs, it.quality),
                        start = it.start,
                    )
                },
            )
        }
    }
}

/**
 * 저장 형식. Bundle에 담을 수 있는 타입으로 손수 풀어 쓰는 대신 JSON 한 줄로 접는다 —
 * [VoiceItemStart]가 브리지 계약에서 이미 @Serializable이라 그대로 재사용된다.
 * [ItemAttempt]는 브리지 계약 타입이라 거기에 직렬화 어노테이션을 더하는 대신 여기서 사본을 둔다.
 */
@Serializable
private data class SavedFlow(
    val phase: SavedPhase,
    val start: VoiceItemStart? = null,
    /** SUBMITTING이 기다리는 시도. 다른 페이즈에서는 null이다. */
    val attemptId: String? = null,
    /** RECORDING이 업로드 포기로 다시 열린 화면인가 (KAN-147). 다른 페이즈에서는 뜻이 없다. */
    val afterUploadFailure: Boolean = false,
    val attempts: List<SavedAttempt> = emptyList(),
)

@Serializable
private enum class SavedPhase { WEB, NEEDS_PERMISSION, RECORDING, SUBMITTING }

@Serializable
private data class SavedAttempt(
    val itemId: String,
    val attemptId: String,
    val durationMs: Long,
    val quality: QualityStatus,
    /**
     * 업로드를 포기했을 때 이 문항의 녹음 화면을 다시 세우려면 원본 요청이 필요하다 (KAN-147).
     * 기본값 null은 이 필드가 생기기 전 형식으로 저장된 값도 그대로 복원되게 한다 - 그렇게 복원된
     * 시도는 자동 재개만 못 할 뿐 결과 조립은 정상이다.
     */
    val start: VoiceItemStart? = null,
)
