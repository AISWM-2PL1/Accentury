import { describe, expect, it } from 'vitest'
import { WAV_HEADER_BYTES, encodeWav16kMono } from './wavEncoder'
import { TARGET_SAMPLE_RATE } from './pcm'

function tagAt(bytes: Uint8Array, offset: number): string {
  return String.fromCharCode(...bytes.slice(offset, offset + 4))
}

function viewOf(bytes: Uint8Array): DataView {
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
}

describe('encodeWav16kMono — 헤더', () => {
  const pcm = new Int16Array(TARGET_SAMPLE_RATE)
  const bytes = encodeWav16kMono(pcm)
  const view = viewOf(bytes)
  const dataBytes = pcm.length * 2

  it('전체 길이가 헤더 44바이트 + PCM이다', () => {
    expect(bytes.length).toBe(WAV_HEADER_BYTES + dataBytes)
  })

  it('RIFF/WAVE/fmt/data 태그가 규격 위치에 있다', () => {
    expect(tagAt(bytes, 0)).toBe('RIFF')
    expect(tagAt(bytes, 8)).toBe('WAVE')
    expect(tagAt(bytes, 12)).toBe('fmt ')
    expect(tagAt(bytes, 36)).toBe('data')
  })

  it('RIFF 크기가 36 + data 크기다 (서버가 파일 크기와 대조한다)', () => {
    expect(view.getUint32(4, true)).toBe(36 + dataBytes)
    expect(view.getUint32(4, true)).toBe(bytes.length - 8)
  })

  it('fmt 청크가 16kHz 모노 16bit 비압축 PCM을 선언한다', () => {
    expect(view.getUint32(16, true)).toBe(16) // fmt 본문 길이
    expect(view.getUint16(20, true)).toBe(1) // PCM
    expect(view.getUint16(22, true)).toBe(1) // 채널
    expect(view.getUint32(24, true)).toBe(TARGET_SAMPLE_RATE)
    expect(view.getUint16(34, true)).toBe(16) // bits per sample
  })

  it('byteRate·blockAlign이 다른 필드에서 유도한 값과 일치한다', () => {
    const blockAlign = view.getUint16(32, true)
    expect(blockAlign).toBe(2)
    expect(view.getUint32(28, true)).toBe(blockAlign * TARGET_SAMPLE_RATE)
    expect(view.getUint32(28, true)).toBe(32000)
  })

  it('data 크기가 실제 PCM 바이트 수와 같다', () => {
    expect(view.getUint32(40, true)).toBe(dataBytes)
  })
})

describe('encodeWav16kMono — 본문', () => {
  it('헤더 뒤에 PCM을 리틀엔디언으로 담고 값이 보존된다', () => {
    const pcm = Int16Array.from([0, 1000, -1000, 32767, -32768])
    const bytes = encodeWav16kMono(pcm)
    const view = viewOf(bytes)

    for (let i = 0; i < pcm.length; i++) {
      expect(view.getInt16(WAV_HEADER_BYTES + i * 2, true)).toBe(pcm[i])
    }
    // 리틀엔디언이면 1000 = 0x03E8이 [0xE8, 0x03] 순서로 놓인다.
    expect(bytes[WAV_HEADER_BYTES + 2]).toBe(0xe8)
    expect(bytes[WAV_HEADER_BYTES + 3]).toBe(0x03)
  })

  it('빈 PCM도 유효한 44바이트 헤더를 만든다', () => {
    const bytes = encodeWav16kMono(new Int16Array(0))
    const view = viewOf(bytes)

    expect(bytes.length).toBe(WAV_HEADER_BYTES)
    expect(view.getUint32(4, true)).toBe(36)
    expect(view.getUint32(40, true)).toBe(0)
  })
})
