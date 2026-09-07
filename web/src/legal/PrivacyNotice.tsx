/**
 * 인트로 하단의 고지 한 줄 (KAN-177).
 *
 * 두 가지를 한 줄에 담는다 — 목소리를 어떻게 다루는지, 그리고 자세한 것은 어디서 볼 수 있는지.
 * 이 자리가 [시작하기] 바로 아래인 이유는 그 버튼이 곧 마이크 권한 요청이기 때문이다:
 * 권한 대화상자가 뜨기 직전에 읽히는 문장이라야 고지 노릇을 한다. 화면 위쪽에 두면 사용자는
 * 히어로와 숫자 카드를 지나오며 이미 잊는다.
 *
 * 앱에 설정 화면이 없어서 방침으로 가는 길이 여기 하나뿐이다 — Google Play는 스토어 등록
 * 정보만이 아니라 **앱 안에서도** 방침을 볼 수 있기를 요구한다 (KAN-177).
 *
 * 웹 SPA 한 곳에 두면 안드로이드·iOS WebView와 스탠드얼론 웹이 함께 해결된다. 세 플랫폼에
 * 같은 줄을 세 번 적지 않는 것이 이 화면을 웹으로 만든 이유이기도 하다.
 */

import type { MouseEvent } from 'react'
import { openExternalUrl } from '../bridge/bridge'
import { privacyPolicyUrl } from './privacyPolicy'

export function PrivacyNotice() {
  const url = privacyPolicyUrl()

  /**
   * 앱 안에서는 네이티브가 연다 (Custom Tabs·SFSafariViewController). 브리지가 받아 주면
   * 링크의 기본 동작을 막는다 — 막지 않으면 WebView가 같은 URL로 이동을 시도해서, 시트가
   * 덮이는 동시에 그 아래 인트로가 정책 문서로 바뀌거나 allowlist에 걸려 오류 화면이 뜬다.
   *
   * 브리지가 없으면(브라우저 단독 실행) 아무것도 하지 않고 `<a>`의 기본 동작에 맡긴다.
   */
  function handleClick(event: MouseEvent<HTMLAnchorElement>) {
    if (openExternalUrl(url)) event.preventDefault()
  }

  return (
    <p className="type-caption privacy-notice">
      녹음한 음성은 분석이 끝나면 바로 지워요.{' '}
      {/*
        버튼이 아니라 `<a>`인 이유는 `MicBlockedScreen`의 스토어 링크와 같다 — 바깥으로
        나가는 것은 이동이라 링크의 기본 동작(길게 눌러 복사, 스크린 리더의 "링크" 안내)이
        전부 의미를 갖는다.

        `target="_blank"`는 브라우저 단독 실행을 위한 것이다: 같은 탭에서 열면 응시하려던
        사람이 정책 문서에 남고 인트로로는 뒤로가기로만 돌아온다. 앱 안에서는 이 속성이
        아무 일도 하지 않지만(팝업 미지원) 그 경로는 위 `handleClick`이 먼저 가로챈다.
      */}
      <a
        className="text-link"
        href={url}
        target="_blank"
        rel="noopener noreferrer"
        onClick={handleClick}
      >
        개인정보처리방침
      </a>
    </p>
  )
}
