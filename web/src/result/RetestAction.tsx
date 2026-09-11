/**
 * [다시 테스트하기] 한 벌 — 버튼과 그 아래 안내 한두 줄 (KAN-34).
 *
 * ## 왜 결과 화면 밖으로 나왔나 (KAN-191)
 *
 * 처음에는 결과 화면 안의 지역 컴포넌트였다. 같은 벌을 쓰는 자리가 만료 화면과 하단 둘인데
 * 둘 다 그 화면 안이었기 때문이다. 분석 대기 화면의 막다른 상태(FAILED·손댈 문항 없음)에도
 * 같은 출구를 주기로 하면서 쓰는 화면이 둘이 됐다.
 *
 * 한쪽이 자기 벌을 따로 그리면 잠금·실패 문구·429 대기 안내 중 하나가 그 화면에서만 빠진다.
 * 더블탭 방지가 클라이언트 몫이라(KAN-107) 한 자리라도 새면 방어가 아니게 된다 — 결과 화면
 * 안에서 두 자리를 한 컴포넌트로 묶었던 이유가 그대로 화면 둘에도 적용된다.
 *
 * ## 왜 `ui/`가 아니라 `result/`인가
 *
 * 이 벌이 그리는 값은 전부 [useRetest]가 만들고 그 훅이 여기 있다. `ui/`는 도메인을 모르는
 * 범용 조각(Button·StatusBlock)의 자리라, 올리면 그쪽이 재응시라는 개념을 알게 된다.
 * 대기 화면이 `progress/`의 문항 타입을 가져다 쓰는 것과 같은 방향이다.
 */

import { readAdConsent } from '../bridge/bridge'
import { Button, type ButtonVariant } from '../ui'
import type { RetestControl } from './useRetest'

/**
 * 버튼 라벨 (KAN-196). 광고를 아는 앱에서는 [다시 테스트하기]가 곧 보상형 광고라
 * (`startRetest` 주석) 누르기 전에 그 사실을 라벨이 말해야 한다 — 광고가 갑자기 뜨면 사용자는
 * 잘못 눌렀다고 여겨 닫고, 그러면 `AD_DISMISSED`로 되돌아와 다시 눌러야 한다.
 *
 * 판정 근거는 `readAdConsent() !== null`이다. "광고를 아는 앱"을 가르는 신호가 그것뿐이다 —
 * 동의 값이 무엇이든(허용·일반·아직 안 물음) 광고는 나오므로 값이 아니라 **유무**를 본다.
 * 웹 단독 실행(KAN-197 범위 밖)과 이 메서드를 모르는 구버전 앱은 null이라 예전 라벨 그대로다:
 * 그 실행에서는 광고가 뜨지 않으니 "광고 보고"라고 적으면 거짓말이 된다.
 *
 * `showInterstitialAd`나 `startRetest`의 유무로 가르지 않는 이유: 전자는 전면 광고의 신호지
 * 보상형의 신호가 아니고, 후자는 광고 이전(KAN-34)부터 있던 메서드다.
 */
function retestLabel(): string {
  return readAdConsent() !== null ? '광고 보고 다시 테스트하기' : '다시 테스트하기'
}

export interface RetestActionProps {
  /** 버튼이 그릴 상태 한 덩이 — 잠금·진행·실패 문구·대기 초가 여기 다 있다 */
  retest: RetestControl
  /**
   * 이 자리에서 재응시가 주버튼이 아닐 때 내려 쓴다. 화면당 주버튼은 하나다
   * (ux-ui.md Hick's law).
   */
  variant?: ButtonVariant
}

export function RetestAction({ retest, variant }: RetestActionProps) {
  const { onRetest, disabled, pending, message, retryAfterSec } = retest

  return (
    <>
      <Button variant={variant} onClick={onRetest} disabled={disabled}>
        {/*
          성공하면 회신이 아니라 페이지 교체가 온다. 그 사이 create 왕복 동안 화면은 아무것도
          모르므로, 할 수 있는 말은 "받았고 진행 중"까지다 — 몇 초 걸리는지도 알 수 없다.
        */}
        {pending ? '준비 중…' : retestLabel()}
      </Button>

      {message !== null && (
        /*
          네이티브가 준 문구를 그대로 그린다 — 갈래별 카피를 웹이 따로 들면 같은 판정에 두
          벌이 생겨 앱과 웹이 다른 말을 하게 된다 (RetestFailure 계약).

          role="alert"인 이유는 StatusBlock의 오류 문구와 같다: 이미 떠 있는 화면에서 나중에
          나타나는 실패라, 스스로 읽어 주지 않으면 버튼이 왜 죽었는지 알 길이 없다.
        */
        <p className="type-caption result-retest__message" role="alert">
          {message}
        </p>
      )}

      {retryAfterSec > 0 && (
        /*
          429 대기 안내 (§2.5). live 영역에 두지 않는다 — 1초마다 바뀌는 값이라 읽어 주면
          같은 문장을 매초 반복해 위 실패 문구를 덮는다.
        */
        <p className="type-caption result-retest__wait">{retryAfterSec}초 후 다시 시도할 수 있어요</p>
      )}
    </>
  )
}
