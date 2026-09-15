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
 * **환경과 무관하게 언제나 prod 문서다.** staging 빌드도 이 주소를 연다.
 *
 * 방침은 법적 고지이고 정본이 하나여야 한다 - staging 웹을 쓰는 사람에게도 실제로 적용되는
 * 것은 prod에 게시된 그 문서다. 환경별로 갈라 두면 "화면이 가리키는 문서"와 "실제로 고지된
 * 문서"가 어긋난다. 빌드 산출물이 환경을 몰라야 한다는 원칙(KAN-127)과도 같은 방향이고,
 * 그래서 배포 워크플로는 이 값을 넘기지 않는다 (`.github/workflows/web-deploy.yml`에서
 * 환경을 아는 값은 GA4 측정 ID 하나뿐이다).
 *
 * `VITE_PRIVACY_POLICY_URL`은 그 원칙의 예외가 아니라 **로컬 확인용 손잡이**다. 게시 전
 * 본문을 앱 화면에서 보고 싶을 때 staging 문서를 잠깐 가리키는 식으로 쓴다. 네이티브
 * allowlist가 prod 호스트만 허용하므로(`EXTERNAL_LINK_HOSTS` / `externalLinkHosts`)
 * **앱 안에서는 다른 호스트를 넣어도 열리지 않는다** - 브라우저 단독 실행에서만 듣는다.
 */
export const DEFAULT_PRIVACY_POLICY_URL = 'https://accentury.app/privacy.html'

/** 이 빌드가 열 정책 문서 주소. 빈 값(`VITE_PRIVACY_POLICY_URL=`)은 미설정과 같이 본다 */
export function privacyPolicyUrl(): string {
  const override = import.meta.env.VITE_PRIVACY_POLICY_URL as string | undefined
  return override !== undefined && override.trim() !== '' ? override : DEFAULT_PRIVACY_POLICY_URL
}
