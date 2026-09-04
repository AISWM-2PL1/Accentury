package com.accentury.app.recording

import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.accentury.app.audio.PcmSource
import com.accentury.app.audio.RecordingEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 목소리 점검 화면의 구동부 (KAN-105 2단계). 엔진을 돌리고 [VoiceCheckController]에 먹인다.
 *
 * 판정은 전부 컨트롤러가 하고 여기는 Android 결선만 한다 — [RecordingViewModel]과 같은 루프지만
 * 결정적인 차이가 하나 있다: **PCM을 받지 않는다.** 점검은 사용자를 재는 것이 아니라 마이크가
 * 잘 열렸는지 확인하는 절차라, 저장하거나 서버로 보낼 이유가 전혀 없다 (FR-DP-02).
 */
class VoiceCheckViewModel(
    private val engine: RecordingEngine,
) : ViewModel() {

    private val controller = VoiceCheckController()

    private val _state = MutableStateFlow(controller.state)
    val state: StateFlow<VoiceCheckState> = _state.asStateFlow()

    private var listeningJob: Job? = null

    /**
     * 듣기를 시작한다. 화면 진입마다 불려도 안전하다 — 이미 듣는 중이거나 판정이 끝난 뒤에는
     * 아무 일도 하지 않는다. 끝난 판정을 되돌리는 건 [restart] 하나뿐이다.
     */
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (listeningJob?.isActive == true) return
        if (_state.value !is VoiceCheckState.Listening) return
        listen()
    }

    /** 시간이 다 됐거나 실패한 뒤의 [다시 시도]. 쌓인 것을 전부 버리고 처음부터 듣는다. */
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun restart() {
        listen()
    }

    /**
     * 마이크를 놓는다. 화면이 컴포지션에서 빠질 때 부른다 — 이 뷰모델은 화면보다 오래 살아서
     * (회전·프로세스 유지) 여기서 안 끊으면 점검 화면이 사라진 뒤에도 마이크가 열려 있다.
     *
     * 정지 요청이 아니라 취소인 이유: 취소는 소스의 finally까지 즉시 내려가 AudioRecord를
     * 놓지만, 정지 요청은 다음 청크 경계까지 기다린다.
     *
     * 취소만 하고 job 참조는 지우지 않는다 — 다음 [listen]이 이 job의 **완료**를 기다려야 한다.
     */
    fun stop() {
        listeningJob?.cancel()
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun listen() {
        /*
         * 중간에 끊긴 듣기의 프레임은 물려받지 않는다. 엔진이 새로 서면 timestampMs가 0부터
         * 다시 시작하는데, 남아 있던 프레임과 이어 붙이면 시간축이 뒤로 감겨 곡선이 뒤엉킨다.
         */
        controller.restart()
        _state.value = controller.state

        /*
         * 이전 캡처가 **완전히 끝난 뒤에** 새 캡처를 연다. cancel()이 돌아왔다고 마이크가 풀린
         * 것이 아니다 — AudioRecord.stop/release는 소스 flow의 finally에서, 그것도 IO 디스패처
         * 위에서 일어나므로 cancel() 반환 시점엔 아직 마이크를 쥐고 있을 수 있다. 회전으로
         * 화면이 즉시 다시 서서 stop() 직후 start()가 불리면, 새 AudioRecord가 아직 살아 있는
         * 이전 것과 겹쳐 "마이크 점유 중"으로 초기화에 실패한다. join으로 직렬화해서 막는다.
         */
        val previous = listeningJob
        listeningJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            val outcome = engine.record { progress ->
                // true면 준비가 끝났다는 뜻 - 더 들어도 판정이 안 바뀌므로 마이크를 놓는다.
                if (controller.onProgress(progress.rms, progress.pitchFrames)) engine.requestStop()
                // 상태에 넣는 목록의 복사본 규칙은 컨트롤러가 지킨다 (snapshot).
                _state.value = controller.state
            }
            when (outcome) {
                /*
                 * outcome.pcm은 **읽지 않는다**. 점검 오디오는 보관도 전송도 하지 않으므로
                 * (FR-DP-02) 여기서 참조를 만들지 않는 것이 그 규칙의 실제 이행이다 -
                 * 지역 변수에 한 번 담는 순간 "어디까지 살아 있는가"를 따져야 할 값이 생긴다.
                 */
                is RecordingEngine.Outcome.Success -> controller.onStopped()
                is RecordingEngine.Outcome.Failure -> controller.onFailed(outcome.reason)
            }
            _state.value = controller.state
        }
    }

    companion object {
        /**
         * PCM 소스를 골라 넣는 팩토리. 소스 선택에 Context가 필요해
         * ([com.accentury.app.audio.defaultPcmSource]) 호출자가 만들어 넘긴다 —
         * 디버그 빌드의 가짜 마이크가 점검 화면에도 그대로 적용된다.
         */
        fun factory(source: PcmSource): ViewModelProvider.Factory = viewModelFactory {
            initializer { VoiceCheckViewModel(RecordingEngine(source)) }
        }
    }
}
