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

  /*
   * 환경 변수는 배포 경로에 없다 - `web-deploy.yml`이 넘기는 VITE 값은 GA4 측정 ID 하나뿐이고
   * staging 빌드도 위 prod 문서를 연다(방침은 법적 고지라 정본이 하나다). 이 손잡이는 게시 전
   * 본문을 브라우저에서 확인할 때 쓰는 로컬 전용이다 - 앱 안에서는 네이티브 allowlist가 prod
   * 호스트만 허용하므로 다른 주소를 넣어도 열리지 않는다.
   */
  it('로컬 확인용으로 환경 변수가 덮어쓴다', () => {
    vi.stubEnv('VITE_PRIVACY_POLICY_URL', 'http://localhost:8788/privacy.html')

    expect(privacyPolicyUrl()).toBe('http://localhost:8788/privacy.html')
  })

  it('빈 값은 미설정과 같이 본다 — 링크를 빈 곳으로 보내지 않는다', () => {
    vi.stubEnv('VITE_PRIVACY_POLICY_URL', '   ')

    expect(privacyPolicyUrl()).toBe(DEFAULT_PRIVACY_POLICY_URL)
  })
})
