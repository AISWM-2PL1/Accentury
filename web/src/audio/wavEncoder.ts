/**
 * 16kHz 모노 16-bit WAV 인코딩 (KAN-56 Stage 1).
 *
 * 네이티브 앱의 `WavWriter.toWavBytes`와 **같은 바이트를 만든다.** 서버의 `WavAudio.parse`가
 * 헤더 필드끼리의 정합까지 검사하기 때문에(RIFF 크기 = 전체 − 8, byteRate = blockAlign ×
 * 레이트, data 크기가 프레임으로 나누어떨어질 것) 한 필드라도 어긋나면 415로 튕긴다.
 * 웹과 앱이 같은 헤더를 쓰면 업로드 실패를 한쪽에서만 겪는 일이 없다.
 *
 * WAV 헤더는 값 몇 개를 정해진 자리에 리틀엔디언으로 늘어놓은 44바이트가 전부다. 압축도
 * 인코딩도 없어서 뒤이어 붙는 PCM은 메모리에 있던 그대로다.
 */

import { TARGET_SAMPLE_RATE } from './pcm'

/** 표준 PCM WAV 헤더 길이. data 청크 본문은 이 뒤에서 시작한다 */
export const WAV_HEADER_BYTES = 44

const CHANNELS = 1
const BITS_PER_SAMPLE = 16
/** 한 프레임(= 모노 16비트에서 샘플 하나)의 바이트 수. 헤더의 blockAlign 필드값이다 */
const BLOCK_ALIGN = (CHANNELS * BITS_PER_SAMPLE) / 8

/**
 * 16-bit PCM을 16kHz 모노 WAV 바이트로 감싼다.
 *
 * 빈 PCM도 44바이트짜리 유효한 헤더를 만든다 — 여기서 던지면 "왜 실패했는지"가 인코더
 * 예외로 흐려진다. 길이 판정은 {@link judge}가 durationMs를 보고 하는 쪽이 정본이다.
 */
export function encodeWav16kMono(pcm: Int16Array): Uint8Array {
  const dataBytes = pcm.length * BLOCK_ALIGN
  const bytes = new Uint8Array(WAV_HEADER_BYTES + dataBytes)
  const view = new DataView(bytes.buffer)

  writeTag(bytes, 0, 'RIFF')
  // RIFF 크기는 자기 자신(8바이트)을 뺀 나머지 전부다. 서버가 실제 파일 크기와 대조한다.
  view.setUint32(4, WAV_HEADER_BYTES - 8 + dataBytes, true)
  writeTag(bytes, 8, 'WAVE')

  writeTag(bytes, 12, 'fmt ')
  view.setUint32(16, 16, true) // fmt 청크 본문 길이 (PCM은 16바이트 고정)
  view.setUint16(20, 1, true) // 1 = 비압축 PCM. 서버가 이 값만 통과시킨다
  view.setUint16(22, CHANNELS, true)
  view.setUint32(24, TARGET_SAMPLE_RATE, true)
  view.setUint32(28, TARGET_SAMPLE_RATE * BLOCK_ALIGN, true) // byteRate
  view.setUint16(32, BLOCK_ALIGN, true)
  view.setUint16(34, BITS_PER_SAMPLE, true)

  writeTag(bytes, 36, 'data')
  view.setUint32(40, dataBytes, true)
  for (let i = 0; i < pcm.length; i++) {
    view.setInt16(WAV_HEADER_BYTES + i * BLOCK_ALIGN, pcm[i], true)
  }
  return bytes
}

/** 4글자 ASCII 태그를 그대로 박는다. WAV 태그는 전부 ASCII 4바이트다 */
function writeTag(bytes: Uint8Array, offset: number, tag: string): void {
  for (let i = 0; i < tag.length; i++) {
    bytes[offset + i] = tag.charCodeAt(i)
  }
}
