package com.accentury.app.permission

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/**
 * 마이크 권한 게이트의 상태 (KAN-98, ux-ui.md §4-C).
 *
 * 거부는 두 갈래로 갈린다 — OS 팝업을 다시 띄울 수 있는 [Denied]와,
 * 2회 거부 등으로 OS가 재요청을 막아 설정 앱만이 유일한 경로인 [PermanentlyDenied].
 * 이 구분이 화면의 버튼(다시 요청 vs 설정 이동)을 결정한다.
 */
sealed interface MicPermissionState {
    /** OS 팝업 전 자체 안내 화면 1장 — 맥락 없는 권한 요청은 거부율이 높다 (ux-ui.md §4-C). */
    data object Rationale : MicPermissionState

    data object Granted : MicPermissionState

    /** 거부됐지만 OS 재요청이 아직 가능하다 — 가치 재설명 후 다시 요청한다. */
    data object Denied : MicPermissionState

    /** OS 재요청 불가 — 설정 딥링크로만 진행 가능 (2026-07-27 확정: 건너뛰기 없음). */
    data object PermanentlyDenied : MicPermissionState
}

/**
 * 마이크 권한 게이트 상태 머신. 권한 결과 콜백·설정 앱 복귀가 여기로 모인다.
 *
 * PermissionGate에서 분리한 이유: 거부/영구거부 판별과 설정 복귀 재확인이 게이트 UX의
 * 정확성을 좌우하는데, Compose·ActivityResult에 붙어 있으면 JVM 단위 테스트가 불가능하다
 * (WebLoadController와 같은 구조). Compose snapshot state라 화면은 그대로 따라온다.
 */
class MicPermissionController private constructor(initialState: MicPermissionState) {

    // 이미 허용된 상태로 게이트에 들어오면 안내 화면 없이 바로 통과한다
    // ("허용 후에는 재진입 없이 바로 첫 문항으로 이동한다").
    constructor(initiallyGranted: Boolean) : this(
        if (initiallyGranted) MicPermissionState.Granted else MicPermissionState.Rationale,
    )

    var state: MicPermissionState by mutableStateOf(initialState)
        private set

    /**
     * OS 권한 팝업 결과. [canAskAgain]은 거부 직후의 shouldShowRequestPermissionRationale —
     * 거부됐는데 이 값마저 false면 OS가 팝업 자체를 막은 것(영구 거부)이다.
     * 팝업 바깥 탭으로 닫힌 경우도 거부로 들어오지만 canAskAgain=true라 [Denied]에 머문다.
     */
    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        state = when {
            granted -> MicPermissionState.Granted
            canAskAgain -> MicPermissionState.Denied
            else -> MicPermissionState.PermanentlyDenied
        }
    }

    /**
     * 설정 앱에서 돌아왔을 때(ON_RESUME)의 재확인 — 허용으로 바뀌었으면 재시작 없이 통과한다.
     * 허용→회수 방향은 다루지 않는다: 설정에서 권한을 회수하면 OS가 프로세스를 재시작하므로
     * 이 분기는 도달 불가고, 녹음 중 회수는 KAN-86 범위다.
     */
    fun onReturnedToApp(granted: Boolean) {
        if (granted) state = MicPermissionState.Granted
    }

    companion object {
        /**
         * 회전·프로세스 재시작 후 복원. 영구 거부가 재생성에 증발해 "설정 딥링크만"
         * 제약을 잃으면 안 되지만, 저장 시점과 복원 시점의 실제 권한이 어긋날 수도 있어
         * 대조한다: 실제로 허용돼 있으면 저장값과 무관하게 통과하고, 저장값이 Granted인데
         * 실제로는 회수됐으면(프로세스 사망 중 설정 변경) 처음부터 다시 묻는다 —
         * 이때 재요청 가능 여부는 알 수 없으므로 다음 요청 결과가 상태를 다시 판정한다.
         */
        fun restored(saved: MicPermissionState, currentlyGranted: Boolean): MicPermissionController =
            MicPermissionController(
                when {
                    currentlyGranted -> MicPermissionState.Granted
                    saved == MicPermissionState.Granted -> MicPermissionState.Rationale
                    else -> saved
                },
            )

        /** rememberSaveable 결선용. 상태를 문자열 키로 저장하고 [restored]로 대조 복원한다. */
        fun saver(isCurrentlyGranted: () -> Boolean): Saver<MicPermissionController, String> =
            Saver(
                save = { it.state.toSaveKey() },
                restore = { key -> restored(stateFromSaveKey(key), isCurrentlyGranted()) },
            )

        private fun MicPermissionState.toSaveKey(): String = when (this) {
            MicPermissionState.Rationale -> "rationale"
            MicPermissionState.Granted -> "granted"
            MicPermissionState.Denied -> "denied"
            MicPermissionState.PermanentlyDenied -> "permanently_denied"
        }

        private fun stateFromSaveKey(key: String): MicPermissionState = when (key) {
            "granted" -> MicPermissionState.Granted
            "denied" -> MicPermissionState.Denied
            "permanently_denied" -> MicPermissionState.PermanentlyDenied
            else -> MicPermissionState.Rationale
        }
    }
}
