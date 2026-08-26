/**
 * 공유 유입 계측 코드(`?c=`) 읽기 (KAN-31 Stage 1).
 *
 * 공유 링크는 `https://accentury.app/t?c=kko_share` 꼴이다 (백엔드 `application.yml`의
 * `web-test-url`). 결과 화면의 [친구에게 공유하기]가 서버에서 받은 이 URL을 그대로 넘기므로,
 * 링크를 타고 들어온 사람의 첫 세션에 같은 코드가 실려야 유입 경로가 이어진다 (§3.1).
 *
 * 개인 식별 정보가 아니다 — 서버도 익명 집계에만 쓴다. 그래서 URL에 남아 있어도 무해하고,
 * 화면을 옮길 때 다른 진입 파라미터와 함께 보존한다 (`navigation/entryUrl.ts`).
 */

/**
 * 서버가 받아 주는 형태 (`CreateSessionRequest.campaignToken`의 `@Pattern`과 같은 규칙).
 * 여기서 같은 검사를 하는 이유는 400을 피하기 위해서다 — 아래 [sanitizeCampaignToken] 참고.
 */
const CAMPAIGN_TOKEN_PATTERN = /^[A-Za-z0-9._-]{1,64}$/

/** 진입 쿼리의 `c` 값. 없거나 비어 있으면 null */
export function readCampaignToken(search: string): string | null {
  const raw = new URLSearchParams(search).get('c')
  if (raw === null || raw.trim() === '') return null
  return raw
}

/**
 * 서버 계약에 맞는 값만 통과시킨다. 어긋나면 null이고, 호출자는 **필드를 아예 빼고** 보낸다.
 *
 * 계약 위반을 400으로 돌려받지 않는 것이 요점이다. 공유 링크는 메신저·SNS를 여러 번 거치며
 * 잘리거나 트래킹 파라미터가 덧붙는 경로라, 코드가 망가진 채로 도착하는 일이 실제로 생긴다.
 * 그때 400으로 막으면 계측값 하나 때문에 사용자가 테스트를 시작하지 못한다 — 계측은 실패해도
 * 되는 일이고 응시는 아니다.
 */
export function sanitizeCampaignToken(raw: string | null | undefined): string | null {
  if (typeof raw !== 'string') return null
  return CAMPAIGN_TOKEN_PATTERN.test(raw) ? raw : null
}
