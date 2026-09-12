# 웹 광고 — Google AdSense (KAN-197)

브라우저 단독 실행(`https://accentury.app`)의 광고다. 앱 광고는 AdMob이고 정본은
[`ads-admob.md`](ads-admob.md) — 이 문서는 **웹 몫**을 맡는다. 사업자가 갈린 이유가 하나뿐이라
말이 짧다: **AdMob은 웹을 지원하지 않는다.**

1단계(2026-09-13)에서 정한 것은 방침·동의 문안·이 문서까지다. 태그 설치·동의 저장·요청 플래그는
2~4단계에 배선한다. 아래 표에 「예정」이라 적힌 자리가 그것이다.

## 1. 결정 근거

- 사업자 결정: 지라 KAN-197 코멘트 (2026-09-13, 팀장) —
  https://accentury.atlassian.net/browse/KAN-197
- AdFit(카카오) 대비 근거 셋
  - **방침 본문이 거의 안 늘어난다.** 4항 국외 이전의 이전받는 자가 이미 Google LLC라
    (Firebase·GA·AdMob), AdSense는 같은 `<dl>`에 이름 하나 더하는 것으로 끝난다. 새 사업자를
    들이면 이전받는 자·국가·기간을 통째로 한 벌 더 써야 하고, 2항 제3자 제공 표에도 행이 는다
  - **비맞춤 전환이 공식 문서에 있다.** `requestNonPersonalizedAds=1` (§4). AdMob의 npa와
    같은 자리다
  - **검증할 수 있다.** 광고 요청 URL의 `npa=1`을 개발자 도구에서 눈으로 본다 (§4)
- **승인 전에 슬롯이 비는 것은 정상이다.** AdSense 사이트 심사가 끝나기 전에는 태그가 붙어도
  광고가 내려오지 않는다. 4단계 전까지 빈 자리를 보고 「배선이 깨졌다」고 읽지 말 것 — 판정은
  요청이 나가는가(개발자 도구)로 한다

## 2. 실행 환경 게이트 — 앱 WebView에는 설치하지 않는다

```
isStandaloneWeb(window.location.search) === true   → AdSense 태그 설치
그 밖(앱 WebView)                                   → 설치하지 않음
```

GA4 태그와 **같은 규칙, 같은 자리**다 (`web/src/main.tsx:28`,
`if (isStandaloneWeb(window.location.search)) installGa4Tag()`). 이유는 서로 다르다 — GA4는
같은 사건을 네이티브 Firebase와 두 번 세지 않으려고 막고, AdSense는 **정책 때문에** 막는다:
AdSense 광고 태그를 앱 WebView 안에서 돌리는 것은 허용되지 않는다. 앱 안의 광고는 AdMob SDK가
네이티브에서 띄운다.

여기서 따라 나오는 결과가 하나 있다. **WebView allowlist에 광고 도메인을 추가하지 않는다.**
태그가 WebView에서 아예 설치되지 않으므로 열어 줄 것이 없다 —
[`webview-bridge.md`](webview-bridge.md)의 allowlist는 KAN-197로 바뀌지 않는다.

## 3. 동의

| 실행 | 묻는 자리 | 저장 | 단계 |
|---|---|---|---|
| 앱 | 인트로 위 동의 시트 | 네이티브 (SharedPreferences / UserDefaults) — 정본 | 완료 (KAN-196) |
| 브라우저 웹 | 같은 시트 | **브라우저 저장소** | **2단계 예정** |

시트는 같은 컴포넌트(`web/src/ads/AdConsentSheet.tsx`)이고 문안만 갈린다. 고지 4요소 중 갈리는
것은 ①사업자와 ②수집 항목 둘 — 앱은 「Google AdMob / 기기의 광고 식별자」, 웹은
「Google AdSense / 브라우저 쿠키」다. 계약은 `web/src/ads/adConsentText.ts`의

```ts
export type AdVendor = 'admob' | 'adsense'
export const AD_CONSENT_WHY: Record<AdVendor, string>
export const AD_CONSENT_EFFECT: Record<AdVendor, string>
```

이고 시트는 `vendor?: AdVendor` prop으로 고른다 (기본값 `'admob'` — 지금 호출처가 앱 경로뿐이라
그렇다). 2단계가 웹 경로에서 `vendor="adsense"`를 넘긴다.

저장이 웹에서만 브라우저 저장소인 이유는 [`webview-bridge.md` §8.1](webview-bridge.md)에 있다 —
브리지가 없는 실행이라 네이티브 저장소에 닿을 길이 없다. 앱 쪽 정본이 네이티브인 것은 그대로다.

## 4. 요청 규칙 (3단계 예정)

전부 문서로 확인한 것이다. 기억으로 쓰지 않는다 — 아래 근거 열의 페이지를 읽고 적었다.

| 하려는 것 | API | 근거 |
|---|---|---|
| 비맞춤 광고 요청 | `(adsbygoogle=window.adsbygoogle||[]).requestNonPersonalizedAds=1` | [adsense/answer/9042142](https://support.google.com/adsense/answer/9042142), [7670312](https://support.google.com/adsense/answer/7670312) |
| 맞춤 광고로 되돌리기 | 같은 값에 `=0` | 위와 같음 |
| 동의 전 요청 막기 | `(adsbygoogle=window.adsbygoogle||[]).pauseAdRequests=1` | 위와 같음 |
| 결정 뒤 요청 재개 | `pauseAdRequests=0` | 위와 같음 |
| 비맞춤인지 확인 | 광고 요청 URL에 `&npa=1` | [7670312](https://support.google.com/adsense/answer/7670312) |

**순서가 계약이다.** `requestNonPersonalizedAds`도 `pauseAdRequests`도 첫
`(adsbygoogle = window.adsbygoogle || []).push({})` **전에** 세워야 한다 — 문서의 말은
"You must do this before triggering any ad requests by using `adsbygoogle.push(...)`"다. 스크립트
태그 로드 → 플래그 → `<ins>` 슬롯 → `push({})` 순이다.

두 가지를 못 박아 둔다.

- **`pauseAdRequests=0`을 부르지 않으면 광고가 하나도 안 나온다.** 동의를 묻는 동안 멈춰 두는
  것이 이 플래그의 쓰임인데, 선택 뒤에 푸는 호출을 빠뜨리면 증상이 「빈 슬롯」이라 §1의
  「승인 전이라 빈 것」과 구분되지 않는다. 3단계에서 두 경로(허용·거부) 모두 `=0`을 거치는지
  테스트로 붙든다
- **`pauseAdRequests`는 요청만 막는다.** 문서가 명시한다 — "This technique blocks ad requests
  from being sent, but various scripts are still loaded." 스크립트 자체를 안 붙이려면 §2의
  게이트여야 한다

`ppt=1`은 우리 플래그의 확인 값이 **아니다.** 같은 페이지에 두 확인 값이 나란히 있는데,
`npa=1`은 `requestNonPersonalizedAds`(우리가 쓰는 것), `ppt=1`은
`google_privacy_treatments: 'disablePersonalization'`(우리가 쓰지 않는 별개 플래그)의 확인 값이다.
검증할 때 `ppt`를 찾으면 영영 못 찾는다.

거부해도 쿠키가 아주 사라지지는 않는다. Google은 비맞춤 광고에서도 쿠키를 **빈도 제한과 집계된
성과 보고**에 계속 쓴다고 적었다
([adsense/answer/9007336](https://support.google.com/adsense/answer/9007336)). 방침 8·10항이
「관심사 추정에는 쓰지 않고 …에만 쓴다」라고 적은 것이 이 사실이다 — 「대신」이라고 쓰면 거짓
고지가 된다 (KAN-196 리뷰 P1-4, `privacy.test.mjs`가 막는다).

## 5. 슬롯

| 자리 | 앱 (AdMob) | 브라우저 웹 (AdSense) |
|---|---|---|
| 분석 대기 화면 | 전면(interstitial) 1회 | **배너 1개** |
| 결과 화면 [다시 테스트하기] | 보상형(rewarded) — 끝까지 봐야 재응시 | **광고 없음 — 그대로 재응시** |

웹에 보상형을 두지 않는 것이 이 표에서 제일 중요하다. 재응시 CTA의 문구도 흐름도 웹에서는
**바뀌지 않는다** — 앱의 「광고 보고 다시 테스트하기」 라벨은 브리지가 있는 실행의 것이고
(`webview-bridge.md` §8), 웹은 원래 라벨 그대로 광고 없이 통과한다.

방침 10항이 이 구분을 문장으로 적었고 계약 테스트가 붙든다 (`privacy.test.mjs`의
`브라우저 웹의 광고 사업자 Google AdSense가 2·4·8·10항에 적혀 있다`, assert
`웹의 재응시에는 광고가 없`).

## 6. ID 주입 계획 (4단계)

| 빌드 변수 | 없을 때 | 어디로 |
|---|---|---|
| `VITE_ADSENSE_CLIENT_ID` | **아무 일도 하지 않는다 (no-op)** | 태그 스크립트의 `client` |
| `VITE_ADSENSE_SLOT_ID` | 같음 | `<ins>`의 `data-ad-slot` |

GA4와 같은 규칙이다 — `VITE_GA4_MEASUREMENT_ID`가 없으면 `installGa4Tag`가 `false`를 돌려주고
끝난다 (`web/src/analytics/ga4.ts:53-56`). 광고도 같게 간다: 값이 없는 빌드는 태그를 붙이지 않고
슬롯도 그리지 않는다.

**AdMob과 여기서 갈린다.** 앱은 값이 없으면 Google 테스트 ID로 **켜 둔다**
([`ads-admob.md` §3](ads-admob.md)) — 광고 없는 빌드가 전면·보상형 결선을 한 번도 안 밟기
때문이다. 웹은 그 문제가 약하다(슬롯이 하나이고 흐름을 막지 않는다). 대신 AdSense는 승인된
사이트에서만 광고가 내려오므로, 테스트 ID를 넣어도 얻는 것이 없다.

## 7. 범위 밖

- **CSP 헤더** — CloudFront에 응답 헤더 정책 자체가 없어 이번에 손댈 자리가 없다. 나중에 정책을
  세우면 AdSense 도메인을 그때 함께 넣는다. 기록만 남긴다
- **GA4 광고 파라미터** — 광고 노출·클릭은 AdSense 대시보드에서 본다. GA4 이벤트에 광고
  파라미터를 싣지 않는다 (KAN-33 스키마 불변, `web/src/analytics/events.ts`). GA4는 지금도
  `allow_ad_personalization_signals: false`다
- **앱 WebView allowlist** — §2. 태그를 안 까니 열 것이 없다

## 8. 파일 지도

1단계(2026-09-13)에서 바뀐 것.

| 파일 | 무엇이 바뀌었나 |
|---|---|
| `infra/privacy/privacy.html` | 2·4·8·10항에 웹 AdSense 몫 (사업자·광고 쿠키·배너·웹 철회 경로) |
| `infra/privacy/privacy.test.mjs` | 2항 정확 일치 완화 + 웹 사업자 테스트 1건 |
| `web/src/ads/adConsentText.ts` | `AdVendor` 타입, `AD_CONSENT_WHY`·`AD_CONSENT_EFFECT`를 사업자별 `Record`로 |
| `web/src/ads/AdConsentSheet.tsx` | `vendor?: AdVendor` prop (기본값 `'admob'`) |
| `web/src/ads/AdConsentSheet.test.tsx` | 사업자별 문안 2건 |
| `docs/wiki/ads-web-adsense.md` | 이 문서 |

2~4단계에서 손댈 것.

| 단계 | 파일 | 할 일 |
|---|---|---|
| 2 | `web/src/ads/adConsent.ts` (`useAdConsent`) | 브리지가 없으면 브라우저 저장소로 갈린다 |
| 2 | `web/src/intro/IntroScreen.tsx` | 웹 경로에서 `vendor="adsense"` |
| 3 | 새 `web/src/ads/adsense.ts` | 태그 설치 + `requestNonPersonalizedAds`·`pauseAdRequests` (§4) |
| 3 | `web/src/main.tsx` | `isStandaloneWeb` 게이트 (§2) |
| 4 | 분석 대기 화면 | 배너 슬롯 1개 (§5) |
| 4 | `web/.env` 계열·배포 워크플로 | `VITE_ADSENSE_*` 주입 (§6) |
