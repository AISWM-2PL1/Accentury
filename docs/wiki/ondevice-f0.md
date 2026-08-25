# 온디바이스 F0 추출 방식 선정 (KAN-103)

조사와 결정 기록 (2026-08-13). 실시간 피치 곡선용 온디바이스 F0 추출기를 확정한다.
전제(아키텍처 설계 원칙): 온디바이스는 **저지연 우선, 정확도는 거칠어도 됨**. 정밀 채점은 서버 RMVPE 몫.

## 결정 요약

| 플랫폼 | 채택 | 폴백 |
|---|---|---|
| **Android** | **자체 Kotlin YIN 구현** (시간영역, 의존성 0) | MIT 구현(sevagh/pitch-detection, dywapitchtrack) JNI 포팅 |
| **iOS** | **동일 YIN 알고리즘의 Swift 이식** (필요시 vDSP 가속) | AudioKit SoundpipeAudioKit `PitchTap` (MIT, ptrack 알고리즘) |

**한 줄 근거**: 기성 라이브러리 1순위 후보였던 TarsosDSP가 **GPL-3.0**이라 상용 클로즈드소스 앱에 쓸 수 없고(배포 시 앱 전체 소스공개 의무), GPL을 배제하면 남는 경로 중 자체 YIN이 지연, 무성음 판별, 플랫폼 일관성, 의존성 면에서 가장 깔끔하다.

## 후보 비교표

비교축은 티켓(KAN-103) 정의를 따른다. 지연이 최우선, 옥타브 오류는 서버가 커버하므로 가중치 낮음.

| 후보 | 지연 | 옥타브 오류 | 무성음 판별 | 잡음 내성 | 라이선스 | iOS 대응 | 판정 |
|---|---|---|---|---|---|---|---|
| **자체 Kotlin YIN** | 수 ms/프레임 (O(W²), W=1024 수준이면 충분) | autocorrelation보다 개선, 완전 억제는 아님 (허용) | ◎ 절대 임계값(0.1~0.2) 내장 - CMNDF 최소값이 임계값을 못 넘으면 무성음 | 중 | 없음(논문 기반 재구현) | ◎ 동일 알고리즘 Swift 이식, KMP 공유도 가능 | **채택** |
| TarsosDSP 2.5 (YIN/FastYin/MPM/AMDF/DWAPT) | 수 ms (경량) | YIN/MPM 수준 | ◎ | 중 | **GPL-3.0 → 탈락** | ✕ JVM 전용 | 탈락 |
| 자체 autocorrelation (구 프로토타입) | 수 ms | ✕ 심함 (확인된 한계) | ✕ 없음 → 곡선 튐 | ✕ 취약 | 없음 | 이식 가능 | 탈락 (품질 미달) |
| aubio (C) | 수 ms | YIN 계열 | ◎ | 중 | **GPLv3 → 탈락** | iOS 빌드 가능하나 무의미 | 탈락 |
| Essentia | - | - | - | - | **AGPLv3** (상업 라이선스 유상) | - | 탈락 |
| sevagh/pitch-detection (C++) | 수 ms | MPM/pYIN 등 선택지 | ◎ | 중~상 (pYIN) | MIT | C++라 양쪽 가능 (JNI/ObjC++ 필요) | 폴백 (참고 구현으로 활용) |
| dywapitchtrack (C) | <23ms 설계 | 억제 특화 | ◎ | 중 | MIT | 가능 (JNI 필요) | 폴백 |
| AudioKit `PitchTap` (iOS 전용) | 실시간 tap 설계 | ptrack 계열 | ○ (amplitude 병행 제공) | 중 | MIT, 유지보수 활발 (2026-07 push) | ◎ iOS 네이티브 | iOS 폴백 |
| Beethoven (iOS, YIN) | 실시간 | YIN | ◎ | 중 | MIT | ◎ | 탈락 (2020년 이후 방치) - 단 **YIN Swift 소스는 MIT라 이식 시 참고** |
| Apple 내장 API | - | - | - | - | - | **F0 검출 API 없음** (AVAudioUnitTimePitch는 피치 "변조"용, Accelerate는 FFT 프리미티브만) | 해당 없음 |

## 선정 근거 상세

1. **라이선스가 결정적 제약**. TarsosDSP와 aubio는 GPLv3, Essentia는 AGPLv3. GPL 계열은 APK/IPA 배포 순간 카피레프트가 걸려 앱 전체 소스공개 의무 발생 → 상용 클로즈드소스 앱에서 즉시 탈락. 이 한 축이 "기성 라이브러리 붙이기" 경로를 대부분 제거한다.
2. **YIN은 자체 구현 비용이 낮다**. 코어(차분함수 → CMNDF → 절대 임계값 → 포물선 보간)가 100~200줄. 16kHz, 2048샘플 창에서 80~400Hz 검출은 표준 설정이고, O(W²) 시간영역 구현도 프레임당 수 ms → NFR "곡선 갱신 100ms 이하" 예산에 크게 여유.
3. **무성음 판별이 알고리즘에 내장**. 구 autocorrelation 프로토타입의 최대 문제(곡선 튐)를 YIN의 절대 임계값이 정확히 해결한다.
4. **플랫폼 일관성**. Android가 자체 YIN이면 iOS도 같은 알고리즘을 이식하는 편이 피치 곡선 특성(무성음 구간, 튐 패턴)이 양 플랫폼에서 동일해진다. AudioKit PitchTap은 알고리즘이 ptrack(Csound 이식)이라 곡선 특성이 달라짐 → 폴백으로만.
5. **의존성 0 + 테스트 용이**. 순수 Kotlin이라 합성 사인파로 JVM 단위 테스트 가능, 추후 Kotlin Multiplatform으로 iOS와 로직 공유 옵션도 열림(캡처는 플랫폼별, DSP 수학만 공유).
6. **YIN 특허**: 명시적 특허 기록을 찾지 못함. MIT 재구현(sevagh), GPL 재구현(aubio)들이 특허 경고 없이 배포 중인 것이 정황 증거. 100% 확정 문서는 없음을 기록해 둔다.

⚠️ **구현 시 주의**: TarsosDSP(GPL) 소스를 보고 옮기면 파생저작물 위험. 참고는 **논문 원전**(de Cheveigné & Kawahara, JASA 2002)과 **MIT 구현**(sevagh/pitch-detection, Beethoven YIN)만 사용한다.

## 임계값 완화와 에너지 게이트 (KAN-105)

원 논문 권장 CMNDF 임계값 0.1~0.2를 그대로 쓰니(0.15) 실제 대화 샘플에서 곡선이 조각났다. 우리 용도는 정밀 F0 측정이 아니라 실시간 억양 곡선이라 판단 기준이 "끊김 < 약간의 오검출"이다. 곡선이 끊기면 사용자가 억양을 읽을 수 없지만 한두 점이 튀는 건 곡선 모양을 해치지 않는다.

| CMNDF 임계값 | 20대 샘플 유성 판정률 | 50대 샘플 유성 판정률 | 옥타브 오류(중앙값 대비 1.8배 밖) |
| --- | --- | --- | --- |
| 0.15 | 85% | 36~48% | - |
| **0.25 (채택)** | 91% | 72~77% | 0~1개 |
| 0.30 | +2~7%p | +2~7%p | - |

0.30은 0.25 대비 이득이 작아 0.25로 정했다.

임계값을 느슨하게 잡으면 마이크 잡음이나 무음 구간에서 CMNDF가 우연히 0.25 아래로 내려가 가짜 피치가 나올 수 있다. 그래서 `VOICED_MIN_RMS = 100f` 에너지 게이트를 함께 넣었다 - 청크 RMS가 이보다 작으면 판정 자체를 하지 않는다. 값은 `AudioQuality.QUIET_RMS_THRESHOLD`와 같아서, 점검 화면의 볼륨 판정(`VoiceCheckController`)과 곡선이 같은 문턱을 공유한다. "점검을 통과한 볼륨이면 곡선이 나온다"가 성립한다. 게이트를 CMNDF 계산 앞에 둬서 무음 프레임은 O(W·τ) 연산을 통째로 건너뛰는 부수 이득도 있다.

## 스파이크 (AC 3)

이 레포 `feature/KAN-103-f0-spike` (PR #21): `audio/YinPitchEstimator.kt`(순수 Kotlin) + `RecordingEngine.Progress.pitchHz` + RecordingViewModel DEBUG 로그로 마이크 F0 확인. 합성 사인파 JVM 테스트 포함. 그래프 없음(후속 티켓).

Codex 1R 반영: 대역 경계 τ에서 포물선 보간이 80~400Hz를 벗어날 수 있어 결과를 `coerceIn`으로 clamp, 로그 포맷 `Locale.US` 고정.

**후속 티켓(실시간 곡선)으로 넘긴 과제** (Codex 검증에서 식별):
- `AudioRecord.read()`가 2048보다 짧은 청크를 연속 반환하면 F0가 계속 null → PCM을 고정 분석 프레임으로 **누적(framing)**하는 계층 필요. 오버랩 분석(2048창 512 슬라이드)과 함께 설계.
- 무성음 판정 검증을 합성 신호 너머로: 실제 무성 자음, 유색잡음, 낮은 SNR 케이스.
- 실기기 지연 벤치마크(프레임당 추정 시간 측정).

## 출처

- TarsosDSP GPL-3.0: https://github.com/JorenSix/TarsosDSP/blob/master/LICENSE (v2.5, 2023-01)
- YIN 원논문: de Cheveigné & Kawahara, JASA 2002 - https://pubs.aip.org/asa/jasa/article/111/4/1917/547221
- aubio GPLv3: https://github.com/aubio/aubio/blob/master/COPYING / Essentia AGPLv3: https://essentia.upf.edu/
- MIT 구현: https://github.com/sevagh/pitch-detection , https://github.com/antoineschmitt/dywapitchtrack
- AudioKit(MIT, PitchTap=ptrack): https://github.com/AudioKit/SoundpipeAudioKit
- Beethoven(MIT, 방치): https://github.com/vadymmarkov/Beethoven
- Oboe(Apache-2.0, I/O 전용 - F0 알고리즘 없음): https://github.com/google/oboe
