/**
 * 개인정보처리방침으로 가는 링크 하나 (KAN-177, KAN-196에서 분리).
 *
 * 처음에는 `PrivacyNotice` 안의 `<a>`였다. 맞춤형 광고 동의 시트(`ads/AdConsentSheet`)도
 * 같은 문서로 보내야 해서 쓰는 자리가 둘이 됐고, 한쪽이 자기 `<a>`를 따로 그리면 "앱 안에서는
 * 네이티브가 연다"는 갈래가 그 자리에서만 빠진다 — 눌렀는데 아무 일 없는 링크가 된다
 * (`openExternalUrl` 주석). 여는 규칙을 한 곳에 둔다.
 *
 * 버튼이 아니라 `<a>`인 이유는 `MicBlockedScreen`의 스토어 링크와 같다 — 바깥으로 나가는
 * 것은 이동이라 링크의 기본 동작(길게 눌러 복사, 스크린 리더의 "링크" 안내)이 전부 의미를
 * 갖는다.
 *
 * `target="_blank"`는 브라우저 단독 실행을 위한 것이다: 같은 탭에서 열면 응시하려던 사람이
 * 정책 문서에 남고 인트로로는 뒤로가기로만 돌아온다. 앱 안에서는 이 속성이 아무 일도 하지
 * 않지만(팝업 미지원) 그 경로는 아래 `handleClick`이 먼저 가로챈다.
 */

import type { MouseEvent, ReactNode } from 'react'
import { openExternalUrl } from '../bridge/bridge'
import { privacyPolicyUrl } from './privacyPolicy'

export interface PrivacyPolicyLinkProps {
  /** 링크 글자. 기본은 문서 이름 그대로다 */
  children?: ReactNode
}

export function PrivacyPolicyLink({ children = '개인정보처리방침' }: PrivacyPolicyLinkProps) {
  const url = privacyPolicyUrl()

  /**
   * 앱 안에서는 네이티브가 연다 (Custom Tabs·SFSafariViewController). 브리지가 받아 주면
   * 링크의 기본 동작을 막는다 — 막지 않으면 WebView가 같은 URL로 이동을 시도해서, 시트가
   * 덮이는 동시에 그 아래 화면이 정책 문서로 바뀌거나 allowlist에 걸려 오류 화면이 뜬다.
   *
   * 브리지가 없으면(브라우저 단독 실행) 아무것도 하지 않고 `<a>`의 기본 동작에 맡긴다.
   */
  function handleClick(event: MouseEvent<HTMLAnchorElement>) {
    if (openExternalUrl(url)) event.preventDefault()
  }

  return (
    <a className="text-link" href={url} target="_blank" rel="noopener noreferrer" onClick={handleClick}>
      {children}
    </a>
  )
}
