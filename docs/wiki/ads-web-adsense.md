# 웹 광고 — Google AdSense (KAN-197)

브라우저 단독 실행(`https://accentury.app`)의 광고다. 앱 광고는 AdMob이고 정본은
[`ads-admob.md`](ads-admob.md) — 이 문서는 **웹 몫**을 맡는다. 사업자가 갈린 이유가 하나뿐이라
말이 짧다: **AdMob은 웹을 지원하지 않는다.**

1단계(2026-09-13)에서 정한 것은 방침·동의 문안·이 문서까지이고, 2단계(2026-09-13)에서 웹 동의를
묻고 저장하는 자리를 배선했다 (§3). 3단계(2026-09-13)에서 태그·요청 플래그·배너 슬롯이 코드로
들어왔다 (§4·§5). 4단계(2026-09-13)에서 배포 빌드의 ID 주입과 `ads.txt` 생성이 워크플로에
들어오고(§6) 완주 E2E가 태그 있는 빌드·없는 빌드 양쪽을 실측했다 (§7.1).

**코드로 할 일은 여기서 끝났다.** 남은 것은 사람이 하는 운영 절차다 — AdSense 가입부터 변수
등록까지의 순서가 §10에 있고, 그것이 끝나기 전까지 prod 빌드에 광고가 없는 것은 고장이 아니다.

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

4단계(2026-09-13)가 셋을 다 넣었다 — 빌드 변수(§6.1), `ads.txt`(§6.2), 완주 E2E(§7.1). 계획과
갈린 자리가 하나다: `ads.txt`를 정적 파일로 두지 않기로 했다.

### 6.1 배포 변수 등록 — prod에만 둔다

빌드 스텝이 `VITE_GA4_MEASUREMENT_ID`·`VITE_REGION_SELECT` 옆에서 두 값을 받는다
(`.github/workflows/web-deploy.yml`).

```yaml
VITE_ADSENSE_CLIENT_ID: ${{ vars.ADSENSE_CLIENT_ID }}
VITE_ADSENSE_SLOT_ID: ${{ vars.ADSENSE_SLOT_ID }}
```

값은 GitHub environment 변수로 등록한다. 절차의 정본은
[`infra/README.md`](../../infra/README.md)의 「GitHub 설정」이고(GA4 변수 문단 바로 아래)
명령은 둘이다.

```
gh variable set ADSENSE_CLIENT_ID -e prod --body ca-pub-XXXXXXXXXXXXXXXX
gh variable set ADSENSE_SLOT_ID   -e prod --body XXXXXXXXXX
```

**staging에는 두지 않는다.** 근거가 GA4와 다르다 — 계측은 두 환경의 집계가 섞이면 안 돼서
갈랐지만, 광고는 **승인 대상이 prod 하나**여서 갈린다. 심사를 통과한 사이트에만 광고가
내려오므로 staging에 값을 넣으면 태그만 서고 빈 슬롯이 남는다. 시크릿이 아니라 변수인 것은
GA4와 같은 이유다: 스크립트 주소와 `<ins>` 속성에 그대로 박히는 공개 값이다.

**넣는 시점도 늦다.** 사이트 승인이 나고 광고 단위를 만든 **뒤**다 (§10). 그전까지 prod는
변수 없이 배포되고, 그 빌드에 광고가 없는 것이 정상 상태다.

### 6.2 `ads.txt`는 워크플로가 만든다 — 레포의 정적 파일이 아니다

3단계까지의 계획은 `web/public/ads.txt`라는 정적 파일이었다. 4단계에서 뒤집었고 근거가 둘이다.

- **파일에 적히는 게시자 ID가 환경 값이다.** 레포에 박으면 staging 번들에도 같은 줄이 실린다
- **가짜 ID가 적힌 `ads.txt`는 해롭다.** 자리표시자를 채워 두면 AdSense가 그 도메인을
  「승인되지 않은 판매자」로 읽는다. 파일이 없는 편이 틀린 파일이 있는 것보다 낫다

그래서 빌드 다음에 스텝 하나가 선다 (`ads.txt 생성 (KAN-197)`). 값이 없으면 파일을 만들지 않고
`::notice`만 남긴 채 지나가고, 있으면 `dist/ads.txt`에 한 줄을 적는다.

```
google.com, pub-0000000000000000, DIRECT, f08c47fec0942fa0
```

형식은 [adsense/answer/7532444](https://support.google.com/adsense/answer/7532444)에서 확인했다
(2026-09-13 재확인). 네 칸이고 `f08c47fec0942fa0`은 Google이 정한 고정 값이라 그대로 쓴다.

**접두어가 `pub-`이지 `ca-pub-`이 아니다.** `ca-`를 떼는 일은 스텝이 한다
(`${ADSENSE_CLIENT_ID#ca-}`). 그리고 값이 `ca-pub-`으로 시작하지 않으면 **스텝이 실패한다** —
틀린 게시자 ID가 적힌 파일이 조용히 올라가면 소유권 확인이 안 되는데, 증상이 「심사가 아직 안
끝났다」와 구분되지 않아 몇 주를 잃는다. 셸 조각을 세 경우로 돌려 본 결과다 (2026-09-13).

| `ADSENSE_CLIENT_ID` | 결과 |
|---|---|
| `ca-pub-1234567890123456` | exit 0 · `google.com, pub-1234567890123456, DIRECT, f08c47fec0942fa0` · `generated=true` |
| (빈 값) | exit 0 · 파일 없음 · `generated=false` + `::notice` |
| `pub-1234567890123456` | **exit 1** · 파일 없음 |

업로드는 `index.html`과 같은 부류로 간다. 해시 자산 sync에서 빼고(`--exclude ads.txt`)
`no-cache`로 따로 올린 뒤 CloudFront에서 `/ads.txt`를 무효화한다 — 이름에 해시가 없어 내용이
바뀌어도 주소가 그대로이기 때문이다. 확장자가 있어 SPA 재작성 Function(KAN-126)을 타지 않으므로
캐시 키도 경로 그대로다. 파일을 만들지 않은 환경에서는 업로드도 무효화도 건너뛰고, **버킷에 이미
있던 파일을 지우지는 않는다** (sync에 `--delete`가 없는 것과 같고 배포 역할에 DeleteObject 권한도
없다). 광고를 내리려면 변수를 지우는 것으로 부족하고 객체를 따로 지워야 한다.

확인은 배포 뒤 한 줄이다.

```
curl https://accentury.app/ads.txt
```

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

### 7.1 완주 E2E 실측 (4단계, 2026-09-13)

위 절차는 사람이 눈으로 하는 확인이다. 자동으로 붙들 수 있는 것은 따로 있다 — **광고가 흐름을
바꾸지 않는다**는 사실이고, 그것은 한 판을 끝까지 걸어 봐야 나온다.

`full-run.spec.ts`에 케이스가 하나 늘었다 (「대기 화면 광고 슬롯은 태그 있는 빌드에만 서고,
결과 화면 CTA는 태그 유무와 같다」). 완주 스펙에 단언을 얹지 않고 판을 따로 돌린 이유는 실패를
가르기 위해서다 — 광고가 깨졌을 때 「완주가 깨졌다」로 읽히면 고칠 자리를 잘못 찾는다.
`fullyParallel`이라 두 판은 같이 돈다.

보는 것이 셋이다.

| 단언 | 붙드는 것 |
|---|---|
| 대기 화면의 `complementary`(이름 「광고」) 유무 | 태그 있는 빌드에만 상자가 선다 (§5.1). 이름 「광고」는 접근성 계약이라 클래스가 아니라 역할로 잡는다 |
| 결과 화면 버튼 **2개** — 「친구에게 공유하기」·「다시 테스트하기」 (+ 링크 「앱 다운로드」) | 웹 재응시에 광고가 없고 라벨도 앱과 다르다 (§5). 개수까지 세는 것은 「라벨은 맞는데 버튼이 하나 늘었다」를 잡기 위해서다 |
| 결과 화면에 슬롯 없음 | 슬롯은 대기 화면 하나뿐이다 |

**여기서만 빌드 변수를 읽는다.** 지역 화면은 화면에 뜬 것을 보고 가는데(`testFlow.ts`의
`startTest`), 광고는 **없는 것을 단언해야** 하는 쪽이라 화면만 봐서는 「태그 없는 빌드라 없다」와
「태그 있는 빌드인데 안 섰다」가 갈리지 않는다 — 후자가 이 스펙이 잡아야 할 실패다. 로컬 판에서는
그 값을 스펙이 알 수 있다: Playwright가 `webServer.env`를 부모 환경 위에 얹으므로 셸에 준
`VITE_ADSENSE_*`가 개발 서버에 그대로 닿고 같은 값이 스펙 프로세스에도 있다. `E2E_BASE_URL`로
배포 환경을 겨눌 때는 번들이 이미 굳어 있어 알 길이 없으므로 슬롯 판정을 건너뛰고 CTA만 본다.

두 번 돌린 결과다 (로컬 스택 — DB·가짜 AI·백엔드 `bootRun`).

| 판 | 결과 | 완주 |
|---|---|---|
| 태그 없음 (`npx playwright test`) | 5 passed · 1 skipped (25.1s) | 23429ms (문항 19113ms + 분석 대기 4316ms) |
| 태그 있음 (`VITE_ADSENSE_CLIENT_ID=ca-pub-0000000000000000 VITE_ADSENSE_SLOT_ID=0000000000`) | 5 passed · 1 skipped (23.1s) | 21400ms (문항 18868ms + 분석 대기 2532ms) |

skip 1건은 `retake.spec.ts`다 — `E2E_FAIL_ITEM`이 없는 스택의 정상 동작이고 광고와 무관하다
([`browser-e2e.md`](browser-e2e.md)).

**더미 ID 판에서 Google이 400을 돌려준 것이 그대로 증거가 됐다.** 브라우저 콘솔에
`Failed to load resource: the server responded with a status of 400`이 한 줄 찍혔고, 같은 판이
결과 등급·점수까지 갔다. 승인 전 prod에서 일어날 일이 이것과 같은 모양이다 — 광고가 안 채워지는
것과 슬롯이 안 서는 것은 다른 사건이고, 앞의 것은 흐름을 막지 않는다.

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

4단계(2026-09-13)에서 바뀐 것.

| 파일 | 무엇이 바뀌었나 |
|---|---|
| `.github/workflows/web-deploy.yml` | 빌드 변수 둘 + `ads.txt` 생성 스텝(값 검사 포함)·`no-cache` 업로드·`/ads.txt` 무효화 (§6.1·§6.2) |
| `web/e2e/full-run.spec.ts` | 케이스 1건 — 슬롯 유무와 결과 화면 CTA (§7.1). 헬퍼·스냅샷은 그대로 |
| `infra/README.md` | GitHub 설정 절에 `gh variable set` 두 줄과 등록 시점·staging 제외 근거 |
| `docs/wiki/analytics.md` | 「설정 파일이 없는 것이 정상 상태다」 표에 광고 변수 행 (계측이 아니라는 단서와 함께) |
| `docs/wiki/privacy-policy.md` | §3 게시 게이트 8번 — 남은 것이 코드가 아니라 운영 절차임을 반영 |
| `docs/wiki/ads-web-adsense.md` | 이 문서의 §6.1·§6.2·§7.1·§10 |

**`web/public/ads.txt`는 만들지 않았다.** 계획을 뒤집은 근거는 §6.2다.

## 10. 운영 체크리스트 — 여기부터는 사람이 한다

코드는 끝났고 광고는 아직 안 나온다. 그 사이를 메우는 절차이고, **순서가 있다** — 앞 칸이 닫히기
전에 뒷 칸을 하면 헛돈다 (광고 단위 없이 슬롯 ID를 찾을 수 없고, 변수 없이 배포해 봐야 광고 없는
빌드가 또 나간다).

| # | 할 일 | 끝났다는 신호 | 막히면 |
|---|---|---|---|
| 1 | AdSense 가입 | 계정 대시보드가 열린다 | — |
| 2 | 사이트 `accentury.app` 추가 · 소유권 확인 | 콘솔의 사이트 상태가 「검토 중」으로 바뀐다 | 소유권 확인은 `ads.txt`로 한다. 파일은 워크플로가 만들므로 **3번 변수를 먼저 넣고 Release를 한 번 배포해야** 파일이 생긴다 — 이 한 칸만 순서가 거꾸로 물린다 |
| 3 | 광고 단위(디스플레이) 생성 | 슬롯 ID(숫자)가 나온다 | 게시자 ID는 가입 직후부터 있고(`ca-pub-…`), 슬롯 ID는 광고 단위를 만들어야 생긴다 |
| 4 | `gh variable set` 두 개 (prod) | `gh variable list -e prod`에 둘 다 보인다 | §6.1. staging에는 두지 않는다 |
| 5 | Release 배포 | 워크플로 로그의 `ads.txt 생성` 스텝에 한 줄이 찍힌다 | 값이 `ca-pub-`으로 시작하지 않으면 여기서 실패한다 (§6.2) |
| 6 | `ads.txt` 게시 확인 | `curl https://accentury.app/ads.txt`가 `google.com, pub-…` 한 줄을 준다 | 404면 5번 스텝을 건너뛴 것이고, 내용이 옛것이면 무효화를 보라 |
| 7 | 심사 통과 | 콘솔 사이트 상태가 「준비됨」 | **거절될 수 있다.** SPA라 크롤러가 보는 초기 HTML에 콘텐츠가 거의 없고, 심사는 「콘텐츠가 충분하지 않은 사이트」를 자주 든다. 그때는 광고 배선이 아니라 사이트에 읽을 것을 늘리는 문제다 — 방침·소개 같은 정적 페이지가 후보다 |
| 8 | 실제 요청 확인 | 대기 화면에서 개발자 도구 Network에 `googleads`/`pagead` 요청, 거부한 방문이면 `npa=1` | §7의 절차 그대로. 응답 200인데 슬롯이 비면 아직 승인 전이다 (§1) |

7번이 이 표에서 가장 불확실한 칸이다. 승인은 우리가 통제하지 못하고 기간도 정해져 있지 않으므로,
**방침(10항)이 배선보다 앞서 있는 상태가 그동안 계속된다.** 그 상태를 기록해 둔 자리가
[`privacy-policy.md`](privacy-policy.md) §3의 8번이다 — 거기서 「남은 것」이 비면 이 표도 다 닫힌
것이다.
