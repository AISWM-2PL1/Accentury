/**
 * 멱등 키 생성 (KAN-56 Stage 3에서 `submitVocabAnswer`로부터 옮겨 왔다).
 *
 * 비용이 발생하는 POST는 전부 `Idempotency-Key`를 싣는다 (§2.2) — 어휘 답안 제출, 그리고
 * 이제 녹음 업로드. 두 번째 사용처가 생긴 시점이 이 함수를 화면·도메인 밖으로 꺼낼 때다.
 * 어휘 제출 모듈이 계속 소유하면 오디오 계층이 "답안 제출"을 import하게 되고, 읽는 사람은
 * 둘 사이에 없는 의존을 상상하게 된다.
 *
 * 옛 경로(`progress/submitVocabAnswer`)는 이 이름을 그대로 재수출한다 — 기존 import를
 * 건드리지 않기 위해서다.
 */

/**
 * 새 멱등 키 (UUID v4 형태).
 *
 * `crypto.randomUUID()`를 그대로 쓰지 않는 이유: 그 함수는 **보안 컨텍스트(HTTPS·localhost)
 * 전용**이라, 개발 WebView가 로드하는 `http://10.0.2.2:5173`에는 존재하지 않는다 —
 * 에뮬레이터 실기 확인에서 TypeError로 실증됐다 (2026-08-18). `getRandomValues`는 컨텍스트
 * 구분 없이 제공되므로 그걸로 v4를 직접 만든다. 키는 서버가 형태를 해석하지 않는 불투명
 * 값이라(§3.5, 상한 길이만 검사) 어느 쪽 산출물이든 동등하다.
 */
export function newIdempotencyKey(): string {
  const c = globalThis.crypto
  if (typeof c.randomUUID === 'function') return c.randomUUID()
  const bytes = new Uint8Array(16)
  c.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40 // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80 // variant 10xx
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
