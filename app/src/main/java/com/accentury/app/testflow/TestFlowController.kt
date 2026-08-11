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

    /** 녹음 화면이 웹 위를 덮고 있다. */
    data class Recording(val start: VoiceItemStart) : TestFlowPhase
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
    restoredAttempts: List<ItemAttempt>,
) {

    constructor() : this(TestFlowPhase.Web, emptyList())

    var phase: TestFlowPhase by mutableStateOf(initialPhase)
        private set

    /**
     * 업로드가 끝나기를 기다리는 시도들. 화면이 이 값을 읽지 않으므로(결과는 [onUploadsChanged]의
     * 반환값으로만 나간다) snapshot state로 둘 이유가 없다. 등록 순서대로 내보내려고 LinkedHashMap이다.
     */
    private val pendingAttempts = LinkedHashMap<String, ItemAttempt>().apply {
        restoredAttempts.forEach { put(it.attemptId, it) }
    }

    /**
     * 웹이 VOICE 문항에 진입했다. 이미 네이티브 화면이 떠 있으면 무시한다 — 브리지 콜백은 임의
     * 타이밍에 오고(§8) 웹 리로드·이중 호출로 같은 요청이 두 번 들어올 수 있는데, 뒤늦은 요청이
     * 진행 중인 녹음을 갈아치우면 이미 녹음된 음성을 잃는다.
     *
     * 같은 itemId가 다시 오는 것(재녹음)은 막지 않는다. 결과 유실·재시도 경로에서 자연스러운
     * 흐름이고, 중복 제출은 웹 상태 머신의 가드가 거른다.
     */
    fun onStartVoiceItem(start: VoiceItemStart, micGranted: Boolean) {
        if (phase != TestFlowPhase.Web) return
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
     * 녹음을 마치고 제출했다. 업로드 완료를 기다리지 않고 웹으로 돌아간다 — 다음 문항을 붙드는
     * 대신, 결과는 준비되는 대로 [onUploadsChanged]가 따로 실어 보낸다.
     *
     * 녹음 화면 밖에서 오는 종료 통지는 무시한다. 이탈·회전으로 이미 화면이 내려간 뒤의 뒤늦은
     * 콜백이라 어느 문항의 시도인지 말할 수 없다.
     */
    fun onRecordingFinished(attemptId: String, durationMs: Long, quality: QualityStatus) {
        val start = (phase as? TestFlowPhase.Recording)?.start ?: return
        pendingAttempts[attemptId] = ItemAttempt(
            itemId = start.itemId,
            attemptId = attemptId,
            durationMs = durationMs,
            quality = quality,
        )
        phase = TestFlowPhase.Web
    }

    /**
     * 녹음 화면에서 나가기 — 이 시도는 등록하지 않고 웹으로 돌아간다.
     *
     * 하네스의 onExit처럼 진행 전체를 초기화하지는 않는다: 진행의 정본은 웹 상태 머신이고
     * 나가기는 해당 문항을 다시 시도하겠다는 뜻일 뿐이라, 여기서 앞 문항들의 대기 시도까지
     * 버리면 이미 끝난 업로드의 결과가 웹에 영영 도착하지 않는다.
     * (녹음 중이던 PCM 폐기는 RecordingScreen이 이탈 즉시 처리한다 — FR-DP-02)
     */
    fun onRecordingExit() {
        if (phase !is TestFlowPhase.Recording) return
        phase = TestFlowPhase.Web
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
            val result = assembleItemResult(iterator.next(), uploads) ?: continue
            iterator.remove()
            delivered += result
        }
        return delivered
    }

    private fun toSavedFlow(): SavedFlow = SavedFlow(
        phase = when (phase) {
            TestFlowPhase.Web -> SavedPhase.WEB
            is TestFlowPhase.NeedsPermission -> SavedPhase.NEEDS_PERMISSION
            is TestFlowPhase.Recording -> SavedPhase.RECORDING
        },
        start = when (val current = phase) {
            TestFlowPhase.Web -> null
            is TestFlowPhase.NeedsPermission -> current.pending
            is TestFlowPhase.Recording -> current.start
        },
        attempts = pendingAttempts.values.map {
            SavedAttempt(it.itemId, it.attemptId, it.durationMs, it.quality)
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
            // start 없는 NeedsPermission·Recording은 성립하지 않는 조합이라 웹으로 되돌린다.
            val start = flow.start
            val phase = when {
                start == null -> TestFlowPhase.Web
                flow.phase == SavedPhase.NEEDS_PERMISSION -> TestFlowPhase.NeedsPermission(start)
                flow.phase == SavedPhase.RECORDING -> TestFlowPhase.Recording(start)
                else -> TestFlowPhase.Web
            }
            return TestFlowController(
                initialPhase = phase,
                restoredAttempts = flow.attempts.map {
                    ItemAttempt(it.itemId, it.attemptId, it.durationMs, it.quality)
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
    val attempts: List<SavedAttempt> = emptyList(),
)

@Serializable
private enum class SavedPhase { WEB, NEEDS_PERMISSION, RECORDING }

@Serializable
private data class SavedAttempt(
    val itemId: String,
    val attemptId: String,
    val durationMs: Long,
    val quality: QualityStatus,
)
