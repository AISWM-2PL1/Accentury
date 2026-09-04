/**
 * [친구에게 공유하기]의 채널 선택 (KAN-30 1단계 — 웹 결선).
 *
 * 실행 환경마다 쓸 수 있는 공유 수단이 다르다. 그 갈래를 결과 화면이 아니라 여기서 소유하는
 * 이유는 화면이 `navigator`나 브리지를 직접 읽지 않게 하기 위해서다 — 결과 화면이 할 일은
 * 버튼을 그리고 눌렸다고 알리는 것까지다 (스토어 판별을 App이 대신 해 주는 것과 같은 규칙).
 *
 * ## 이 모듈이 카카오 SDK를 부르지 않는다
 *
 * 정식 경로는 네이티브 카카오 피드 템플릿(v2)이고, 그 호출은 **2단계에서 네이티브가** 한다.
 * 웹이 SDK를 들지 않는 이유는 앱 키·카톡 설치 여부·OS 공유 시트 폴백이 전부 네이티브 사정이기
 * 때문이다. 여기서는 서버가 준 카드 자산을 브리지 너머로 건네고 손을 뗀다.
 */

import { shareResult as shareViaBridge, type SharePayload } from '../bridge/bridge'
import type { ResultShare } from '../result/testResult'

/**
 * 실제로 공유가 나간 통로. 3단계 계측(`share_clicked` 이벤트의 `channel` 파라미터)이 이 값을
 * 읽는다 — 앱에서 카톡으로 나가는 비율과 브라우저에서 시트/복사로 새는 비율은 다른 사실이고,
 * 하나로 뭉뚱그리면 2단계 네이티브 공유가 실제로 쓰이는지를 볼 수 없다.
 *
 * `'unsupported'`도 사건이다 — 아무 데도 못 간 클릭이 얼마나 되는지가 폴백을 더 깔 근거가 된다.
 */
export type ShareChannel = 'bridge' | 'system' | 'clipboard' | 'unsupported'

/** 링크만 복사됐을 때의 안내. 시트가 열리지 않았으므로 무슨 일이 일어났는지 말해 줘야 한다 */
const COPIED_MESSAGE = '공유 링크를 복사했어요 · 붙여넣어서 보내 주세요'
/** 어느 통로도 없을 때. 비난 없는 카피 톤(ux-ui.md)을 지킨다 */
const UNSUPPORTED_MESSAGE = '이 환경에서는 공유를 지원하지 않아요'

/**
 * 쓸 수 있는 통로 중 가장 나은 것으로 공유한다. 어디로 나갔는지를 돌려준다.
 *
 * 순서는 "사용자가 덜 움직이는 쪽"이다.
 *
 * 1. **브리지** — 앱 안이면 네이티브가 카톡을 연다. 카톡 미설치 시 OS 공유 시트로 내려가는
 *    판정까지 네이티브가 하므로 웹이 그 뒤를 볼 일이 없다.
 * 2. **`navigator.share`** — 브라우저 단독 실행의 정식 경로다. 다만 **보안 컨텍스트(HTTPS)에서만**
 *    존재한다: 개발 WebView가 로드하는 `http://10.0.2.2:5173`에는 없다(`crypto.randomUUID`가
 *    같은 이유로 없었던 것과 같은 상황, 2026-08-18 에뮬레이터 실증). 그래서 없을 때가 정상이다.
 * 3. **클립보드 복사** — 시트가 없어도 링크는 건넬 수 있다. 붙여넣기 한 번을 사용자에게
 *    떠넘기는 셈이라 마지막에서 두 번째다.
 * 4. 그마저 없으면 안내만 남긴다.
 *
 * ## 절대 던지지 않는다
 *
 * 전 구간이 try/catch다. 공유는 결과 화면의 곁가지인데 그 실패가 렌더 트리를 타고 올라가면
 * 이미 받아 본 점수까지 사라진다 — 공유가 안 되는 것과 결과가 사라지는 것은 사용자에게
 * 전혀 다른 사건이다 (KAN-30 AC "공유 취소가 결과를 변경하지 않는다").
 *
 * 공유 payload에 점수를 싣지 않는다 (KAN-30 요구). 등급별 문구와 캠페인 URL은 서버가 준 값
 * 그대로다 — 수신자는 남의 결과를 보는 게 아니라 자기 테스트를 새로 응시한다.
 */
export function shareResult(share: ResultShare): ShareChannel {
  const payload: SharePayload = {
    imageUrl: share.imageUrl,
    text: share.text,
    webTestUrl: share.webTestUrl,
  }

  try {
    if (shareViaBridge(payload)) return 'bridge'
  } catch (error: unknown) {
    // 네이티브가 던진 예외는 여기서 끊고 웹 통로로 내려간다 — @JavascriptInterface 구현이
    // 무엇을 던지든 그건 이 화면이 감당할 일이 아니다.
    console.warn('[share] 브리지 공유가 실패해 웹 통로로 내려갑니다', error)
  }

  try {
    if (typeof navigator.share === 'function') {
      // 취소는 실패가 아니다 — 사용자가 공유 시트를 닫으면 reject가 오는데, 그걸 오류로
      // 다루면 정상 행동에 오류 로그가 쌓인다. 결과 화면은 그대로 두는 것이 맞다 (KAN-30 AC).
      navigator.share({ text: payload.text, url: payload.webTestUrl }).catch(() => {})
      return 'system'
    }
  } catch (error: unknown) {
    console.warn('[share] 공유 시트를 열지 못했습니다', error)
  }

  /*
   * 복사는 비동기라 이 함수가 끝난 뒤에 실패할 수 있다. 그래서 안내를 `then` 안에서 띄운다 —
   * 미리 "복사했어요"를 띄우고 실패하면 사용자는 있지도 않은 링크를 붙여넣는다. 반환값은
   * 낙관적으로 `'clipboard'`다: 계측이 세는 것은 "이 클릭이 어느 통로를 골랐는가"이고,
   * 그 통로가 끝까지 갔는지는 여기서 기다려 알아낼 수 있는 값이 아니다.
   */
  try {
    const writeText = navigator.clipboard?.writeText
    if (typeof writeText === 'function') {
      navigator.clipboard
        .writeText(payload.webTestUrl)
        .then(() => window.alert(COPIED_MESSAGE))
        .catch(() => warnUnsupported(payload))
      return 'clipboard'
    }
  } catch (error: unknown) {
    console.warn('[share] 클립보드에 접근하지 못했습니다', error)
  }

  warnUnsupported(payload)
  return 'unsupported'
}

/**
 * 아무 통로도 없었다 — 사용자에게 알리고 진단을 남긴다.
 *
 * `alert`를 쓰는 것은 결과 화면에 이 사건을 그릴 자리가 없기 때문이다. 화면에 문구를 붙이려면
 * 결과 화면이 공유 상태를 들고 있어야 하는데, 그건 곁가지 하나 때문에 화면의 상태 모델을
 * 늘리는 일이다 — 폴백의 폴백에 그만한 값을 치르지 않는다.
 */
function warnUnsupported(payload: SharePayload): void {
  console.warn('[share] 공유할 수 있는 통로가 없는 환경입니다', {
    text: payload.text,
    webTestUrl: payload.webTestUrl,
  })
  try {
    window.alert(UNSUPPORTED_MESSAGE)
  } catch {
    // `alert`가 막힌 환경(일부 WebView 설정)까지 왔다면 더 할 수 있는 일이 없다.
    // 진단은 위에서 이미 남겼으므로 조용히 끝낸다.
  }
}
