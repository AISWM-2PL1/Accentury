import { describe, expect, it, vi } from 'vitest'
import type { FetchLike } from '../progress/fetchTestDefinition'
import type { ClientQuality } from './quality'
import type { Recording } from './recordingBuffer'
import { UploadError, uploadRecording } from './uploadRecording'

const API_BASE = 'http://localhost:8080'
const SESSION_ID = 'sess-1'
const ITEM_ID = 'item-3'
const TOKEN = 'session-token'
const ATTEMPT_ID = 'attempt-abc'

const QUALITY: ClientQuality = { rms: 0.3, peak: 0.8, silenceRatio: 0.1, clipped: false }

/** 업로드가 실어 보낼 것만 든 최소 녹음. 바이트 내용은 이 파일의 관심사가 아니다 */
function recording(): Recording {
  return {
    wav: new Uint8Array([0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4]),
    durationMs: 2_400,
    sourceSampleRate: 48_000,
    quality: QUALITY,
    status: 'NORMAL',
  }
}

function upload(overrides: Partial<Parameters<typeof uploadRecording>[0]> = {}) {
  return {
    apiBase: API_BASE,
    sessionId: SESSION_ID,
    itemId: ITEM_ID,
    sessionToken: TOKEN,
    attemptId: ATTEMPT_ID,
    recording: recording(),
    ...overrides,
  }
}

/** Response 대역 (fetchTestDefinition.test.ts와 같은 방식) */
function response(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => headers[name] ?? null },
    json: async () => {
      if (body === undefined) throw new SyntaxError('본문이 JSON이 아니다')
      return body
    },
  } as unknown as Response
}

function stubFetch(...responses: Response[]): ReturnType<typeof vi.fn<FetchLike>> {
  const queue = [...responses]
  return vi.fn<FetchLike>(async () => queue.shift() ?? response(200, { analysisJobId: 'job-1' }))
}

/** 요청 본문에서 파트를 꺼낸다 — jsdom은 파일 파트를 File로 돌려준다 */
function parts(init: RequestInit | undefined) {
  const body = init?.body
  if (!(body instanceof FormData)) throw new Error('본문이 FormData가 아니다')
  return { audio: body.get('audio'), meta: body.get('meta') }
}

/**
 * Blob의 바이트를 읽는다. jsdom의 Blob에는 `arrayBuffer()`가 없어(브라우저에는 있다)
 * 구형 `FileReader` 경로로 읽는다 — 테스트 환경의 한계라 제품 코드와는 무관하다.
 */
function readBytes(blob: Blob): Promise<ArrayBuffer> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as ArrayBuffer)
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(blob)
  })
}

describe('요청 조립', () => {
  it('세션·문항 경로와 헤더 세 개를 싣는다', async () => {
    const fetchImpl = stubFetch(response(202, { analysisJobId: 'job-1' }))

    await uploadRecording(upload(), fetchImpl)

    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe(`${API_BASE}/v0/sessions/${SESSION_ID}/voice-items/${ITEM_ID}/recording`)
    expect(init?.method).toBe('POST')
    const headers = init?.headers as Record<string, string>
    expect(headers.Authorization).toBe(`Bearer ${TOKEN}`)
    // 멱등 키는 시도 식별자 그 자체다 — 새로 만들지 않고 받은 값을 그대로 싣는다 (§5.2)
    expect(headers['Idempotency-Key']).toBe(ATTEMPT_ID)
    expect(headers['X-Correlation-Id']).toBeTruthy()
  })

  /*
   * 이 단언이 이 파일에서 가장 쉽게 깨지는 규칙이다. multipart의 Content-Type에는 브라우저가
   * 정하는 boundary가 들어가므로, 우리가 헤더를 써 넣는 순간 본문의 실제 경계와 어긋나
   * 서버가 파트를 하나도 못 찾는다. "다른 요청들처럼 Content-Type을 달자"는 정리가
   * 들어오면 여기서 막힌다.
   */
  it('Content-Type을 직접 달지 않는다 — boundary는 브라우저가 정한다', async () => {
    const fetchImpl = stubFetch(response(202, { analysisJobId: 'job-1' }))

    await uploadRecording(upload(), fetchImpl)

    const headers = fetchImpl.mock.calls[0][1]?.headers as Record<string, string>
    expect(Object.keys(headers).map((name) => name.toLowerCase())).not.toContain('content-type')
  })

  it('audio는 recording.wav 이름의 audio/wav 파일 파트다', async () => {
    const fetchImpl = stubFetch(response(202, { analysisJobId: 'job-1' }))

    await uploadRecording(upload(), fetchImpl)

    const { audio } = parts(fetchImpl.mock.calls[0][1])
    expect(audio).toBeInstanceOf(File)
    const file = audio as File
    expect(file.name).toBe('recording.wav')
    expect(file.type).toBe('audio/wav')
    expect(new Uint8Array(await readBytes(file))).toEqual(recording().wav)
  })

  it('meta는 파일이 아니라 평문 문자열이다 — 서버가 @RequestPart String으로 받는다', async () => {
    const fetchImpl = stubFetch(response(202, { analysisJobId: 'job-1' }))

    await uploadRecording(upload(), fetchImpl)

    const { meta } = parts(fetchImpl.mock.calls[0][1])
    expect(typeof meta).toBe('string')
    expect(JSON.parse(meta as string)).toEqual({ durationMs: 2_400, clientQuality: QUALITY })
  })

  it('요청마다 새 correlation id를 만든다 — 재시도는 별개의 요청이다', async () => {
    const fetchImpl = stubFetch(
      response(202, { analysisJobId: 'job-1' }),
      response(202, { analysisJobId: 'job-1' }),
    )

    await uploadRecording(upload(), fetchImpl)
    await uploadRecording(upload(), fetchImpl)

    const first = fetchImpl.mock.calls[0][1]?.headers as Record<string, string>
    const second = fetchImpl.mock.calls[1][1]?.headers as Record<string, string>
    expect(second['X-Correlation-Id']).not.toBe(first['X-Correlation-Id'])
    // 같은 시도의 재전송이므로 멱등 키는 그대로다
    expect(second['Idempotency-Key']).toBe(first['Idempotency-Key'])
  })
})

describe('빈 값 가드 — 네트워크를 타기 전에 끊는다', () => {
  it('세션 토큰이 비면 요청 없이 재시도 불가로 실패한다', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    const fetchImpl = stubFetch()

    await expect(uploadRecording(upload({ sessionToken: '   ' }), fetchImpl)).rejects.toMatchObject({
      name: 'UploadError',
      code: 'CLIENT_MISSING_sessionToken',
      retryable: false,
      // 사용자에게는 필드 이름 대신 다음 행동을 말한다 — 진단은 콘솔로 간다
      message: '녹음을 보낼 수 없어요. 처음부터 다시 시작해 주세요',
    })
    expect(fetchImpl).not.toHaveBeenCalled()
    expect(error).toHaveBeenCalled()
    error.mockRestore()
  })

  it('시도 식별자가 비어도 마찬가지다 — 멱등 키 없이 보내면 중복 접수가 생긴다', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    const fetchImpl = stubFetch()

    await expect(uploadRecording(upload({ attemptId: '' }), fetchImpl)).rejects.toMatchObject({
      code: 'CLIENT_MISSING_attemptId',
      retryable: false,
    })
    expect(fetchImpl).not.toHaveBeenCalled()
    error.mockRestore()
  })
})

describe('응답 해석', () => {
  it('202면 analysisJobId를 돌려준다', async () => {
    const fetchImpl = stubFetch(response(202, { analysisJobId: 'job-77' }))

    await expect(uploadRecording(upload(), fetchImpl)).resolves.toEqual({ analysisJobId: 'job-77' })
  })

  it('2xx인데 analysisJobId가 없으면 재시도 가능한 실패다 — 폴링할 id가 없다', async () => {
    const fetchImpl = stubFetch(response(202, { analysisJobId: '   ' }))

    await expect(uploadRecording(upload(), fetchImpl)).rejects.toMatchObject({
      name: 'UploadError',
      code: null,
      // 멱등 키가 중복 접수를 막으므로 같은 키로 다시 보내면 첫 접수의 id를 다시 받는다
      retryable: true,
    })
  })

  it('봉투가 있으면 code·message·retryable을 그대로 싣는다 (415)', async () => {
    const fetchImpl = stubFetch(
      response(415, {
        code: 'AUDIO_FORMAT_UNSUPPORTED',
        message: '지원하지 않는 오디오 형식이에요',
        retryable: false,
      }),
    )

    await expect(uploadRecording(upload(), fetchImpl)).rejects.toMatchObject({
      code: 'AUDIO_FORMAT_UNSUPPORTED',
      message: '지원하지 않는 오디오 형식이에요',
      // 같은 파일을 다시 보내도 결과가 같다 — 재시도 버튼을 그리면 안 된다
      retryable: false,
      retryAfterMs: null,
    })
  })

  it('429는 대기 시간을 함께 싣는다', async () => {
    const fetchImpl = stubFetch(
      response(429, { code: 'RATE_LIMITED', message: '잠시 후 다시 시도해 주세요', retryable: true, retryAfterMs: 3_000 }),
    )

    await expect(uploadRecording(upload(), fetchImpl)).rejects.toMatchObject({
      code: 'RATE_LIMITED',
      retryable: true,
      retryAfterMs: 3_000,
    })
  })

  it('봉투 없는 5xx는 상태 코드로 재시도 가능하다고 본다', async () => {
    const fetchImpl = stubFetch(response(503, undefined))

    await expect(uploadRecording(upload(), fetchImpl)).rejects.toMatchObject({
      code: null,
      retryable: true,
      message: '녹음을 보내지 못했어요 (HTTP 503)',
    })
  })

  it('봉투 없는 4xx는 다시 보내도 같다 — 재시도 불가', async () => {
    const fetchImpl = stubFetch(response(400, undefined))

    await expect(uploadRecording(upload(), fetchImpl)).rejects.toMatchObject({
      code: null,
      retryable: false,
    })
  })

  it('응답이 아예 오지 않으면 재시도 가능한 실패다', async () => {
    const fetchImpl = vi.fn<FetchLike>(async () => {
      throw new TypeError('Failed to fetch')
    })

    const error = await uploadRecording(upload(), fetchImpl).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(UploadError)
    expect(error).toMatchObject({
      code: null,
      retryable: true,
      message: '네트워크 오류로 녹음을 보내지 못했어요',
    })
  })
})
