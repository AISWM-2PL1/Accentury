/**
 * 브라우저 마이크 지원 판정 + 권한 요청 (KAN-56 Stage 2).
 *
 * 이 파일이 답하는 질문은 둘뿐이다 — **"여기서 녹음이 가능한가"**와 **"사용자가 허락했는가".**
 * 실제 캡처(AudioContext 그래프)는 `capture.ts`가 하고, 여기서는 스트림을 얻자마자 끊는다.
 *
 * ## 왜 지원 판정을 권한 요청보다 먼저 하는가
 *
 * `getUserMedia`는 **보안 컨텍스트(HTTPS 또는 localhost) 전용**이라, 아닌 곳에서는 거부되는 게
 * 아니라 `navigator.mediaDevices` 속성 자체가 없다. 개발 WebView가 로드하는
 * `http://10.0.2.2:5173`이 정확히 그 자리다 — `crypto.randomUUID`가 같은 이유로 없어서 어휘
 * 문항 [다음]이 무증상으로 죽었던 것과 같은 함정이다(트러블슈팅 #22, 2026-08-18). 그때
 * "다음 후보"로 적어 둔 API가 바로 `navigator.mediaDevices.getUserMedia`다.
 *
 * 구분이 중요한 이유는 사용자에게 줄 출구가 다르기 때문이다. 권한 거부는 브라우저 설정에서
 * 되돌릴 수 있지만, 지원 자체가 없는 환경은 사용자가 무엇을 눌러도 바뀌지 않는다 — 앱으로
 * 보내는 것이 유일한 길이다. 그래서 "요청했더니 실패했다"로 뭉뚱그리지 않고 먼저 갈라 둔다.
 */

/** 이 브라우저에서 녹음이 가능한지, 안 되면 무엇이 없는지 */
export type MicSupport = 'supported' | 'insecure-context' | 'no-media-devices' | 'no-audio-worklet'

/**
 * 권한 요청 결과.
 *
 * `denied`(사용자가 막음)와 `unavailable`(장치가 없거나 다른 앱이 점유)을 나누는 이유도
 * 출구가 달라서다 — 앞은 브라우저 설정 안내, 뒤는 "다른 앱을 닫아 보라"는 안내가 맞다.
 */
export type MicPermission = 'granted' | 'denied' | 'unavailable' | 'unsupported'

/**
 * 브라우저 전역에서 읽는 값만 모은 주입 지점.
 *
 * 이 계층을 두는 이유는 jsdom 때문이다 — 테스트 환경에는 `navigator.mediaDevices`도
 * `AudioWorkletNode`도 없어서, 전역을 직접 읽으면 "지원 안 됨" 한 갈래밖에 테스트할 수 없다.
 * 전역 스텁(`vi.stubGlobal`)으로 흉내 낼 수도 있지만, 그러면 테스트가 브라우저 구현 세부에
 * 묶인다. 필요한 사실 세 개만 인터페이스로 뽑아 두면 판정 로직을 순수 함수처럼 검사할 수 있다.
 */
export interface MicEnvironment {
  isSecureContext: boolean
  /** 없으면 이 환경에 `navigator.mediaDevices.getUserMedia`가 없다는 뜻이다 */
  getUserMedia?: (constraints: MediaStreamConstraints) => Promise<MediaStream>
  hasAudioWorklet: boolean
}

/**
 * 실제 브라우저 전역에서 [MicEnvironment]를 읽는다. 모든 접근이 방어적인 이유는 jsdom과
 * 구형 WebView 둘 다 여기 오는데, 없는 속성을 그냥 읽으면 판정 전에 예외로 죽기 때문이다.
 */
export function browserEnvironment(): MicEnvironment {
  const mediaDevices = typeof navigator === 'undefined' ? undefined : navigator.mediaDevices
  return {
    isSecureContext: typeof window !== 'undefined' && window.isSecureContext === true,
    // 메서드를 떼어 내면 `this`가 끊기므로 화살표로 감싼다 — Safari는 이걸 TypeError로 던진다.
    getUserMedia:
      typeof mediaDevices?.getUserMedia === 'function'
        ? (constraints) => mediaDevices.getUserMedia(constraints)
        : undefined,
    hasAudioWorklet: typeof AudioWorkletNode !== 'undefined' && typeof AudioContext !== 'undefined',
  }
}

/**
 * 이 환경에서 녹음이 가능한지 판정한다. 실패 사유는 **먼저 걸린 것 하나만** 돌려준다 —
 * 보안 컨텍스트가 아니면 나머지 검사는 볼 것도 없이 전부 없기 때문이다.
 */
export function microphoneSupport(env: MicEnvironment = browserEnvironment()): MicSupport {
  if (!env.isSecureContext) return 'insecure-context'
  if (typeof env.getUserMedia !== 'function') return 'no-media-devices'
  if (!env.hasAudioWorklet) return 'no-audio-worklet'
  return 'supported'
}

/** 사용자가 명시적으로 막았을 때 브라우저들이 쓰는 DOMException 이름 */
const DENIED_ERROR_NAMES: ReadonlySet<string> = new Set([
  'NotAllowedError',
  'SecurityError',
  // 표준 이전 이름. 구형 WebView가 아직 이걸 준다
  'PermissionDeniedError',
])

/**
 * `getUserMedia` 실패를 두 갈래로 접는다.
 *
 * 이름을 하나하나 나열하지 않고 **거부만 목록으로 두고 나머지를 전부 `unavailable`로** 미는
 * 이유는, 모르는 이름이 왔을 때 "사용자가 막았다"고 단정하면 안 되기 때문이다 — 그 경우
 * 사용자는 이미 허용한 권한을 설정에서 다시 찾아 헤매게 된다. 반대 방향의 오판(장치 문제로
 * 안내)은 [다시 시도]로 회복된다.
 */
export function classifyMediaError(error: unknown): 'denied' | 'unavailable' {
  /*
   * `instanceof Error`로 좁히지 않고 `name` 속성만 본다. 브라우저에서는 `DOMException`이
   * Error를 상속하지만 jsdom 구현은 그렇지 않아서, instanceof로 거르면 테스트에서만 모든
   * 거부가 `unavailable`로 접힌다 — 실기와 테스트의 판정이 갈리는 것이 이 함수에서 가장
   * 나쁜 결과다. 오류 객체가 무엇이든 `name`이 있으면 그걸로 판정한다.
   */
  const name = typeof error === 'object' && error !== null ? (error as { name?: unknown }).name : ''
  return typeof name === 'string' && DENIED_ERROR_NAMES.has(name) ? 'denied' : 'unavailable'
}

/**
 * 마이크 권한을 요청한다. 인트로 [시작하기]의 게이트가 유일한 호출자다.
 *
 * **성공하면 트랙을 곧바로 끊는다.** 게이트에 필요한 것은 권한이라는 사실뿐이고, 스트림은
 * 실제 녹음을 시작할 때 `capture.ts`가 다시 잡는다. 여기서 열어 둔 채로 넘기면 인트로부터
 * 녹음 화면까지 마이크 표시등이 계속 켜져 있고(사용자에게는 "몰래 듣는 중"으로 보인다),
 * iOS에서는 잡아 둔 스트림이 오디오 세션을 붙들어 다음 컨텍스트 생성과 충돌한다.
 */
export async function requestMicrophonePermission(
  env: MicEnvironment = browserEnvironment(),
): Promise<MicPermission> {
  const support = microphoneSupport(env)
  // 지원되지 않는 환경에서는 요청 자체를 하지 않는다 — 어차피 던질 호출이고, 브라우저에 따라
  // 권한 프롬프트가 잠깐 떴다 사라지는 인상만 남는다.
  if (support !== 'supported' || env.getUserMedia === undefined) return 'unsupported'

  try {
    const stream = await env.getUserMedia({ audio: true })
    for (const track of stream.getTracks()) track.stop()
    return 'granted'
  } catch (error) {
    return classifyMediaError(error)
  }
}
