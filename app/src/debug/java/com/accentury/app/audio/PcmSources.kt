package com.accentury.app.audio

import android.content.Context
import com.accentury.app.BuildConfig

/**
 * 이번 실행이 쓸 PCM 소스를 고른다 (디버그 변형).
 *
 * 에뮬레이터 마이크는 무음(0)만 준다. 피치 곡선을 다듬으려면 매번 같은 음성이 필요하므로,
 * 디버그 빌드에 한해 assets의 WAV를 마이크 자리에 끼울 수 있게 해 둔다.
 *
 * 사용법 - 디버그 빌드를 설치할 때 파일명을 프로퍼티로 준다:
 * ```
 * ./gradlew :app:installDebug -PfakeMic=fake_mic.wav
 * ```
 * 프로퍼티 없이 빌드하면 [FAKE_MIC_ASSET][BuildConfig.FAKE_MIC_ASSET]이 빈 문자열이라 평소대로
 * 마이크를 쓴다.
 *
 * `BuildConfig.DEBUG`를 다시 확인하지 않는 이유: 이 파일과 [FilePcmSource]·[MonitoredPcmSource]가
 * `src/debug`에만 있어 릴리스 빌드에는 **클래스 자체가 없다**. 파일 재생 경로가 없다는 것을
 * 실행 중 분기가 아니라 소스셋이 보장한다 (릴리스 변형은 `src/release`의 같은 이름 파일).
 *
 * WAV는 16kHz 모노 16bit여야 한다 ([FilePcmSource] 참고). `app/src/debug/assets/`에 두면
 * 디버그 빌드에만 실린다.
 *
 * 파일 소스는 스피커로도 나간다 ([MonitoredPcmSource]) - 에뮬레이터면 호스트 스피커로 들린다.
 */
fun defaultPcmSource(context: Context): PcmSource {
    val assetName = BuildConfig.FAKE_MIC_ASSET
    if (assetName.isEmpty()) return AudioRecorder()
    // Activity가 아니라 앱 컨텍스트를 붙잡는다 - 소스는 ViewModel과 함께 화면 회전 너머까지 산다.
    val assets = context.applicationContext.assets
    return MonitoredPcmSource(FilePcmSource(open = { assets.open(assetName) }))
}
