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

import { AD_CONSENT_SETTINGS_LINK } from '../ads/adConsentText'
import { PrivacyPolicyLink } from './PrivacyPolicyLink'

export interface PrivacyNoticeProps {
  /**
   * 「맞춤형 광고 설정」을 눌렀다 (KAN-196). **없으면 그 링크를 그리지 않는다** — 광고 동의라는
   * 개념이 없는 실행(웹 단독·광고를 모르는 앱, `readAdConsent() === null`)에서 눌러도 아무
   * 일 없는 링크를 두지 않는다. 판정은 인트로가 하고 이 줄은 받은 것만 그린다.
   */
  onAdConsentSettings?: () => void
}

export function PrivacyNotice({ onAdConsentSettings }: PrivacyNoticeProps = {}) {
  return (
    <p className="type-caption privacy-notice">
      녹음한 음성은 분석이 끝나면 바로 지워요.
      {/*
        줄을 여기서 끊는다. 문장과 링크를 한 줄에 흘려 담으면 폭이 모자라 문장이 먼저 두 줄로
        갈리고(캡션 13px · 콘텐츠 폭 312dp에 문장만 약 256dp), 링크가 앞 줄 꼬리에 붙어 어디까지가
        고지이고 어디부터가 누를 것인지 흐려진다. 고지 한 문장 · 링크 한 줄로 나눈다.

        접근성 글자 크기를 키우면 문장이 다시 두 줄로 넘어가는데, 그때도 `keep-all`이 낱말을
        지켜 준다 (`.privacy-notice`).
      */}
      <br />
      {/*
        링크의 요소 선택(`<a>`)과 여는 규칙(앱 안은 네이티브, 밖은 새 탭)은 `PrivacyPolicyLink`가
        갖는다 — 광고 동의 시트(KAN-196)도 같은 링크를 쓴다.
      */}
      <PrivacyPolicyLink />
      {onAdConsentSettings !== undefined && (
        <>
          {/*
            방침 링크와 같은 줄에 가운뎃점으로 잇는다 (KAN-196). 둘 다 "내 정보가 어떻게
            다뤄지는지"로 가는 문이라 한 줄에 서는 것이 맞고, 셋째 줄을 만들면 하단이 주버튼
            아래 세 줄이 되어 시안의 배치가 흐려진다. 폭은 캡션 13px에 두 링크 합쳐 열넉 자라
            312dp 안에 넉넉히 든다.

            `<a>`가 아니라 `<button>`인 이유는 방침 링크와 반대다 — 저쪽은 바깥으로 나가는
            이동이고, 이쪽은 앱 안에서 시트를 여는 동작이다. `href`가 없는 링크는 길게 눌러도
            복사할 것이 없고 스크린 리더가 "링크"라고 읽어 주면 사실과 어긋난다.
          */}
          {' · '}
          <button type="button" className="text-link ad-consent-settings" onClick={onAdConsentSettings}>
            {AD_CONSENT_SETTINGS_LINK}
          </button>
        </>
      )}
    </p>
  )
}
