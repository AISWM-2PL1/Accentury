/**
 * 개인정보처리방침 링크 (KAN-177).
 *
 * URL을 여기 한 곳에만 둔다 (KAN-177 AC). 인트로 말고도 스토어 등록 정보(KAN-174·KAN-175)와
 * 결과 화면이 같은 문서를 가리키게 되는데, 주소가 두 군데 하드코딩되면 호스팅이 바뀌는 날
 * 한쪽만 고쳐져 죽은 링크가 남는다 — `audio/storeLink.ts`가 스토어 URL을 한 곳에 모은 것과
 * 같은 이유다.
 *
 * ## `.html`이 붙어 있는 이유 (KAN-133, 2026-09-04 실측)
 *
 * 정책 문서는 SPA의 라우트가 아니라 S3에 따로 올리는 정적 파일이다
 * (`infra/privacy/privacy.html`). CloudFront의 SPA 재작성 함수는 **경로의 마지막 조각에 점이
 * 없으면 `/index.html`로 돌리므로**, 확장자를 빼면 `/privacy`가 200을 주면서 정책 문서가
 * 아니라 앱 화면을 띄운다. 오류가 아니라 엉뚱한 페이지가 뜨는 실패라 눈으로 열어 보기 전에는
 * 아무도 모른다 — 확장자는 취향이 아니라 계약이다.
 */

/**
 * 기본값은 prod 문서다. 스테이징 웹은 `VITE_PRIVACY_POLICY_URL`로
 * `https://staging.accentury.app/privacy.html`을 덮어쓴다 (`audio/storeLink.ts`와 같은 규칙).
 */
export const DEFAULT_PRIVACY_POLICY_URL = 'https://accentury.app/privacy.html'

/** 이 빌드가 열 정책 문서 주소. 빈 값(`VITE_PRIVACY_POLICY_URL=`)은 미설정과 같이 본다 */
export function privacyPolicyUrl(): string {
  const override = import.meta.env.VITE_PRIVACY_POLICY_URL as string | undefined
  return override !== undefined && override.trim() !== '' ? override : DEFAULT_PRIVACY_POLICY_URL
}
