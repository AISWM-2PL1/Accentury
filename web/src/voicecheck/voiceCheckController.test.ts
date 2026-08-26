import { describe, expect, it } from 'vitest'
import type { PitchFrame } from '../audio/pitchTracker'
import { QUIET_RMS_THRESHOLD } from '../audio/quality'
import { sineChunk } from '../audio/testing/fakeCapture'
import { CENTER_MIN_VOICED_FRAMES } from '../recording/userCurve'
import { VoiceCheckController, voiceCheckRms, type VoiceCheckState } from './voiceCheckController'

/** 실제 분석기와 같은 32ms 간격으로 유성 프레임을 만든다 (`OverlappedFramer`의 hop 기준) */
function voiced(count: number, hz = CENTER_HZ, startMs = 0): PitchFrame[] {
  return Array.from({ length: count }, (_, i) => ({
    timestampMs: startMs + i * FRAME_MS,
    pitchHz: hz,
  }))
}

function unvoiced(count: number, startMs = 0): PitchFrame[] {
  return Array.from({ length: count }, (_, i) => ({
    timestampMs: startMs + i * FRAME_MS,
    pitchHz: null,
  }))
}

function listening(state: VoiceCheckState): Extract<VoiceCheckState, { phase: 'listening' }> {
  if (state.phase !== 'listening') throw new Error(`듣는 중이 아니다: ${state.phase}`)
  return state
}

const FRAME_MS = 32
const CENTER_HZ = 220

/** 통과선을 넘는 조각 볼륨 */
const LOUD = QUIET_RMS_THRESHOLD * 3
/** 통과선에 못 미치는 조각 볼륨 */
const QUIET = QUIET_RMS_THRESHOLD / 2

describe('VoiceCheckController — 판정 (앱 VoiceCheckControllerTest 포팅)', () => {
  it('말하기 전에는 안내가 말해 달라는 쪽이다', () => {
    const controller = new VoiceCheckController()

    const initial = listening(controller.state)
    expect(initial.hint).toBe('SAY_IT')
    expect(initial.centerHz).toBeNull()

    // 무성 프레임만 들어와도 마찬가지다 — 소리는 났는데 목소리가 아니었던 경우다
    expect(controller.onProgress(LOUD, unvoiced(5))).toBe(false)
    expect(listening(controller.state).hint).toBe('SAY_IT')
  })

  it('유성 프레임이 모자라면 계속 듣는다', () => {
    const controller = new VoiceCheckController()

    // 볼륨은 충분한데 중심을 잠글 만큼 말하지 않았다
    const stopRequested = controller.onProgress(LOUD, voiced(CENTER_MIN_VOICED_FRAMES - 1))

    expect(stopRequested, '아직 판정이 안 났으니 캡처를 세우지 않는다').toBe(false)
    const state = listening(controller.state)
    expect(state.hint).toBe('KEEP_GOING')
    expect(state.voicedCount).toBe(CENTER_MIN_VOICED_FRAMES - 1)
    expect(state.centerHz, '8개에 못 미치면 중심이 안 잠긴다').toBeNull()
    expect(state.loudEnough).toBe(true)
  })

  it('중심은 잡혔는데 볼륨이 모자라면 더 크게 말하라고 한다', () => {
    const controller = new VoiceCheckController()

    const stopRequested = controller.onProgress(QUIET, voiced(CENTER_MIN_VOICED_FRAMES))

    expect(stopRequested, '볼륨이 모자라면 아직 준비가 아니다').toBe(false)
    const state = listening(controller.state)
    expect(state.hint).toBe('TOO_QUIET')
    expect(state.centerHz).toBeCloseTo(CENTER_HZ, 3)
    expect(state.loudEnough).toBe(false)
  })

  it('조용히 잡은 중심은 뒤늦게 크게 말해도 그대로다', () => {
    const controller = new VoiceCheckController()

    controller.onProgress(QUIET, voiced(CENTER_MIN_VOICED_FRAMES))
    // 안내를 보고 크게 다시 말했다. 이번엔 훨씬 높은 음이지만 중심은 이미 잠겼다
    const stopRequested = controller.onProgress(
      LOUD,
      voiced(8, CENTER_HZ * 2, CENTER_MIN_VOICED_FRAMES * FRAME_MS),
    )

    expect(stopRequested, '준비가 끝났으니 캡처를 세운다').toBe(true)
    const ready = controller.state
    if (ready.phase !== 'ready') throw new Error('준비 상태가 아니다')
    expect(ready.centerHz, '중심은 처음 8개의 중앙값이다').toBeCloseTo(CENTER_HZ, 3)
    expect(ready.frames).toHaveLength(CENTER_MIN_VOICED_FRAMES + 8)
  })

  it('한 번 크게 말했으면 뒤에 조용해져도 통과한다', () => {
    const controller = new VoiceCheckController()

    // 크게 시작했지만 아직 프레임이 모자라고
    controller.onProgress(LOUD, voiced(4))
    // 말끝이 잦아들며 나머지 프레임이 채워졌다
    const stopRequested = controller.onProgress(QUIET, voiced(4, CENTER_HZ, 4 * FRAME_MS))

    expect(stopRequested, '볼륨 판정은 최댓값 기준이라 앞의 큰 소리가 근거로 남는다').toBe(true)
    expect(controller.state.phase).toBe('ready')
  })

  it('준비된 뒤 도착한 조각은 판정을 흔들지 못한다', () => {
    const controller = new VoiceCheckController()
    controller.onProgress(LOUD, voiced(CENTER_MIN_VOICED_FRAMES))
    const ready = controller.state

    // 정지 요청과 실제 정지 사이에 워클릿 잔여분이 한둘 더 온다
    const stopRequested = controller.onProgress(
      0,
      voiced(4, CENTER_HZ, CENTER_MIN_VOICED_FRAMES * FRAME_MS),
    )

    expect(stopRequested).toBe(false)
    expect(controller.state).toEqual(ready)
  })

  it('듣기가 끝났는데 준비가 아니면 시간 초과다', () => {
    const controller = new VoiceCheckController()
    controller.onProgress(QUIET, voiced(CENTER_MIN_VOICED_FRAMES))

    controller.onStopped()

    const state = controller.state
    if (state.phase !== 'timedOut') throw new Error('시간 초과가 아니다')
    expect(state.hint, '무엇이 모자랐는지가 남는다').toBe('TOO_QUIET')
    expect(state.frames).toHaveLength(CENTER_MIN_VOICED_FRAMES)
  })

  it('준비된 뒤의 종료는 준비를 그대로 둔다', () => {
    const controller = new VoiceCheckController()
    controller.onProgress(LOUD, voiced(CENTER_MIN_VOICED_FRAMES))
    const ready = controller.state

    // 준비가 되면 캡처 정지를 요청하므로 종료 통지는 늘 이 뒤에 온다
    controller.onStopped()

    expect(controller.state).toEqual(ready)
  })

  it('캡처 실패는 실패 상태가 되고 종료 통지가 덮지 않는다', () => {
    const controller = new VoiceCheckController()

    controller.onFailed('마이크 권한이 필요해요')
    controller.onStopped()

    const state = controller.state
    if (state.phase !== 'failed') throw new Error('실패 상태가 아니다')
    expect(state.reason).toBe('마이크 권한이 필요해요')
  })

  it('다시 시도하면 전부 초기화된다', () => {
    const controller = new VoiceCheckController()
    controller.onProgress(LOUD, voiced(CENTER_MIN_VOICED_FRAMES))
    expect(controller.state.phase).toBe('ready')

    controller.restart()

    const state = listening(controller.state)
    expect(state.frames).toEqual([])
    expect(state.voicedCount).toBe(0)
    expect(state.level).toBe(0)
    expect(state.loudEnough, '볼륨 기록도 함께 비운다').toBe(false)
    expect(state.centerHz).toBeNull()
    expect(state.hint).toBe('SAY_IT')
  })

  it('레벨은 최근 조각값이라 조용해지면 함께 내려간다', () => {
    const controller = new VoiceCheckController()

    controller.onProgress(LOUD, voiced(2))
    controller.onProgress(QUIET, voiced(2, CENTER_HZ, 2 * FRAME_MS))

    const state = listening(controller.state)
    expect(state.level).toBe(QUIET)
    expect(state.loudEnough, '통과 판정은 최댓값이라 내려가지 않는다').toBe(true)
  })

  it('상태에 실린 프레임 목록은 복사본이라 다음 조각에 안 바뀐다', () => {
    const controller = new VoiceCheckController()

    controller.onProgress(QUIET, voiced(2))
    const first = listening(controller.state).frames
    controller.onProgress(QUIET, voiced(2, CENTER_HZ, 2 * FRAME_MS))

    expect(first).toHaveLength(2)
  })
})

describe('voiceCheckRms — 스케일', () => {
  it('브라우저의 -1..+1 조각을 16-bit 원 스케일로 되돌린다', () => {
    // 진폭 0.5 사인파의 rms는 0.5/√2 ≈ 0.354 — 원 스케일로 약 11585다
    expect(voiceCheckRms(sineChunk(100, { amplitude: 0.5 }))).toBeCloseTo(11585, -2)
  })

  it('무음 조각은 통과선에 못 미친다 — 빈 조각도 0이다', () => {
    expect(voiceCheckRms(new Float32Array(1024))).toBe(0)
    expect(voiceCheckRms(new Float32Array(0))).toBe(0)
    expect(voiceCheckRms(sineChunk(100, { amplitude: 0.001 }))).toBeLessThan(QUIET_RMS_THRESHOLD)
  })
})
