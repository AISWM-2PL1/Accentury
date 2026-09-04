# 익명 계측·크래시 리포팅 (KAN-33)

앱·웹이 같은 사건을 같은 이름으로 세고, 그 값이 KAN-24·KAN-21 재검토 트리거를 판단할 계기판이
되게 하는 배선이다. 크래시와 비치명 오류가 심볼화된 스택으로 남는 것까지 이 티켓 범위다.

- 티켓: **KAN-33** (계기판이 받쳐 주는 트리거: KAN-24 대기 시간, KAN-21 등급 분포, FR-SH-06 공유 퍼널)
- 스키마 정본: [`web/src/analytics/events.ts`](../../web/src/analytics/events.ts) — **이름·파라미터·값 표기의 유일한 목록이다**
- 배선: 웹 `web/src/analytics/{track,ga4}.ts` · 안드로이드 `app/src/main/java/com/accentury/app/analytics/` · iOS `ios/Accentury/Analytics/` + `ios/AccenturyCore/Sources/AccenturyCore/Analytics/`
- Firebase 프로젝트: `accentury-c2713` (Android `com.accentury.app` · iOS `com.accentury.app` · 웹 스트림)

## 1. 세 런타임이 한 스키마를 쓴다

같은 사건이 이름이 다른 두 축으로 쌓이면 퍼널이 둘로 갈리고, 그건 대시보드를 보고 나서야
알게 된다. 그래서 이름의 주인을 하나로 못박는다 — `events.ts`의 `AnalyticsEvent` 유니온이다.
안드로이드 `AppEvents.kt`와 iOS `AnalyticsEvents.swift`가 든 상수는 그 목록의 거울이고,
갈리면 컴파일은 조용한 채로 지표만 갈라진다.

| | 이벤트명·파라미터 | 값 표기 |
|---|---|---|
| 이벤트명 | snake_case, 소문자, 40자 이내 | — |
| 파라미터명 | snake_case, 소문자, 40자 이내, 이벤트당 25개 | — |
| 코드값 | — | snake_case (`kakao`, `system_sheet`) |
| 재녹음 사유 | — | **대문자** (`USER`·`QUALITY`·`FAILED`) — 웹 `RetakeReason`이 그렇다 |

### 이벤트 목록

퍼널(사람이 어디서 빠지는가)과 운영 지표(트리거의 계기판)로 나뉜다. `누가 센다`가 곧 그 사건을
아는 유일한 곳이라는 뜻이다.

| 이벤트 | 파라미터 | 누가 센다 | 무엇을 말하는가 |
|---|---|---|---|
| `referral_opened` | `campaign` | 웹 | 인트로를 열었다 |
| `referral_test_started` | `campaign` | 웹 | 세션이 만들어졌다 = 응시 시작 |
| `item_shown` | `item_seq`, `item_type` | 웹 | 문항이 화면에 떴다 |
| `item_submitted` | `item_seq`, `item_type` | 웹 | 그 문항의 답을 제출했다 |
| `recording_retake` | `item_seq`, `reason` | 웹(QUALITY·FAILED) · 앱(USER) | 같은 문항을 다시 녹음했다 |
| `test_completed` | `campaign` | 웹 | 결과가 확정됐다 |
| `result_viewed` | `campaign` | 웹 | 결과 화면을 실제로 봤다 |
| `share_clicked` | `campaign`, `channel` | 웹 | [친구에게 공유하기]를 눌렀다 |
| `share_tapped` | — | 앱 | 같은 탭을 네이티브가 받았다 (아래 «둘을 왜 다 세는가») |
| `share_launched` | `channel` | 앱 | 카톡·공유 시트를 실제로 띄웠다 |
| `app_download_clicked` | `campaign`, `platform` | 웹 | [앱 다운로드] — 웹 단독 실행에만 있는 지점 |
| `retest_started` | — | 웹 | [다시 테스트하기] |
| `analysis_wait_duration` | `duration_ms`, `pending_item_count` | 웹 | **KAN-24 트리거의 측정값** |
| `analysis_poll_count` | `count`, `total_elapsed_ms` | 웹 | 폴링 규칙(KAN-14)이 지켜지는가 |
| `poll_abandoned` | `elapsed_ms`, `pending_item_count` | 웹 | 60초 상한에 걸렸다 (GPU 밀림의 조기 신호) |
| `analysis_item_terminal` | `status`, `error_code` | 웹 | 문항이 종결 상태에 도달했다 |
| `tier_assigned` | `tier_code`, `score_version`, `overall_bucket` | 웹 | **KAN-21 트리거의 측정값** |

**둘을 왜 다 세는가 (`share_clicked` / `share_tapped` / `share_launched`).** 웹이 세는 것은
"눌렀다"이고, 네이티브가 세는 둘은 "네이티브가 그 요청을 받았다"와 "통로가 실제로 열렸다"다.
탭과 실행의 차이가 곧 **눌렀는데 아무 데도 못 간 비율**이고, 한 건으로 뭉치면 그 구멍이 보이지
않는다. `share_launched`는 «보냈다»는 뜻이 아니다 — 카카오 SDK도 OS 공유 시트도 전환 뒤를
돌려주지 않고, 우리 앱은 그 순간 뒤로 내려간다. 실제 전송 수는 카카오 콘솔의 공유 웹훅으로만
알 수 있고 그건 BE 후속 작업이다.

**`recording_retake`의 사유가 갈리는 곳이 다르다.** 서버가 되돌려보낸 재녹음(QUALITY·FAILED)은
사유를 아는 유일한 곳이 웹의 분석 대기 화면이라 거기서 세고, 사용자가 그냥 다시 읽기로 한
`USER`는 네이티브 녹음 화면이 센다. 한쪽만 세면 "재녹음이 많다"까지만 알게 되고, 품질 임계치를
낮춰야 하는지 GPU를 늘려야 하는지는 갈리지 않는다.

## 2. 같은 사건이 두 경로로 가지 않는다

| 실행 | 경로 | 이유 |
|---|---|---|
| 앱 안 (WebView) | 브리지 `logEvent` → 네이티브 Firebase | 앱 이벤트는 앱 스트림에 쌓여야 하고, SDK가 붙여 주는 축(기기·OS·앱 버전·앱 인스턴스)을 WebView가 만들 수 없다 |
| 앱의 네이티브 화면 | 네이티브 창구 직접 | 공유 통로·재녹음은 네이티브만 아는 사건이다 |
| 웹 단독 실행 | gtag → GA4 웹 스트림 | 브라우저에는 브리지가 없다 |
| 계측을 모르는 구버전 앱 | 버림 | 웹 경로로 흘리면 앱 사용자가 웹 트래픽으로 세어진다 |
| 설정이 없는 빌드 | 로그만 | 측정 ID·설정 파일을 모르는 로컬·CI 빌드가 **정상 상태**다 |

분기의 주인은 웹의 `track()` 하나다 (`web/src/analytics/track.ts`). 앱 안에서는 GA4 태그 자체를
설치하지 않으므로(`main.tsx`) 이중 집계의 창이 애초에 닫혀 있고, `track()`의 판정은 그 사실이
다른 파일 사정으로 바뀌어도 규칙이 남게 하는 두 번째 잠금이다.

**앱인지 웹인지는 파라미터로 싣지 않는다.** Firebase가 데이터 스트림으로 나눠 준다 — 같은 GA4
속성 아래 Android·iOS 앱 스트림과 웹 스트림이 따로 있어 «플랫폼» 축이 자동으로 붙는다.
파라미터로 한 번 더 실으면 두 축이 어긋날 여지만 생긴다.

### 브리지 `logEvent` (앱 안 경로)

```
web track(event)
  └─ bridge.logEvent(name, JSON.stringify(params))     web/src/bridge/bridge.ts
  └─ 네이티브 수신
       Android: @JavascriptInterface → postToMain → origin 재검증
       iOS:     postMessage → BridgeDispatcher.handle (메인 스레드, origin 재검증)
  └─ isAnalyticsName(name) && parseEventParams(json)    ← 규격 밖이면 버리고 비치명 보고
  └─ EventSink.log(name, params)                        ← 앱의 유일한 창구
```

메서드 추가는 하위호환이라 **브리지 계약 버전은 1을 유지한다**. 계측을 모르는 구버전 앱은 스큐
게이트를 그대로 통과하고, 웹 래퍼가 `false`로 걸러 이벤트만 조용히 사라진다 — 계측 하나 때문에
응시할 수 있는 앱을 «업데이트 필요»로 막을 이유가 없다.

**숫자는 숫자로 넘어간다.** 이것이 이 티켓 AC가 걸린 자리다. 전부 문자열로 보내면 GA4가 그 값을
측정기준(dimension)으로 잡아 평균·백분위를 낼 수 없고, 그러면 «대기 화면 체류 시간의 평균·P95»가
정의만 있고 계산할 수 없는 값이 된다. 그래서 파서가 JSON 원본의 타입을 살려
정수(`Count`/`count`)와 실수(`Amount`/`amount`)를 갈라 두고, sink가 `putLong`/`NSNumber`로 넘긴다.

**규격 밖 이름은 버린다.** GA4의 이벤트·파라미터 정의는 사후 삭제가 안 돼서, `Item_Shown`이 한 번
흘러가면 `item_shown`과 별개의 축이 영구히 남는다. allowlist를 통과한 페이지만 여기까지 오므로
그런 값이 온다는 것은 곧 **우리 웹과 우리 앱이 계약을 다르게 알고 있다**는 뜻이고, 그래서 조용히
버리되 Crashlytics 비치명 이벤트로 남긴다.

## 3. 익명 규칙

세션 id·세션 토큰·문항 내용·원본 음성·점수 원값은 어떤 파라미터에도 없다. 개인을 특정할 수 있는
값이 하나라도 섞이면 «익명 계측»이라는 전제가 깨지고, 그 값들이 계측 서버에 남을 이유도 없다.

설명이 필요한 값이 셋이다.

- **`campaign`** — 공유 링크가 실어 온 공용 상수(`kko_share` 같은 값)라 사람을 가리키지 않고,
  세션 생성과 같은 규칙으로 걸러진 값만 싣는다 (`web/src/session/campaign.ts`).
- **`tier_code`** — 5개뿐인 집계 축이다. 이것이 없으면 KAN-21의 «등급 분포 편향»을 판단할 근거가 없다.
- **`overall_bucket`** — 종합 점수를 10점 단위로 뭉갠 값 (`overallBucket()`, FR-AN-09). 0~100 정수를
  등급·시각과 함께 보내면 한 사람의 결과를 되짚을 조합이 생기는데, 알아야 하는 것은 «등급 경계
  근처에 얼마나 몰려 있는가»라 10점 눈금이면 충분하다.

### 사용자 식별자와 광고 식별자를 붙이지 않는다

| | 무엇으로 끄는가 |
|---|---|
| 사용자 ID | `setUserId`/`setUserID`를 부르는 코드가 세 런타임 어디에도 없다 |
| 웹 광고 신호 | `gtag('config', …, { allow_google_signals: false, allow_ad_personalization_signals: false })` |
| Android 광고 ID | 매니페스트 `google_analytics_adid_collection_enabled=false` |
| iOS 광고 ID(IDFA) | **SwiftPM product를 `FirebaseAnalyticsCore`로 고른다** (`ios/project.yml`) |

iOS만 방식이 다른 이유: IDFA 수집은 설정 플래그가 아니라 **무엇을 링크했는가**로 갈린다. 기본
`FirebaseAnalytics` product는 IDFA를 수집하는 `GoogleAppMeasurement`를 물고 오고, `…Core`는
`GoogleAppMeasurementCore`를 물어 AdSupport·AppTrackingTransparency가 바이너리에 아예 들어오지
않는다. 켤 수 있는 코드가 없는 편이 플래그보다 강하다. 확인은 빌드 산출물에 직접 물으면 된다:

```bash
otool -L Accentury.app/Frameworks/GoogleAppMeasurement.framework/GoogleAppMeasurement | grep -i adsupport
nm -u Accentury.app/Frameworks/GoogleAppMeasurement.framework/GoogleAppMeasurement | grep -c ASIdentifierManager
# 둘 다 0건이어야 한다 (2026-09-05 확인)
```

`Info-{Debug,Release}.plist`의 `GOOGLE_ANALYTICS_DEFAULT_ALLOW_AD_*` 세 줄은 이미 꺼진 것을 한 번
더 끄는 셈인데, product를 되돌리는 변경이 그 파일을 보지 않고도 광고 신호를 켜지 못하게 한다.

### 크래시 리포트에도 같은 규칙이 선다

Crashlytics 커스텀 키·로그·비치명 메시지에 들어가는 값은 **코드에 박아 둔 고정 문자열뿐이다** —
브리지 메서드 이름과 우리가 쓴 녹음 실패 사유. 세션 토큰·오디오 바이트·임시 파일 경로를 넘기는
경로는 만들지 않는다 (KAN-38 로그 마스킹 원칙). 커스텀 키는 `bridge_contract_version` 하나이고
비식별 상수다.

## 4. 비치명 오류를 남기는 두 자리

크래시가 아니라 **조용히 실패하는 자리**를 남긴다. 우리 앱의 실패 경로는 대부분 화면에 보이지
않는다 — 브리지가 못 읽는 메시지는 그냥 버려지고, 마이크가 안 열리면 «다시 시도»만 뜬다. 사람이
봐야 고칠 수 있는 사실인데 크래시가 아니니 크래시 리포트에는 영영 안 나타난다.

| 도메인 | 언제 | 어디서 |
|---|---|---|
| `bridge_parse_failed` | `startVoiceItem`·`shareResult`·`logEvent` payload를 읽지 못했다 | Android `web/AccenturyBridge.kt` · iOS `Web/AccenturyBridge.swift` |
| `audio_capture_failed` | 마이크를 열지 못했거나 캡처가 도중에 끊겼다 | Android `audio/RecordingEngine.kt` · iOS `Recording/RecordingModel.swift` |

iOS의 캡처 실패를 `AudioRecorder`의 시작 실패 catch가 아니라 `RecordingModel`에 붙인 이유:
**모든 캡처 실패가 지나는 길목이 거기다.** 세션 활성화 거부·변환기 생성 실패·전화 인터럽션이
전부 `.failed`로 도착하고, 시작 단계에만 붙이면 녹음 도중 끊긴 실패가 빠져 분포가 실제보다
시작 실패 쪽으로 기울어 보인다.

## 5. 설정 파일이 없는 것이 정상 상태다

이 레포의 규칙이다 (`app/build.gradle.kts`의 `kakaoNativeAppKey()` 주석과 같은 판단). 갓 클론한
기계와 PR CI에는 설정이 없고, 그 상태로도 빌드·테스트·실행이 전부 되어야 한다.

| | 파일 | 없을 때 |
|---|---|---|
| Android | `app/google-services.json` | 두 플러그인(google-services·crashlytics)을 **적용하지 않는다** — 없으면 설정 단계에서 빌드를 죽이기 때문. 의존성은 조건 없이 붙는다 |
| iOS | `ios/Accentury/GoogleService-Info.plist` | `FirebaseApp.configure()`를 부르지 않는다 — 파일이 없으면 그 호출이 앱을 죽인다. 의존성은 조건 없이 붙는다 |
| 웹 | `VITE_GA4_MEASUREMENT_ID` | 태그를 설치하지 않는다 |

**의존성까지 조건부로 하지 않는 이유가 같다**: 설정 유무에 따라 컴파일·링크되는 것이 갈리면,
설정 없는 CI가 검증한 코드와 스토어로 나가는 코드가 달라진다.

판정 지점은 런타임마다 **하나**다. Android는 `EventSink.create`, iOS는 `FirebaseSetup`이고,
화면은 자기가 어느 sink를 받았는지 모른다. 「설정이 없다」를 화면마다 다시 물으면 어떤 화면은
묻는 것을 잊고, 그 화면만 초기화되지 않은 SDK를 부르게 된다.

iOS의 XcodeGen 배선도 같은 규칙 위에 선다: `GoogleService-Info.plist`를 `project.yml`에 경로로
적지 않는다. 폴더 스캔이 이미 리소스로 잡아 가고, **경로를 명시하면 파일이 없을 때
«Build input file cannot be found»로 프로젝트 생성 다음 단계가 죽는다.**

## 6. Firebase·GA4 콘솔 설정 절차

### 6-1. 앱 등록과 설정 파일

1. [Firebase 콘솔](https://console.firebase.google.com/) → 프로젝트 `accentury-c2713`
2. **Android**: 프로젝트 설정 → 내 앱 → Android 앱 `com.accentury.app` → `google-services.json` 내려받아 `app/`에 둔다
3. **iOS**: 같은 화면 → iOS 앱, 번들 ID `com.accentury.app` → `GoogleService-Info.plist` 내려받아 `ios/Accentury/`에 둔다
4. **웹**: 이 프로젝트에 연결된 GA4 속성 → 관리 → 데이터 스트림 → 웹 스트림(없으면 새로 만든다)의
   측정 ID(`G-…`)를 웹 빌드 환경변수 `VITE_GA4_MEASUREMENT_ID`로 넣는다. 레포에 박지 않는다 —
   페이지 소스에 실리는 공개 값이라 비밀은 아니지만, 박아 두면 개발·테스트 트래픽이 실사용 집계에 섞인다

두 설정 파일은 **커밋 대상이다**. 카카오 앱 키와 갈리는 지점인데, 저 값은 남이 우리 앱 키로
카카오 API를 두드릴 수 있지만 이 파일들의 앱 id·API 키는 패키지명·번들 id와 서명에 묶여 있어
다른 앱이 가져다 쓸 수 없다.

### 6-2. Crashlytics 켜기

Crashlytics는 **첫 리포트가 도착해야** 콘솔 대시보드가 열린다. 앱을 한 번 실행하고 강제로 크래시를
낸 뒤 다시 실행하면(리포트는 다음 실행에 올라간다) 목록이 생긴다.

- **iOS dSYM**: 업로드는 빌드 단계가 한다 (`ios/project.yml`의 «Crashlytics dSYM 업로드»).
  Release·아카이브만 dSYM을 만들므로(Debug는 `DEBUG_INFORMATION_FORMAT=dwarf`) Debug 빌드에서는
  «Unable to process …dSYM» 경고 한 줄이 나오고 지나가는 것이 정상이다.
  아카이브 뒤 콘솔 → Crashlytics → dSYM 상태에서 «누락된 dSYM 없음»을 확인한다.
- **Android**: mapping 파일 업로드는 crashlytics 플러그인이 릴리스 빌드에서 자동으로 한다.

### 6-3. GA4 맞춤 정의 등록 — **이 절차 없이는 대시보드에 값이 안 보인다**

GA4는 커스텀 이벤트 파라미터를 **등록하기 전까지 보고서에 노출하지 않는다.** 등록 시점 이후
데이터부터 채워지므로(소급 적용 없음) 계측을 켜는 것과 같은 날 해 두는 것이 맞다.

GA4 → 관리(⚙) → 데이터 표시 → **맞춤 정의**

**맞춤 측정항목**(숫자, 범위=이벤트) — 평균·합계를 낼 수 있게 된다.

| 매개변수 | 측정 단위 |
|---|---|
| `duration_ms` | 밀리초 |
| `total_elapsed_ms` | 밀리초 |
| `elapsed_ms` | 밀리초 |
| `pending_item_count` | 표준 |
| `count` | 표준 |
| `item_seq` | 표준 |
| `overall_bucket` | 표준 |

**맞춤 측정기준**(범위=이벤트) — 분포를 가를 축이 된다.

`tier_code` · `score_version` · `campaign` · `channel` · `reason` · `item_type` · `status` · `error_code` · `platform`

> 속성당 맞춤 측정기준 50개·측정항목 50개가 상한이다. 지금 목록은 그 절반도 쓰지 않는다.

### 6-4. KAN-24 트리거 — 대기 화면 체류 시간

트리거 문구는 «대기 화면 평균 체류 10초 초과»이고, 측정값은 `analysis_wait_duration`의
`duration_ms`다.

**평균**은 GA4에서 바로 본다. 탐색 → 자유 형식 → 측정기준에 «이벤트 이름», 측정항목에 위에서
등록한 `duration_ms`(평균)를 놓고 `analysis_wait_duration`으로 필터한다. `pending_item_count`를
측정기준 축으로 얹으면 «남은 문항이 몇 개일 때 오래 기다리는가»까지 한 표에서 갈린다.

**P95는 GA4 UI에서 나오지 않는다.** GA4의 집계에는 분위수가 없다(합계·평균·최소·최대). 두 갈래다.

1. **BigQuery Export** — GA4 → 관리 → BigQuery 링크. 무료 등급은 일 1회 배치라 실시간은 아니다.
   ```sql
   SELECT
     APPROX_QUANTILES(
       (SELECT value.int_value FROM UNNEST(event_params) WHERE key = 'duration_ms'), 100
     )[OFFSET(95)] AS p95_ms
   FROM `accentury-c2713.analytics_<속성ID>.events_*`
   WHERE event_name = 'analysis_wait_duration'
     AND _TABLE_SUFFIX BETWEEN '20260901' AND '20260930'
   ```
2. **구간을 파라미터로 하나 더 싣기** — 대기 시간을 구간(예: 0-5s·5-10s·10-20s·20s+)으로 묶은
   맞춤 측정기준을 추가하면 GA4 UI에서 분포로 읽을 수 있다. 스키마 변경이라 `events.ts`부터
   고쳐야 하고, 지금은 하지 않았다. BigQuery를 붙이지 않기로 하면 이쪽이 후속 작업이다.

**그래서 이 AC는 콘솔에서 닫힌다.** 맞춤 정의 등록과 P95 경로 선택(BigQuery 링크 또는 구간
파라미터 추가)이 끝나야 «대시보드에서 바로 확인»이 성립한다.

### 6-5. KAN-21 트리거 — 등급 분포 편향

트리거 문구는 «한 등급이 40%를 넘으면 점수 경계값 재보정»이고, 측정값은 `tier_assigned`다.

탐색 → 자유 형식 → 행에 `tier_code`, 값에 «이벤트 수», 필터 `event_name = tier_assigned`.
비율은 셀 유형을 «행 대비 비율»로 바꾸면 그대로 보인다.

`score_version`을 열(column)로 놓는 것이 이 표의 요점이다 — 경계값을 재보정하면 분포가 달라지는데,
버전 축이 없으면 보정 전후가 한 덩어리로 섞여 «편향이 줄었는지»를 판단할 수 없다.
`overall_bucket`을 행으로 바꾸면 등급 경계 근처에 얼마나 몰려 있는지가 10점 눈금으로 나온다 —
경계값을 어디로 옮길지는 그 표가 말해 준다.

## 7. 확인하는 법

### DebugView (실시간)

이벤트가 실제로 나가는지는 GA4 DebugView가 유일하게 즉시 보여준다. 일반 보고서는 24시간 넘게 걸린다.

```bash
# Android
adb shell setprop debug.firebase.analytics.app com.accentury.app
# 끄기: adb shell setprop debug.firebase.analytics.app .none.

# iOS — 실행 인자로 켠다 (Xcode의 Edit Scheme › Arguments, 또는)
xcrun simctl launch --console-pty booted com.accentury.app -FIRDebugEnabled
```

GA4 → 관리 → DebugView에서 기기를 고르면 이벤트가 파라미터째 흐른다. 여기서 봐야 하는 것은
**이름이 아니라 파라미터의 타입이다** — `duration_ms`가 문자열로 찍히면 평균·P95가 나오지 않는다.

### 설정 없이 도는 빌드

설정 파일이 없으면 이벤트는 로그로만 남는다. 개발에서 퍼널이 실제로 도는지 눈으로 확인할 유일한
통로이기도 하다.

```bash
adb logcat -s AppEvents                                  # Android
xcrun simctl spawn booted log stream --predicate 'subsystem == "com.accentury.app" AND category == "analytics"'
```

웹은 개발 빌드에서 `console.debug('[track]', event)`가 같은 자리다.

### 자동 검증

| 무엇 | 어디 |
|---|---|
| 이름 규칙·타입 보존·상한 | iOS `AccenturyCoreTests/Analytics/EventParamsTests` · Android `EventParamsTest` |
| 브리지 라우팅·origin 게이팅 | iOS `AccenturyTests/AccenturyBridgeTests` · Android `AccenturyBridgeTest` |
| 전송 경로 분기(이중 집계 방지) | `web/src/analytics/track.test.ts` |
| 태그 설치·광고 신호 차단 | `web/src/analytics/ga4.test.ts` |

## 8. 스토어 데이터 고지에 반영할 것 (KAN-174·KAN-175 앞으로)

Crashlytics가 붙으면서 **수집 항목이 늘었다.** 두 스토어의 고지 양식에 이 사실이 들어가야 하고,
빠뜨리면 심사에서 걸리거나(애플) 사후 정책 위반이 된다(구글).

| | 무엇을 적어야 하는가 |
|---|---|
| Play 데이터 안전 (KAN-174) | **앱 활동**(앱 상호작용) 수집·전송, **앱 정보 및 성능** 중 «비정상 종료 로그»·«진단» 수집·전송. 전송 중 암호화됨, 사용자가 삭제 요청 가능 여부는 정책 확인 필요 |
| App Store 개인정보 라벨 (KAN-175) | **사용 데이터 › 제품 상호작용**, **진단 › 비정상 종료 데이터·성능 데이터**. 세 항목 모두 «사용자와 연결되지 않음(Not Linked to You)» — `setUserID`를 부르지 않고 IDFA를 링크하지 않았다 |

**«추적(Tracking)»에는 해당하지 않는다.** IDFA를 수집하지 않고 광고 신호를 껐으므로 App Tracking
Transparency 프롬프트도 필요 없다 — 위 §3의 `otool`·`nm` 확인이 그 근거다. `FirebaseAnalytics`
product로 되돌리면 이 판단이 통째로 뒤집히므로, 그 변경은 이 절과 함께 다시 읽어야 한다.

Firebase가 제공하는 SDK 개인정보 매니페스트(`PrivacyInfo.xcprivacy`)는 각 패키지 번들에 들어 있고
Xcode가 앱 개인정보 보고서에 합쳐 준다 — 라벨 작성 때 그 보고서(Xcode › Product › Archive ›
Generate Privacy Report)를 근거로 쓰면 항목을 빠뜨리지 않는다.

## 9. 아직 안 한 것

- **P95 경로 선택** — BigQuery Export 링크 또는 구간 파라미터 추가 (§6-4). 둘 중 하나가 서야 KAN-24 AC가 닫힌다.
- **맞춤 정의 등록** — 콘솔 작업이라 코드로 못박을 수 없다 (§6-3). 등록 이전 데이터는 소급되지 않는다.
- **공유 전송 완료 수** — 카카오 공유 웹훅(서버 콜백)이 있어야 알 수 있다. BE 후속.
- **iOS 실기기 크래시 확인** — 심볼화된 스택은 아카이브 → dSYM 업로드 → 실기기 크래시까지 가야 눈으로 볼 수 있다. 시뮬레이터 빌드까지가 이 단계의 검증 범위다.
