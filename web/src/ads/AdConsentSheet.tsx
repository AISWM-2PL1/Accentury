/**
 * 맞춤형 광고 동의 시트 (KAN-196). 인트로 위를 덮는 모달이다.
 *
 * ## 닫는 길이 선택뿐이다
 *
 * X도 바깥 탭도 없다. 선택하지 않고 닫을 수 있으면 상태가 `unknown`으로 남고, 그러면 다음
 * 실행에 또 묻는다 — 그것이 맞는 동작이긴 하지만, 닫을 수 있는 시트가 매번 다시 뜨는 것은
 * 사용자에게 고장으로 읽힌다. 고르는 것 자체가 두 탭 중 하나라 부담이 크지 않고, 어느 쪽을
 * 골라도 나중에 인트로 하단 「맞춤형 광고 설정」에서 바꿀 수 있다.
 *
 * 그래서 Escape도 듣지 않는다. 키보드가 있는 환경(데스크톱 브라우저)은 이 시트가 뜨는 실행이
 * 아니다 — 시트는 광고를 아는 앱 안에서만 뜬다 (`useAdConsent` null 규칙).
 *
 * ## 접근성
 *
 * `role="dialog"` + `aria-modal="true"`로 뒤의 인트로를 보조기기에서 떼어 놓고, 제목을
 * `aria-labelledby`로 잇는다. 첫 버튼에 `autoFocus`를 줘서 시트가 뜨는 순간 초점이 시트 안에
 * 있게 한다 — 이 레포에 모달이 처음이라 따를 패턴이 없고, 포커스 트랩까지 세우는 것은 탭
 * 키가 없는 모바일 WebView에 비해 과하다. 뒤의 [시작하기]는 배경 막이 화면 전체를 덮어 손이
 * 닿지 않는다 (`.ad-consent-sheet`가 `position: fixed; inset: 0`).
 *
 * 제목이 h1이 아니라 h2인 이유: 인트로의 h1(「사투리 좀 치나?」)이 화면 이름이고, 이 시트는
 * 그 화면 위에 덮인 것이다 — h1이 둘이면 보조기기에 화면이 둘로 들린다.
 *
 * ## 스타일이 시트인 이유
 *
 * 화면 가운데 상자가 아니라 아래에서 올라오는 시트로 그린다 — 네이티브 권한 대화상자와
 * 나란히 놓였을 때 OS 것과 우리 것이 구분돼야 하고(OS 대화상자는 가운데), 한 손 엄지가 닿는
 * 자리에 버튼이 오는 것이 모바일 첫 화면에서 맞다. 종이 질감(테두리·오프셋 그림자)은
 * `.card`와 같은 토큰을 쓴다.
 */

import { PrivacyPolicyLink } from '../legal/PrivacyPolicyLink'
import { Button } from '../ui'
import type { AdConsent, AdConsentChoice } from '../bridge/bridge'
import {
  AD_CONSENT_ALLOW,
  AD_CONSENT_CHANGE_HINT,
  AD_CONSENT_CURRENT,
  AD_CONSENT_DENY,
  AD_CONSENT_DETAIL_LEAD,
  AD_CONSENT_DETAIL_TAIL,
  AD_CONSENT_EFFECT,
  AD_CONSENT_TITLE,
  AD_CONSENT_WHY,
} from './adConsentText'

export interface AdConsentSheetProps {
  /**
   * 지금 상태. `unknown`이면 첫 실행이라 상태 줄이 없고, 그 밖이면 「맞춤형 광고 설정」으로
   * 다시 연 것이라 지금 무엇을 골라 둔 상태인지 한 줄 알린다 — 바꾸러 온 사람이 지금 값을
   * 모르면 두 버튼 중 어느 쪽이 "바꾸는" 쪽인지 알 수 없다.
   */
  current: AdConsent
  /** 사용자가 골랐다. 저장과 닫기는 호출자 몫이다 (`useAdConsent.choose`) */
  onChoose: (state: AdConsentChoice) => void
}

const TITLE_ID = 'ad-consent-title'

export function AdConsentSheet({ current, onChoose }: AdConsentSheetProps) {
  return (
    <div className="ad-consent-sheet">
      <div className="ad-consent-panel" role="dialog" aria-modal="true" aria-labelledby={TITLE_ID}>
        <h2 id={TITLE_ID} className="type-title-sm ad-consent-title">
          {AD_CONSENT_TITLE}
        </h2>
        <div className="type-body-sm ad-consent-body">
          {current !== 'unknown' && <p className="ad-consent-current">{AD_CONSENT_CURRENT[current]}</p>}
          <p>
            {AD_CONSENT_WHY} {AD_CONSENT_EFFECT}
          </p>
          <p>
            {AD_CONSENT_CHANGE_HINT} {AD_CONSENT_DETAIL_LEAD} <PrivacyPolicyLink />
            {AD_CONSENT_DETAIL_TAIL}
          </p>
        </div>
        <div className="ad-consent-actions">
          {/*
            허용이 주버튼이다. 둘 다 정당한 선택이지만 화면당 주버튼은 하나이고(ux-ui.md Hick's
            law), 초점이 먼저 가는 자리이기도 하다. 거부가 보조 무게라고 해서 눌리기 어렵지는
            않다 — 같은 높이의 온전한 버튼이다.
          */}
          <Button autoFocus onClick={() => onChoose('granted')}>
            {AD_CONSENT_ALLOW}
          </Button>
          <Button variant="secondary" onClick={() => onChoose('denied')}>
            {AD_CONSENT_DENY}
          </Button>
        </div>
      </div>
    </div>
  )
}
