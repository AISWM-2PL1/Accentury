/**
 * Web Audio 캡처 결선 (KAN-56 Stage 2).
 *
 * 실제 브라우저에서만 도는 유일한 오디오 파일이다 — jsdom에는 `AudioContext`가 없어 단위
 * 테스트가 없고, 검증은 Stage 4의 실기(에뮬레이터·아이폰 사파리) 확인이 맡는다. 그래서 이
 * 파일은 **가능한 한 얇게** 유지한다: 로직은 전부 순수 계층(`recordingBuffer`)에 있고 여기는
 * 노드를 잇고 끊는 일만 한다. 테스트할 수 없는 코드에는 판단을 두지 않는다는 뜻이다.
 *
 * ## 왜 AudioWorklet인가
 *
 * 캡처된 샘플을 자바스크립트로 가져오는 길은 둘이다. 옛 `ScriptProcessorNode`는 **메인
 * 스레드**에서 콜백이 도는데, 메인 스레드는 리액트 렌더·레이아웃·이미지 디코드와 같은 줄에
 * 서 있다. 한 프레임만 밀려도 오디오 콜백이 늦고, 늦으면 그 구간 샘플이 그냥 사라진다
 * (녹음에 딸깍 소리가 나거나 조용히 짧아진다). AudioWorklet은 오디오 렌더 스레드에서 직접
 * 돌아 이 경쟁 자체가 없다 — 대신 그 스레드에서는 DOM도 리액트도 못 만지므로, 얻은 샘플을
 * `postMessage`로 메인에 넘긴다. 녹음 중 화면이 애니메이션하는 우리 상황에서 이 차이는
 * 이론이 아니라 실측으로 드러난다.
 *
 * ## 순서가 계약이다 (iOS)
 *
 * `getUserMedia` → `AudioContext` 순서를 지킨다. iOS는 마이크 경로가 활성화되는 순간
 * 하드웨어 레이트를 48kHz로 바꾸는데, 컨텍스트를 먼저 만들면 그 컨텍스트는 바뀌기 전 레이트를
 * 들고 있어 실제 입력과 어긋난다. 스트림을 먼저 열면 컨텍스트가 확정된 레이트 위에서 생긴다.
 * 이 순서와 아래 주석의 결정들은 전부 **Stage 4에서 실기로 확인할 가설**이라고 표시해 둔다.
 */

import { classifyMediaError } from './microphone'

/** 캡처 실패의 갈래. 화면 문구와 Stage 4 진단이 이 값으로 갈린다 */
export type CaptureFailure = 'audio-context-suspended' | 'worklet-load-failed' | 'permission' | 'unavailable'

export class CaptureError extends Error {
  constructor(
    readonly reason: CaptureFailure,
    message?: string,
  ) {
    super(message ?? reason)
    this.name = 'CaptureError'
  }
}

/** 진행 중인 캡처 한 건 */
export interface Capture {
  /** 하드웨어 캡처 레이트. `AudioContext` 생성 뒤 읽은 값이다 */
  readonly sampleRate: number
  /**
   * 캡처를 끝낸다 — 트랙 정지, 노드 해제, 컨텍스트 종료까지. 여러 번 불러도 안전하고,
   * 두 번째 호출은 첫 호출과 같은 약속을 돌려준다.
   *
   * **비동기인 이유는 꼬리 때문이다.** 워클릿이 아직 4096샘플을 못 채운 잔여분을 들고 있어,
   * 그냥 끊으면 마지막 최대 85ms가 사라진다. 정지 요청을 보내고 잔여분이 도착할 때까지
   * 기다린 뒤에 끊는다 — 즉 이 약속이 풀린 시점에는 마지막 조각까지 `onChunk`로 전달된 상태다.
   */
  stop(): Promise<void>
}

/**
 * 캡처를 시작하는 함수. 훅이 이 타입으로 주입받기 때문에 테스트는 가짜 구현을 넣어
 * 브라우저 없이 상태 기계를 검사할 수 있다.
 */
export type CaptureFactory = (onChunk: (chunk: Float32Array) => void) => Promise<Capture>

/** 워클릿이 한 번에 넘기는 샘플 수 */
const CHUNK_SAMPLES = 4096

/** 등록 이름. 같은 컨텍스트에 두 번 등록하면 던지므로 컨텍스트마다 한 번만 쓴다 */
const PROCESSOR_NAME = 'accentury-capture'

/** 잔여분 회신을 기다리는 상한 (ms). 워클릿이 이미 죽었으면 회신이 영영 오지 않는다 */
const FLUSH_TIMEOUT_MS = 100

/**
 * 워클릿 프로세서 원본.
 *
 * 별도 파일이 아니라 문자열로 두고 Blob URL로 만들어 넣는다. `addModule`은 **URL만** 받아서
 * 보통은 진짜 파일을 하나 더 배포해야 하는데, 그러면 Vite 개발 서버·번들 출력·CloudFront
 * 배포 세 경로에서 각각 그 파일이 같은 경로로 서빙되도록 설정을 맞춰야 한다. 문자열이면
 * 번들 안에 그대로 들어가 세 경로에서 전부 똑같이 동작한다 — 캡처가 안 되는 원인이 빌드
 * 설정일 가능성을 아예 없앤다.
 *
 * 128프레임(`process` 한 번)마다 보내지 않고 4096샘플씩 모아 보내는 이유는 메시지 수다.
 * 48kHz에서 128프레임은 2.7ms라 초당 375번인데, 그 각각이 구조화 복제와 이벤트 루프를 거친다.
 * 4096이면 초당 약 12번으로 줄면서 지연은 85ms — 녹음 시간 표시가 갱신되는 간격으로 충분하다.
 */
const PROCESSOR_SOURCE = `
class AccenturyCaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this.buffer = new Float32Array(${CHUNK_SAMPLES})
    this.filled = 0
    this.done = false
    this.port.onmessage = (event) => {
      if (event.data !== 'flush') return
      this.emit()
      this.done = true
      this.port.postMessage('flushed')
    }
  }

  /** 채우다 만 잔여분을 보낸다. 전송한 버퍼는 이쪽에서 못 쓰므로 새로 만든다 */
  emit() {
    if (this.filled === 0) return
    const out = this.buffer.slice(0, this.filled)
    this.buffer = new Float32Array(${CHUNK_SAMPLES})
    this.filled = 0
    this.port.postMessage(out, [out.buffer])
  }

  process(inputs) {
    if (this.done) return false
    const channel = inputs[0] && inputs[0][0]
    if (!channel) return true

    let offset = 0
    while (offset < channel.length) {
      const room = this.buffer.length - this.filled
      const take = Math.min(room, channel.length - offset)
      this.buffer.set(channel.subarray(offset, offset + take), this.filled)
      this.filled += take
      offset += take
      if (this.filled === this.buffer.length) this.emit()
    }
    return true
  }
}

registerProcessor('${PROCESSOR_NAME}', AccenturyCaptureProcessor)
`

/**
 * 마이크 스트림을 연다. 인트로 게이트에서 이미 권한을 받았으므로 보통 즉시 풀린다.
 *
 * 제약을 셋 다 끈다(`echoCancellation`·`noiseSuppression`·`autoGainControl`). 전부 통화용
 * 처리라 억양 분석에 해롭다 — 특히 자동 게인은 음량을 실시간으로 주무르며 F0 궤적과 무관하게
 * 진폭을 흔들고, 잡음 억제는 무성 구간을 통째로 0으로 만들어 무음 비율 판정을 왜곡한다.
 * 브라우저가 제약을 무시할 수는 있어도(강제가 아니다) 요청은 남긴다.
 */
async function openStream(): Promise<MediaStream> {
  const getUserMedia = navigator.mediaDevices?.getUserMedia
  if (typeof getUserMedia !== 'function') {
    throw new CaptureError('unavailable', '이 브라우저에서는 마이크를 열 수 없다')
  }
  try {
    return await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false,
      },
    })
  } catch (error) {
    const reason = classifyMediaError(error) === 'denied' ? 'permission' : 'unavailable'
    throw new CaptureError(reason, error instanceof Error ? error.message : undefined)
  }
}

/** 워클릿 모듈을 Blob URL로 심는다. URL은 심자마자 회수한다 — 모듈은 이미 컨텍스트가 들었다 */
async function installProcessor(ctx: AudioContext): Promise<void> {
  const url = URL.createObjectURL(new Blob([PROCESSOR_SOURCE], { type: 'text/javascript' }))
  try {
    await ctx.audioWorklet.addModule(url)
  } catch (error) {
    throw new CaptureError('worklet-load-failed', error instanceof Error ? error.message : undefined)
  } finally {
    URL.revokeObjectURL(url)
  }
}

/**
 * 실제 브라우저 캡처.
 *
 * @param onChunk 워클릿이 보내온 조각. **호출자가 복사해 보관해야 한다** —
 *   여기서 넘기는 배열은 전송(transfer)된 것이라 다음 조각이 같은 메모리를 쓸 수 있다
 *   ([RecordingBuffer.push]가 그 복사를 한다).
 */
export const webAudioCapture: CaptureFactory = async (onChunk) => {
  // (1) 스트림이 먼저다 — iOS 레이트 전환 때문. 파일 상단 주석 참고.
  const stream = await openStream()

  let ctx: AudioContext | undefined
  try {
    /*
     * (2) 컨텍스트. `sampleRate` 옵션을 주지 않는다 — iOS는 하드웨어와 다른 값을 요구하면
     * 무시하거나 던지고, 어차피 16kHz 변환은 우리 리샘플러가 직접 하기 때문에(Stage 1)
     * 브라우저에 레이트를 맡길 이유가 없다. 하드웨어가 주는 대로 받아서 우리가 내린다.
     */
    ctx = new AudioContext()
    await ctx.resume()
    if (ctx.state !== 'running') {
      /*
       * iOS는 **사용자 제스처 안에서 시작한 컨텍스트만** 돌려준다. 호출자의 `start()`가 탭
       * 핸들러 안에서 도니 보통은 이 조건 안에 있지만, 그 사이에 `await`이 하나라도 끼면
       * 브라우저가 "제스처 유효 구간"을 벗어났다고 볼 수 있다. 이 오류가 그 신호다 —
       * Stage 4에서 이게 뜨면 원인은 권한이 아니라 호출 시점이다.
       */
      throw new CaptureError('audio-context-suspended', '오디오 컨텍스트가 시작되지 않았다')
    }

    // (3) 워클릿 모듈
    await installProcessor(ctx)

    // (4) 그래프: 마이크 → 워클릿 → (무음 게인) → 출력
    const source = ctx.createMediaStreamSource(stream)
    const worklet = new AudioWorkletNode(ctx, PROCESSOR_NAME, {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      channelCount: 1,
      channelCountMode: 'explicit',
    })
    /*
     * `ctx.destination`에 **직접** 잇지 않는다. 그러면 사용자에게 자기 목소리가 그대로 들리고
     * (모니터링은 요구가 아니다), iOS에서는 출력에 연결된 순간 오디오 세션이 재생 경로로
     * 잡히면서 마이크가 스피커로 라우팅된다. 그런데 일부 브라우저는 **출력까지 이어지지 않은
     * 노드를 아예 돌리지 않는다** — 아무도 듣지 않는 계산은 생략한다는 최적화다. 그래서
     * 게인 0짜리 노드를 사이에 끼워 "이어져는 있지만 아무것도 들리지 않는" 상태로 만든다.
     */
    const silentSink = ctx.createGain()
    silentSink.gain.value = 0

    let flushed: (() => void) | undefined
    worklet.port.onmessage = (event: MessageEvent) => {
      if (event.data instanceof Float32Array) {
        onChunk(event.data)
        return
      }
      if (event.data === 'flushed') flushed?.()
    }

    source.connect(worklet)
    worklet.connect(silentSink)
    silentSink.connect(ctx.destination)

    const context = ctx
    let stopping: Promise<void> | undefined

    return {
      sampleRate: context.sampleRate,
      stop(): Promise<void> {
        if (stopping !== undefined) return stopping
        stopping = (async () => {
          // 잔여분 회수. 워클릿이 죽어 회신이 오지 않는 경우를 대비해 상한을 둔다 —
          // 여기서 영원히 기다리면 [다음] 버튼이 영영 나타나지 않는다.
          await new Promise<void>((resolve) => {
            const timer = setTimeout(resolve, FLUSH_TIMEOUT_MS)
            flushed = () => {
              clearTimeout(timer)
              resolve()
            }
            worklet.port.postMessage('flush')
          })

          worklet.port.onmessage = null
          source.disconnect()
          worklet.disconnect()
          silentSink.disconnect()
          for (const track of stream.getTracks()) track.stop()
          // 컨텍스트 종료 실패는 삼킨다. 이미 닫혔거나 페이지가 사라지는 중일 뿐이고,
          // 사용자가 할 수 있는 일도 볼 필요도 없는 오류다.
          await context.close().catch(() => {})
        })()
        return stopping
      },
    }
  } catch (error) {
    // 중간에 실패하면 열어 둔 것을 전부 되돌린다. 스트림을 남기면 마이크 표시등이 켜진 채로
    // 오류 화면이 뜬다 — 사용자에게는 실패했다면서 계속 듣고 있는 것으로 보인다.
    for (const track of stream.getTracks()) track.stop()
    await ctx?.close().catch(() => {})
    throw error
  }
}
