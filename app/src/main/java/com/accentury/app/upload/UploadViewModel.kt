package com.accentury.app.upload

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

// UploadState는 itemId를 들고 있지 않아 실패 표시에 쓸 문항 라벨을 모르는 경우의 대체 문구.
private const val DEFAULT_LABEL = "문항"

/**
 * 진행 중인 업로드와 재시도 상태의 주인 (KAN-100).
 *
 * ViewModel인 이유: 이 앱은 회전을 잠그지 않아 업로드 중 회전하면 Activity가 통째로 재생성된다.
 * 컴포지션에 [androidx.compose.runtime.remember]로 매달아 두면 그때 UploadManager와 스코프가
 * 함께 폐기돼, 올라가던 음성과 실패 건의 재시도 통로가 아무 신호 없이 사라진다 — 사용자는 웹의
 * [녹음 화면 다시 열기]로 다시 녹음해야만 복구된다. [com.accentury.app.recording.RecordingViewModel]이
 * 녹음·PCM에 대해 이미 같은 역할을 한다.
 *
 * 프로세스 사망은 여전히 전부 폐기다 — PCM을 디스크에 남기지 않는 것이 FR-DP-02이고, 그 경로에서
 * 홀로 복원된 대기 시도는 [com.accentury.app.testflow.TestFlowController.pruneAttemptsWithoutUpload]가
 * 걷어낸다.
 *
 * 스코프를 [androidx.lifecycle.viewModelScope] 대신 직접 만드는 이유는 두 가지다: 직렬화·바이트
 * 처리를 UI 스레드 밖(Default)에서 돌려야 하고, viewModelScope는 [onCleared]보다 **먼저** 취소돼
 * 아래의 clearAll → cancel 순서를 지킬 수 없다. SupervisorJob은 업로드끼리 격리한다(하나가
 * 실패해도 형제를 죽이지 않는다).
 */
class UploadViewModel(
    client: UploadClient,
    sessionId: String,
    sessionToken: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ViewModel() {

    private val uploadManager = UploadManager(
        client = client,
        scope = scope,
        sessionId = sessionId,
        sessionToken = sessionToken,
    )

    val uploads: StateFlow<Map<String, UploadState>> = uploadManager.uploads

    private val labels = mutableStateMapOf<String, String>()

    /** 업로드를 걸면서 실패 표시에 쓸 문항 라벨을 함께 기억한다. */
    fun enqueue(request: UploadRequest, label: String) {
        labels[request.attemptId] = label
        uploadManager.enqueue(request)
    }

    fun labelOf(attemptId: String): String = labels[attemptId] ?: DEFAULT_LABEL

    fun retry(attemptId: String) {
        uploadManager.retry(attemptId)
    }

    /**
     * 결과가 나올 일이 없어진 시도 하나를 폐기한다 (KAN-147) - 재시도 상한을 넘겨 포기한 업로드와,
     * 같은 문항의 새 녹음에 밀려난 앞 시도가 여기로 온다. 라벨도 함께 지운다: 상태 바에서 사라진
     * 업로드의 라벨은 쓸 곳이 없고, 남겨두면 같은 키가 재사용될 때 옛 문항 번호가 따라붙는다.
     */
    fun discard(attemptId: String) {
        uploadManager.discard(attemptId)
        labels.remove(attemptId)
    }

    /** 남아 있는 음성 바이트를 전부 폐기한다 (FR-DP-02). 라벨은 업로드가 사라지면 쓸 곳이 없다. */
    fun clearAll() {
        uploadManager.clearAll()
        labels.clear()
    }

    override fun onCleared() {
        // 스코프만 취소하면 register~start 사이의 시도가 InFlight·원본으로 남을 수 있다.
        // clearAll을 먼저 불러 음성 바이트·상태를 확정 폐기한 뒤 스코프를 내린다 (FR-DP-02).
        clearAll()
        scope.cancel()
    }

    companion object {
        /**
         * 세션 값을 밖에서 받는 이유: 업로드가 쓰는 세션은 웹 진입 URL·브리지 토큰과 같은 값이어야
         * 하고(음성이 올라가는 세션과 웹이 진행을 저장하는 세션이 갈리면 안 된다), 그 정본은
         * 서버 응답을 받아 든 MainActivity에 있다 (KAN-34).
         *
         * 팩토리는 뷰모델이 처음 만들어질 때 한 번만 쓰이므로 세션은 인스턴스마다 고정이다.
         * 그래서 호출부는 sessionId를 뷰모델 키로 주어 세션이 바뀌면 다른 인스턴스가 되게 한다 —
         * 같은 인스턴스에 새 세션을 덮어씌우면 이미 나간 멱등 키가 다른 세션으로 재시도된다.
         */
        fun factory(
            baseUrl: String,
            sessionId: String,
            sessionToken: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                UploadViewModel(
                    client = OkHttpUploadClient(baseUrl),
                    sessionId = sessionId,
                    sessionToken = sessionToken,
                )
            }
        }
    }
}
