package com.accentury.app.audio

import android.content.Context

/**
 * 이번 실행이 쓸 PCM 소스를 고른다 (릴리스 변형).
 *
 * **릴리스는 마이크뿐이고, 파일 소스 클래스 자체가 없다.** 가짜 마이크 도구
 * (`FilePcmSource`·`MonitoredPcmSource`와 디버그 쪽 `defaultPcmSource`)는 `src/debug`에만 있어
 * 릴리스 APK의 `classes.dex`에 아예 실리지 않는다. "릴리스에는 파일 재생 경로가 없다"를
 * `BuildConfig.DEBUG` 같은 실행 중 분기가 아니라 소스셋으로 보장한다 - 분기는 최적화가
 * 꺼져 있으면(현재 릴리스 설정) 코드를 그대로 남기지만, 없는 클래스는 남을 수가 없다.
 *
 * [context]는 쓰지 않는다. 디버그 변형이 assets를 열어야 해서 필요한 인자이고, 두 변형의
 * 시그니처가 같아야 호출부([com.accentury.app.MainActivity])가 변형을 몰라도 된다.
 */
@Suppress("UNUSED_PARAMETER")
fun defaultPcmSource(context: Context): PcmSource = AudioRecorder()
