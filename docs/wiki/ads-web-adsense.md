# 웹 광고 — Google AdSense (KAN-197)

브라우저 단독 실행(`https://accentury.app`)의 광고다. 앱 광고는 AdMob이고 정본은
[`ads-admob.md`](ads-admob.md) — 이 문서는 **웹 몫**을 맡는다. 사업자가 갈린 이유가 하나뿐이라
말이 짧다: **AdMob은 웹을 지원하지 않는다.**

1단계(2026-09-13)에서 정한 것은 방침·동의 문안·이 문서까지이고, 2단계(2026-09-13)에서 웹 동의를
묻고 저장하는 자리를 배선했다 (§3). 3단계(2026-09-13)에서 태그·요청 플래그·배너 슬롯이 코드로
들어왔다 (§4·§5). 남은 것은 4단계 — 배포 빌드에 ID를 주입하고(§6) `ads.txt`를 올리고 완주
E2E로 슬롯을 눈으로 보는 일이다.

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

### 2.1 설치 시점은 `main.tsx`가 아니라 슬롯 마운트다 (3단계 판단)

지라는 "GA4와 같은 규칙, `main.tsx`"라고 적었다. 규칙의 본질은 **게이트**(`isStandaloneWeb`)이지
호출 위치가 아니어서, 3단계는 위치만 옮겼다 — 태그는 분석 대기 화면의 슬롯이 마운트될 때
선다 (`web/src/ads/AdSlot.tsx`의 이펙트). `main.tsx`는 이 티켓으로 바뀌지 않는다.

근거가 둘이다.

- **첫 렌더에 외부 스크립트가 끼지 않는다.** 인트로의 체감 속도는 KAN-179가 손본 자리이고,
  광고 스크립트는 그 화면에서 아무것도 그리지 않으면서 네트워크만 먹는다
- **묻기 전에 광고 서버에 접속하는 경로가 구조적으로 없다.** 동의 시트는 인트로 위에 뜨므로
  (§3), 대기 화면까지 온 사용자는 이미 고른 뒤다. 아래 `pauseAdRequests`는 그래서 방어용으로만
  남는다 — 없어도 되는 값이 아니라, **있어도 실제로는 걸릴 일이 없는** 값이다

시트는 선택 없이 닫히지 않고(§3) 저장이 막힌 브라우저에서도 메모리 사본이 이번 방문을 이어
주므로(§3.2), 인트로를 거쳐 온 방문이 `unknown`으로 대기 화면에 서는 일은 사실상 없다. 그래도
플래그를 두는 것은 인트로를 거치지 않고 대기 화면 주소로 바로 들어오는 경우 때문이다 — 그
경로에서 물어보지도 않고 맞춤 광고를 요청하지 않는다.

## 3. 동의

| 실행 | 묻는 자리 | 저장 | 단계 |
|---|---|---|---|
| 앱 | 인트로 위 동의 시트 | 네이티브 (SharedPreferences / UserDefaults) — 정본 | 완료 (KAN-196) |
| 브라우저 웹 | 같은 시트 | **브라우저 저장소** (`localStorage`) | 완료 (2단계, 2026-09-13) |

시트는 같은 컴포넌트(`web/src/ads/AdConsentSheet.tsx`)이고 문안만 갈린다. 고지 4요소 중 갈리는
것은 ①사업자와 ②수집 항목 둘 — 앱은 「Google AdMob / 기기의 광고 식별자」, 웹은
「Google AdSense / 브라우저 쿠키」다. 계약은 `web/src/ads/adConsentText.ts`의

```ts
export type AdVendor = 'admob' | 'adsense'
export const AD_CONSENT_WHY: Record<AdVendor, string>
export const AD_CONSENT_EFFECT: Record<AdVendor, string>
```

이고 시트는 `vendor?: AdVendor` prop으로 고른다 (기본값 `'admob'`). 인트로가 훅이 고른 값을
그대로 넘긴다.

저장이 웹에서만 브라우저 저장소인 이유는 [`webview-bridge.md` §8.1](webview-bridge.md)에 있다 —
브리지가 없는 실행이라 네이티브 저장소에 닿을 길이 없다. 앱 쪽 정본이 네이티브인 것은 그대로다.

### 3.1 갈래는 `resolveAdConsentSource` 한 곳에서 정한다 (2단계)

`web/src/ads/adConsent.ts`의 순수 함수다. 훅 밖에 둔 것은 세 갈래가 각각 어떤 실행을 뜻하는지가
렌더링과 무관한 판정이라 렌더 없이 그대로 확인할 수 있어야 하기 때문이다.

| 실행 | 판정 | 저장소 | `vendor` | `consent` |
|---|---|---|---|---|
| 브리지에 `getAdConsent`가 있다 | `'bridge'` | 네이티브 | `admob` | `readAdConsent()` |
| 객체도 `?bridge=`도 없다 (`isStandaloneWeb`) | `'web'` | `localStorage` | `adsense` | `readWebAdConsent()` |
| 그 밖 — 구버전 앱, `?bridge=`만 있는 WebView | `'none'` | 없음 | (`admob`, 의미 없음) | `null` |

**메서드의 유무를 객체의 유무보다 먼저 본다.** 그다음이 `isStandaloneWeb`이고, 나머지 조합은
전부 `'none'`이다 — 그 둘을 웹으로 보내면 앱 안에서 브라우저 저장소에 동의를 적게 되고 정작
SDK를 세우는 네이티브는 그 값을 모른다.

`consent === null`의 뜻이 좁아졌다. 이제 부재는 `'none'`뿐이고, 브라우저 단독 실행은 부재가
아니라 웹 저장소에 묻는 경로다.

### 3.2 웹 저장소 규칙 (`webAdConsentStore.ts`)

| 항목 | 값·규칙 | 이유 |
|---|---|---|
| 키 | `accentury:adConsent` | 진행 스냅샷(`accentury:progress`)과 같은 접두어 — 오리진 안에서 키가 섞이지 않게 하는 규칙이 하나뿐이어야 한다 |
| 저장 값 | `'granted'` \| `'denied'` | `unknown`은 고를 수 없다 (`AdConsentChoice`) |
| 계약 밖 문자열 | **`unknown`으로 접는다** | 브리지 `readAdConsent`가 null로 접는 것과 다르다. 웹 저장소의 깨진 값은 우리 자신의 옛 값이거나 사용자가 만진 것이고 쓰기 경로는 멀쩡하다 — 다시 물으면 제대로 된 값이 들어간다. 브리지 쪽은 계약이 어긋난 앱이라 쓰기도 어긋났을 가능성이 커 조용히 없는 셈 친다 |
| 접근이 던질 때 | 읽기는 `unknown`, 쓰기는 무시 | 쿠키 차단 브라우저는 `window.localStorage` 프로퍼티 접근 자체가 던진다. `progressSnapshot.ts`의 `defaultSnapshotStorage`와 같은 guard를 **복사해서** 둔다 — 광고가 진행 상태 모듈에 묶일 이유가 없다 |
| 메모리 사본 | 쓰기는 항상 사본에 먼저, 읽기는 사본이 있으면 사본 | 저장소가 없는 환경(사생활 모드·쿼터 초과)에서도 이번 방문 안에서는 선택이 지켜져야 시트가 닫히고 링크가 생긴다. 다음 방문에 다시 묻는 것은 「브라우저 저장소에 둡니다」가 약속한 범위 그대로다 |
| `writeWebAdConsent`의 반환 | **항상 `true`** | 앱 쪽 `writeAdConsent`의 true가 「네이티브에 닿았다」인 것과 대칭이 아니다 — 이쪽은 「메모리에는 반드시 닿는다」다 |

테스트 사이에 사본이 새지 않게 `resetWebAdConsentMemory()`를 export한다 (테스트 전용).
`IntroScreen.test.tsx`·`adConsent.test.ts`·`webAdConsentStore.test.ts`의 `afterEach`가 부른다.

## 4. 요청 규칙 (3단계 구현)

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
  「승인 전이라 빈 것」과 구분되지 않는다. 3단계가 두 경로(허용·거부) 모두 `=0`을 거치는지
  테스트로 붙들었다 (`adsense.test.ts`의 「허용도 거부도 요청을 재개한다」)
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

### 4.1 코드가 된 표 (`web/src/ads/adsense.ts`)

위 규칙이 함수 넷으로 들어왔다. 전부 boolean만 돌려주고 **예외를 밖으로 내보내지 않는다** —
광고 로드 실패·DOM 예외·중복 push가 응시 흐름을 막으면 안 되기 때문이고, 공유 모듈과 같은
규칙이다.

| 함수 | 하는 일 | false·no-op이 되는 때 |
|---|---|---|
| `adSenseIdsFromEnv()` | 두 빌드 변수를 읽어 `{clientId, slotId}` | 하나라도 비면 `null` (§6) |
| `adSenseRequestFlags(consent)` | 동의 → 플래그 두 개 (아래 표) | — (순수 함수) |
| `installAdSenseTag(consent, ids?, doc?)` | 큐를 세우고 플래그를 얹고 스크립트를 붙인다 | ID 없음 · 이미 설치 · DOM 예외 |
| `applyAdConsentToAdSense(consent)` | 이미 선 큐의 플래그만 갱신 | 큐가 없으면 아무 일 없음 |
| `pushAdSlot()` | `(adsbygoogle ||= []).push({})` | push가 던지면 삼키고 false |

| 동의 | `requestNonPersonalizedAds` | `pauseAdRequests` | 뜻 |
|---|---|---|---|
| `granted` | 0 | 0 | 맞춤 광고를 요청한다 |
| `denied` | 1 | 0 | 요청하되 비맞춤 — 요청 URL에 `npa=1` |
| `unknown` | 1 | 1 | 요청 자체를 내보내지 않는다 |

`unknown`에도 npa를 1로 두는 이유는 두 플래그가 서로를 보장하지 않아서다. 어떤 경로로든
일시정지가 먼저 풀리면 그 순간 나가는 요청은 남아 있는 npa 값을 따른다.

**순서**는 설치 함수 안에서 지켜진다 — 큐와 플래그를 세운 **뒤에** 스크립트를 붙이고, `<ins>`
슬롯은 렌더 결과라 이펙트보다 먼저 DOM에 있고, `push({})`가 마지막이다.

**선택을 바꾸는 경로.** 시트에서 다시 고르면 `useAdConsent.choose`가 웹 갈래에서
`applyAdConsentToAdSense`를 부른다 (`web/src/ads/adConsent.ts`). 앱에서 네이티브가
`setAdConsent`를 받아 SDK를 다시 세우는 것과 같은 자리다 — 웹에서 SDK에 해당하는 것이 이미 선
adsbygoogle 큐다. 이미 나간 요청은 되돌릴 수 없고 **다음 요청부터** 새 값을 따른다. 시트는
인트로에 뜨고 태그는 대기 화면에서 서므로 실제로는 대부분의 호출이 아무 일도 하지 않는다.

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

### 5.1 배너가 서는 자리 (3단계)

컴포넌트는 `web/src/ads/AdSlot.tsx`이고 `AnalysisWaitingScreen`의 `.analysis-waiting` 안,
**단계 표시(`.analysis-steps`) 바로 아래**에 있다. 위에 두면 「분석 중입니다」 히어로와 진행
상태 사이를 광고가 가른다. 자리를 대기 화면으로 정한 근거는 앱의 전면 광고와 같다 — 이 화면
에는 눌러야 할 CTA가 없고(시작·공유·앱 다운로드는 전부 다른 화면이다) 폴링이 도는 동안 화면이
머문다. 시작·공유·재응시 CTA는 이 티켓으로 **하나도 바뀌지 않는다.**

```html
<div class="ad-slot" role="complementary" aria-label="광고">
  <ins class="adsbygoogle" style="display:block"
       data-ad-client="ca-pub-…" data-ad-slot="…"
       data-ad-format="auto" data-full-width-responsive="true"></ins>
</div>
```

`role="complementary"`와 이름 「광고」는 고지이기도 하다 — 스크린 리더 사용자가 이 영역을
건너뛸지 스스로 정할 수 있어야 한다.

**그리지 않는 경우가 둘이다.** 앱 WebView(§2)와 ID가 없는 빌드(§6). 둘 다 `null`을 돌려주므로
`<ins>`도 감싼 상자도 없다 — 채워질 일이 없는 자리를 100px 비워 두면 단계 표시만 아래로 밀린다.

CSS는 `ui/components.css`의 `.ad-slot`이고 규칙이 셋뿐이다.

| 선언 | 이유 |
|---|---|
| `min-height: 100px` | **CLS 방지.** 광고는 늦게 채워지는데 자리를 미리 잡지 않으면 도착하는 순간 위 단계 표시가 튄다. 더 큰 광고가 오면 상자가 따라 늘어난다(상한 없음) |
| `align-self: stretch` | `.analysis-waiting`이 `align-items: center`라 그대로 두면 내용 폭(0)으로 쪼그라든다. 폭 상한은 부모의 `--content-max-width`(320)를 그대로 받는다 |
| `margin-top: var(--space-2)` | 부모 gap(24) 위에 8을 더한다 — 광고가 단계 표시와 같은 덩어리가 아니라는 것이 간격으로 읽혀야 한다 |

`waiting`이 거짓인 분기(오류·행동 요구)에서는 히어로 블록째 그리지 않으므로 슬롯도 없다.
사용자가 무엇을 해야 하는지를 광고가 밀어내지 않는다.

## 6. ID 주입 (읽는 쪽은 3단계, 넣는 쪽은 4단계)

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

**둘 다 있어야 한다.** 하나만 준 빌드는 값이 없는 빌드와 같다 (`adSenseIdsFromEnv`가 `null`).
게시자 ID만 알면 그릴 자리가 없고, 슬롯 ID만 알면 붙일 태그가 없다 — 반쪽짜리 설정이 「태그는
떴는데 빈 칸」으로 나타나면 §1의 승인 전 상태와 구분되지 않는다.

4단계가 맞춰야 할 것 셋이다.

| 할 일 | 값 | 자리 |
|---|---|---|
| 빌드 변수 주입 | `VITE_ADSENSE_CLIENT_ID`(`ca-pub-` + 숫자 16자리) · `VITE_ADSENSE_SLOT_ID` | `.github/workflows/web-deploy.yml`의 빌드 스텝 env (`VITE_GA4_MEASUREMENT_ID`·`VITE_REGION_SELECT` 옆) |
| `ads.txt` | `google.com, pub-0000000000000000, DIRECT, f08c47fec0942fa0` | `web/public/ads.txt` → 배포되면 `https://accentury.app/ads.txt` |
| 완주 E2E | 대기 화면에서 슬롯이 실제로 서는지 | `smoke`·`full-run` (백엔드·AI 스텁 필요) |

`ads.txt`는 두 가지를 주의한다 (확인:
[adsense/answer/7532444](https://support.google.com/adsense/answer/7532444)). 접두어가
**`pub-`이지 `ca-pub-`이 아니다** — 빌드 변수에 넣는 값에서 `ca-`를 떼야 한다. 그리고 파일이
루트에 있어야 한다: 브라우저로 `https://accentury.app/ads.txt`를 열어 내용이 보이면 된다.
`f08c47fec0942fa0`은 Google이 정한 고정 값이라 그대로 쓴다.

## 7. 검증 절차 — 눈이 아니라 요청으로 본다

빈 슬롯은 증상이 하나인데 원인이 둘이라(승인 전 / 배선이 깨짐) 화면만 보고는 못 가른다.
판정은 개발자 도구 Network에서 한다.

1. 브라우저로 분석 대기 화면까지 간다. 인트로의 동의 시트에서 **「일반 광고만 보기」**를 고른다
2. Network를 열고 `googleads` 또는 `pagead`로 거른다
3. 광고 요청 URL의 쿼리에 **`npa=1`**이 있으면 거부가 제대로 실렸다. `ppt`를 찾으면 영영 못
   찾는다 — 우리 플래그의 확인 값이 아니다 (§4)
4. 다시 「맞춤형 광고 허용」으로 바꾸고 새로 고치면 같은 자리에 `npa`가 **없다**

응답이 200인데 슬롯이 비어 있으면 그것은 승인 전 상태다 (§1). 요청 자체가 아예 안 나가면 셋을
순서대로 본다 — `window.adsbygoogle`이 있는가(없으면 태그가 안 섰다: ID 또는 §2 게이트),
`pauseAdRequests`가 0인가(1이면 동의가 `unknown`으로 읽혔다), `<ins class="adsbygoogle">`가
DOM에 있는가.

앱 WebView에서는 셋 다 없는 것이 정답이다. 이 판정은 자동 테스트가 붙든다 —
`AnalysisWaitingScreen.test.tsx`의 「앱 WebView 안에서는 웹 광고 태그가 설치되지 않는다
(KAN-197 AC)」가 슬롯·`window.adsbygoogle`·스크립트 태그 셋의 부재를 함께 단언한다.

## 8. 범위 밖

- **CSP 헤더** — CloudFront에 응답 헤더 정책 자체가 없어 이번에 손댈 자리가 없다. 나중에 정책을
  세우면 AdSense 도메인을 그때 함께 넣는다. 기록만 남긴다
- **GA4 광고 파라미터** — 광고 노출·클릭은 AdSense 대시보드에서 본다. GA4 이벤트에 광고
  파라미터를 싣지 않는다 (KAN-33 스키마 불변, `web/src/analytics/events.ts`). GA4는 지금도
  `allow_ad_personalization_signals: false`다
- **앱 WebView allowlist** — §2. 태그를 안 까니 열 것이 없다

## 9. 파일 지도

1단계(2026-09-13)에서 바뀐 것.

| 파일 | 무엇이 바뀌었나 |
|---|---|
| `infra/privacy/privacy.html` | 2·4·8·10항에 웹 AdSense 몫 (사업자·광고 쿠키·배너·웹 철회 경로) |
| `infra/privacy/privacy.test.mjs` | 2항 정확 일치 완화 + 웹 사업자 테스트 1건 |
| `web/src/ads/adConsentText.ts` | `AdVendor` 타입, `AD_CONSENT_WHY`·`AD_CONSENT_EFFECT`를 사업자별 `Record`로 |
| `web/src/ads/AdConsentSheet.tsx` | `vendor?: AdVendor` prop (기본값 `'admob'`) |
| `web/src/ads/AdConsentSheet.test.tsx` | 사업자별 문안 2건 |
| `docs/wiki/ads-web-adsense.md` | 이 문서 |

2단계(2026-09-13)에서 바뀐 것.

| 파일 | 무엇이 바뀌었나 |
|---|---|
| `web/src/ads/webAdConsentStore.ts` | **신설.** 키·메모리 사본·`unknown` 접기·저장소 guard (§3.2) |
| `web/src/ads/webAdConsentStore.test.ts` | **신설.** 7건 — 없음·왕복·계약 밖 값·읽기 예외·쓰기 예외·사본 초기화 |
| `web/src/ads/adConsent.ts` | `resolveAdConsentSource` 3갈래 + `AdConsentControl.vendor` (§3.1) |
| `web/src/ads/adConsent.test.ts` | **신설.** 7건 — 갈래 4건 + 훅 3건 |
| `web/src/ads/AdConsentSheet.tsx` | 주석만. Escape를 안 듣는 근거가 「앱 안에서만 뜬다」에서 「선택 없이 닫히면 또 뜬다」로 좁혀졌다 |
| `web/src/intro/IntroScreen.tsx` | `vendor`를 시트에 넘긴다. 나머지 로직 불변 |
| `web/src/intro/IntroScreen.test.tsx` | 「브리지 없음에는 시트도 링크도 없다」를 뒤집고 `?bridge=`만 있는 갈래 1건 추가 |
| `web/e2e/helpers/testFlow.ts` | `passAdConsentIfShown` + `startTest`에서 호출 |
| `web/e2e/smoke.spec.ts` · `mic-blocked.spec.ts` | 같은 헬퍼 호출 (3곳) |
| `docs/wiki/browser-e2e.md` · `webview-bridge.md` · `privacy-policy.md` | 2단계 사실 반영 |

3단계(2026-09-13)에서 바뀐 것.

| 파일 | 무엇이 바뀌었나 |
|---|---|
| `web/src/ads/adsense.ts` | **신설.** 태그 설치·요청 플래그·슬롯 push (§4.1). `ga4.ts`의 거울이다 |
| `web/src/ads/adsense.test.ts` | **신설.** 20건 — ID 읽기 4, 플래그 표 4, 설치 7, 갱신 2, push 3 |
| `web/src/ads/AdSlot.tsx` | **신설.** 배너 컴포넌트. 게이트 둘(§2·§6)에 걸리면 `null` (§5.1) |
| `web/src/ads/AdSlot.test.tsx` | **신설.** 5건 — 웹 단독·WebView 2갈래·ID 결손 2갈래 |
| `web/src/ads/adConsent.ts` | 웹 갈래의 `choose`가 `applyAdConsentToAdSense`를 부른다 (§4.1 마지막 절) |
| `web/src/analysis/AnalysisWaitingScreen.tsx` | `.analysis-steps` 아래에 `<AdSlot />` 한 줄 + 헤더에 KAN-197 단락 |
| `web/src/analysis/AnalysisWaitingScreen.test.tsx` | 3건 — 슬롯 노출·**앱 WebView 부재(AC)**·ID 없는 빌드 |
| `web/src/ui/components.css` | `.ad-slot` (§5.1의 표) |
| `docs/wiki/ads-web-adsense.md` · `browser-e2e.md` | 이 문서의 §2.1·§4.1·§5.1·§7, E2E 실측 한 절 |

**`web/src/main.tsx`는 바뀌지 않았다.** 게이트는 같지만 설치 자리가 슬롯 마운트로 옮겨 갔다 —
근거는 §2.1이다.

4단계에서 손댈 것.

| 파일 | 할 일 |
|---|---|
| `.github/workflows/web-deploy.yml` | `VITE_ADSENSE_CLIENT_ID`·`VITE_ADSENSE_SLOT_ID` 주입 (§6) |
| 새 `web/public/ads.txt` | `google.com, pub-…, DIRECT, f08c47fec0942fa0` (§6) |
| `web/e2e/smoke.spec.ts` 등 | 대기 화면까지 완주해 슬롯 실측 (백엔드·AI 스텁 필요) |
