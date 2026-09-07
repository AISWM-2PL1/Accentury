import { afterEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_PRIVACY_POLICY_URL, privacyPolicyUrl } from './privacyPolicy'

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('privacyPolicyUrl — 정책 문서 주소 (KAN-177)', () => {
  /*
   * 이 한 줄이 이 파일의 존재 이유다. CloudFront SPA 재작성 함수가 마지막 경로 조각에 점이
   * 없으면 `/index.html`로 돌리므로, 확장자가 빠진 `/privacy`는 **200을 주면서** 정책 문서가
   * 아니라 앱 화면을 띄운다 (KAN-133, 2026-09-04 staging 실측). 링크가 죽어도 아무 데도
   * 오류가 남지 않아서, 이 검사가 없으면 심사관이 먼저 발견하게 된다.
   */
  it('기본값은 확장자가 붙은 prod 문서다', () => {
    expect(DEFAULT_PRIVACY_POLICY_URL).toBe('https://accentury.app/privacy.html')
    expect(privacyPolicyUrl()).toBe(DEFAULT_PRIVACY_POLICY_URL)
  })

  it('스테이징은 환경 변수로 덮어쓴다', () => {
    vi.stubEnv('VITE_PRIVACY_POLICY_URL', 'https://staging.accentury.app/privacy.html')

    expect(privacyPolicyUrl()).toBe('https://staging.accentury.app/privacy.html')
  })

  it('빈 값은 미설정과 같이 본다 — 링크를 빈 곳으로 보내지 않는다', () => {
    vi.stubEnv('VITE_PRIVACY_POLICY_URL', '   ')

    expect(privacyPolicyUrl()).toBe(DEFAULT_PRIVACY_POLICY_URL)
  })
})
