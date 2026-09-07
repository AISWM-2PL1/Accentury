# 개인정보처리방침 근거 장부 (KAN-176)

`infra/privacy/privacy.html`에 적힌 문장 하나하나가 **어떤 코드·티켓·결정을 근거로 삼았는지**를
드는 장부다. 방침 본문이 정본이고 이 문서는 그 뒤를 받친다 — 왜 그렇게 썼는가, 그리고 코드가
바뀌면 본문의 어디를 고쳐야 하는가.

이 장부가 따로 필요한 이유는 문서가 코드보다 오래 살기 때문이다. "분석이 끝나면 즉시 삭제한다",
"14일 보관한다" 같은 문장은 우리가 코드로 지키고 있는 약속인데, 그 코드를 고치는 사람에게는
방침이 보이지 않는다. 계약 테스트(`infra/privacy/privacy.test.mjs`)가 핵심 수치를 붙들어 1차
경보를 울리고, 테스트가 못 붙드는 나머지 문장은 이 표가 맡는다.

- 티켓: **KAN-176** (방침 본문 작성)
- 본문 정본: [`infra/privacy/privacy.html`](../../infra/privacy/privacy.html) — 14개 절
- 계약 테스트: [`infra/privacy/privacy.test.mjs`](../../infra/privacy/privacy.test.mjs) 13건, CI `edge-test` 잡에 결선 (`.github/workflows/test.yml`)
- 호스팅과 게시 경로: **KAN-133** — `infra/privacy/README.md`, `scripts/publish-privacy.sh`
- 앱 안 링크: **KAN-177** · 스토어 등록: **KAN-174**(Play) **KAN-175**(App Store)
- 광고 도입: **KAN-196**(앱 SDK·동의 UI·ATT·스토어 신고) **KAN-197**(웹 광고)

## 1. 절별 근거 매핑

본문 절 번호 순이다. 「계약 테스트」 열은 `privacy.test.mjs`의 테스트 이름이고, 비어 있으면
그 문장은 테스트가 아니라 이 표만으로 지켜진다.

### 1항 — 처리 목적·항목·보유 기간

표의 행 단위로 쪼갠다.

| 표의 행 | 핵심 주장 | 근거 | 계약 테스트 |
|---|---|---|---|
| 음성 녹음 | 분석이 끝나면 즉시 삭제 | `ai/app/tempstore.py:10-14` (컨텍스트 매니저가 성공·실패·예외·취소 모두 `finally`에서 삭제), KAN-27 | `음성은 분석 직후 즉시 삭제라고 적혀 있다`·`1항 표의 보유 기간이 행마다 코드와 맞는다` |
| 음성 녹음 | 임시 파일 청소 기준 30분 | `ai/app/config.py:18`, `backend/src/main/resources/application.yml:141`, `ai/app/tempstore.py:13,169-177` | `임시 파일 청소 기준 30분이 적혀 있다` |
| 음성 녹음 | 기기에도 파일로 남기지 않음 | `app/src/main/java/com/accentury/app/audio/WavWriter.kt:14-15` (업로드는 `toWavBytes`만 쓴다), `ios/AccenturyTests/RecordingFileLifecycleTests.swift:20` (`testAFullRecordEnqueueDiscardCycleLeavesNoWavOnDisk`) | — |
| 음성 녹음 | DB·S3에 저장 안 함 | 2026-09-01 팀 회의 결정 (`infra/privacy/README.md`에 기록), 엔티티에 오디오 컬럼 없음 (`backend/src/main/java/app/accentury/backend/session/TestSession.java`, `backend/src/main/java/app/accentury/backend/result/TestResult.java`, `backend/src/main/java/app/accentury/backend/vocab/VocabAnswer.java`) | — |
| 익명 테스트 세션 | 토큰은 해시값만 저장 | `backend/src/main/java/app/accentury/backend/session/TestSession.java:36,45-46`, KAN-9 | — |
| 익명 테스트 세션 | 세션의 처음 수명은 30분 | `backend/src/main/resources/application.yml:102` (`session.ttl: 30m`), `backend/src/main/java/app/accentury/backend/session/SessionService.java:132` (`expiresAt = now + session.ttl`) | `1항 표의 보유 기간이 행마다 코드와 맞는다` |
| 익명 테스트 세션 | 미완주 세션은 만든 지 30분 뒤 만료 정리 | `backend/src/main/java/app/accentury/backend/session/SessionService.java:132,365-372` (`purgeExpired`가 `deleteByExpiresAtBefore`, 10분 주기), `backend/src/main/resources/db/migration/V1__baseline.sql:61,78,98` (`on delete cascade` — 어휘 답안·분석 작업·결과가 세션 행과 함께 지워진다) | 위와 같음 |
| 익명 테스트 세션 | 완주 세션은 **완료 시점부터** 24시간 뒤 삭제 | `backend/src/main/resources/application.yml:112` (`analysis.retention: 24h`), `backend/src/main/java/app/accentury/backend/result/CompletionService.java:169-178` (`resultExpiresAt = 완료 시각 + retention` → `markCompleted`), `backend/src/main/java/app/accentury/backend/session/TestSession.java:140-157` (완료 시 세션 만료를 결과 만료로 다시 잡는다 — 토큰 수명도 함께 24시간이 된다) | 위와 같음 + `세션 보유 기간의 기준점이 「생성 시점」으로 되돌아가지 않았다` |
| 테스트 결과·어휘 답안 | 완료 시점부터 24시간 | `backend/src/main/java/app/accentury/backend/result/TestResultRetention.java:22,33-40` (`deleteByExpiresAtBefore` 60분 주기), `backend/src/main/java/app/accentury/backend/result/CompletionService.java:170-175` (`expiresAt`을 저장 시점에 확정), KAN-25·KAN-15 | 위와 같음 |
| 테스트 결과·어휘 답안 | 미완주 세션의 것은 세션과 함께 삭제 | `backend/src/main/resources/db/migration/V1__baseline.sql:61,78,98` (CASCADE), `backend/src/main/java/app/accentury/backend/session/SessionService.java:355-372` (`purgeExpired` 주석이 "하위 3테이블도 함께 지운다"를 명시) | 위와 같음 |
| 익명 통계 | 합계값만, 세션·IP·개인 점수 없음 | `backend/src/main/java/app/accentury/backend/analytics/DailyCounter.java:33`, `backend/src/main/java/app/accentury/backend/analytics/AnalyticsCounters.java:45`, `backend/src/main/java/app/accentury/backend/result/CompletionService.java:132`, KAN-106 | — |
| 익명 통계 | 재응시·만료로 되돌리지 않음 | `backend/src/main/java/app/accentury/backend/analytics/DailyCounter.java:33`, `backend/src/main/java/app/accentury/backend/analytics/AnalyticsCounters.java:45` (둘 다 "되돌리지 않는다"를 명시) | — |
| 접속 IP | 메모리에서만 세고 저장 안 함 | `backend/src/main/java/app/accentury/backend/common/FixedWindowRateLimiter.java:9,12,20` (`ConcurrentHashMap` 인메모리 윈도우), `backend/src/main/java/app/accentury/backend/common/ClientIps.java:52-58` (판정만 하고 반환) | — |
| 서버 운영 로그 | 14일 | `infra/modules/fargate/variables.tf:173-176` (`log_retention_days` 기본값 14) | `1항 표의 보유 기간이 행마다 코드와 맞는다` |
| 보안 로그 | 7일, 인증 헤더는 가림 | `infra/modules/waf/variables.tf:21-24` (기본값 7), `infra/modules/waf/main.tf:286,313,319` (샘플 저장 끔 + `redacted_fields`) | `1항 표의 보유 기간이 행마다 코드와 맞는다` |
| 이용 통계 이벤트 | 보존은 GA 설정에 따름 (기본값 2개월) | **미확인** — 콘솔 기본값이고 레포에 근거가 없다 (7항 참조) | — |
| 비정상 종료 로그 | 90일 | **미확인** — Crashlytics 콘솔 기본값이고 레포에 근거가 없다 (7항 참조). 수집 항목 자체는 KAN-33, `docs/wiki/analytics.md` §8 | `1항 표의 보유 기간이 행마다 코드와 맞는다` (표기가 90일인지만 붙든다) |
| 광고 | 사업자 방침에 따름 | 2026-09-07 팀 결정, KAN-196·KAN-197. **아직 배선 없음** | `맞춤형 광고 절이 있고 동의·거부 방법이 적혀 있다` |

같은 항의 산문 부분은 위 행들의 풀이다. 「재응시하면 이전 세션과 결과를 즉시 삭제」는
`backend/src/main/java/app/accentury/backend/session/SessionService.java:28,87-88,201-203,245-247`과
`backend/src/main/java/app/accentury/backend/result/TestResultRepository.java:28`,
`backend/src/main/java/app/accentury/backend/vocab/VocabAnswerRepository.java:32`,
`backend/src/main/java/app/accentury/backend/analysis/AnalysisJobRepository.java:98`이 근거이며 KAN-107 소관이다.
「캠페인 코드 64자 이하」는 `backend/src/main/java/app/accentury/backend/session/CreateSessionRequest.java`가 붙든다.

### 2~14항

| 절 | 핵심 주장 | 근거 | 계약 테스트 |
|---|---|---|---|
| 2. 제3자 제공 | 광고 사업자 제공은 제3자 제공에 해당 | 광고 SDK가 자기 목적으로 처리하는 구조 — 2026-09-07 팀 결정, KAN-196 | — |
| 2. 제3자 제공 | 제공받는 자는 **확정 후 기재** (286행) | 사업자 미정. prod 게이트 (4항 참조) | — |
| 2. 제3자 제공 | 위탁·공유는 제3자 제공이 아님 | 3항(위탁)과 9항(이용자 선택 공유)의 구분 | — |
| 3. 위탁 | AWS 서울 리전(ap-northeast-2) | `infra/envs/prod/terraform.tfvars:3`, `infra/envs/staging/terraform.tfvars:3` | `데이터 소재지가 서울 리전이라고 적혀 있다` |
| 3. 위탁 | Google LLC(Firebase Analytics·Crashlytics) | KAN-33, `docs/wiki/analytics.md` | — |
| 4. 국외 이전 | 이전 대상은 이용 통계와 오류 로그뿐 | 음성·세션·결과는 서울 리전 (`infra/envs/prod/terraform.tfvars:3`, `infra/envs/staging/terraform.tfvars:3`), Firebase만 국외 | `데이터 소재지가 서울 리전이라고 적혀 있다` |
| 4. 국외 이전 | 광고 사업자 국외 이전은 **확정 후 기재** (353~355행) | 사업자 미정. prod 게이트 | — |
| 4. 이용 통계 이벤트 | 수집 항목 목록 | `web/src/analytics/events.ts:71` (`AnalyticsEvent` 유니온이 이름·파라미터의 정본), `docs/wiki/analytics.md` §1 | — |
| 4. 이용 통계 이벤트 | 응시 구분 무작위 키는 탭을 닫으면 사라짐 | `web/src/analytics/testId.ts:21-23,93` (`sessionStorage`) | — |
| 4. 이용 통계 이벤트 | 세션 id·토큰·문항·점수 원값을 싣지 않음 | `docs/wiki/analytics.md` §3 「익명 규칙」, `web/src/analytics/events.ts` | — |
| 4. 이용 통계 이벤트 | 광고 식별자를 쓰지 않음 (Android·iOS·웹 각각) | `app/src/main/AndroidManifest.xml:43-49` (`google_analytics_adid_collection_enabled=false`, 개인화 신호 false), `ios/Accentury/Analytics/FirebaseEventSink.swift:23-32` (`GoogleAppMeasurementCore`로 AdSupport·ATT를 바이너리에서 배제), `web/src/analytics/ga4.ts:75-81` (`allow_google_signals:false`, `allow_ad_personalization_signals:false`) | — |
| 4. 비정상 종료 로그 | 스택·기기·OS·앱 버전, 사용자 ID 없음 | KAN-33, `docs/wiki/analytics.md` §3 「크래시 리포트에도 같은 규칙이 선다」 | — |
| 5. 파기 | 파기 시점 다섯 가지 | 1항의 근거를 그대로 반복한다 (음성 즉시·30분, 미완주 세션 30분, 완주 세션 완료 후 24시간, 재응시 즉시, 로그 14일/7일) | `음성은 분석 직후…`·`임시 파일 청소 기준 30분…`·`세션·결과 보유 기간 24시간…` |
| 6. 정보주체 권리 | 특정 이용자의 정보를 지목할 수단이 없음 | 계정 없음, 세션은 익명 (`backend/src/main/java/app/accentury/backend/session/TestSession.java` — 사람을 가리키는 컬럼 없음) | — |
| 6. 정보주체 권리 | 탭을 닫으면 세션 토큰·응시 키는 사라지지만 **진행 기록은 남는다** | `web/src/session/webSession.ts`·`web/src/analytics/testId.ts:21-23` (`sessionStorage`) 대 `web/src/progress/progressSnapshot.ts:34,51` (`localStorage`, 키 `accentury:progress:<sessionId>`). 앱 코드에 `clearSnapshot` 호출점이 없어(`web/src/progress/useTestProgress.ts:14` 주석이 "삭제 시점은 결과 화면"이라 적었지만 아직 배선 없음) 진행 기록은 사이트 데이터 삭제·앱 삭제로만 지워진다 | — |
| 7. 만 14세 미만 | 아동 대상 아님, 마켓에도 그렇게 등록 | `docs/wiki/play-store-listing.md` §6 (타겟 연령 13세 이상) — **KAN-174 브랜치에만 있는 파일** | — |
| 7. 만 14세 미만 | 아동에게 맞춤형 광고 미표시 | 2026-09-07 팀 결정, KAN-196에서 SDK 설정으로 구현 예정 | — |
| 8. 자동 수집 장치 | 웹에는 GA4 태그가 쿠키를 저장 | `web/src/analytics/ga4.ts:75-81` — `config`에 쿠키를 끄는 옵션이 없다(기본 동작이 쿠키 설정) | — |
| 8. 자동 수집 장치 | 앱 WebView에는 태그를 깔지 않음 | `web/src/main.tsx:20` — `isStandaloneWeb`일 때만 `installGa4Tag()` | — |
| 8. 자동 수집 장치 | 진행 기록은 브라우저 저장소에 남고 서비스가 지우지 않음 | `web/src/progress/progressSnapshot.ts:34,51` (키 `accentury:progress:<sessionId>`), `web/src/progress/useTestProgress.ts:61-65` (`window.localStorage`), `web/src/progress/useTestProgress.ts:14` (`clearSnapshot`을 부르지 않는다 — 호출점이 아직 없다) | — |
| 8. 자동 수집 장치 | 세션 토큰·응시 키는 탭 저장소 | `web/src/session/webSession.ts`, `web/src/analytics/testId.ts:21-23` (`sessionStorage`) | — |
| 8. 자동 수집 장치 | 광고 SDK가 광고 식별자를 사용 | 2026-09-07 팀 결정, KAN-196. **아직 배선 없음** | — |
| 9. 공유 기능 | payload에 점수·세션·음성이 없음 | `app/src/main/java/com/accentury/app/bridge/SharePayload.kt:28-31` (필드는 `imageUrl`·`text`·`webTestUrl` 셋), `web/src/share/shareResult.ts:57-59` (payload 필드가 `imageUrl`·`text`·`webTestUrl` 셋뿐, KAN-30 요구) | — |
| 9. 공유 기능 | 링크를 받은 사람은 자기 테스트를 시작 | `docs/wiki/app-links.md` §1 — 링크가 읽는 쿼리는 `c` 하나뿐 | — |
| 10. 광고 | 광고 절이 존재하고 동의·거부 경로가 있음 | 2026-09-07 팀 결정, KAN-196·KAN-197 | `맞춤형 광고 절이 있고 동의·거부 방법이 적혀 있다` |
| 10. 광고 | 「광고와 추적이 없습니다」는 이제 거짓 | 같은 결정으로 삭제한 문장. 되살아나는 것을 테스트가 막는다 | `"광고와 추적이 없습니다"가 남아 있지 않다` |
| 10. 광고 | 사업자·동의 UI 위치·국외 이전은 **확정 후 기재** (483·514·523~525행) | 사업자 미정. prod 게이트 | — |
| 11. 안전성 확보 | HTTPS, 토큰 해시, 임시 파일 최소 권한, 로그 비식별, WAF, 관리자 토큰 | `backend/src/main/java/app/accentury/backend/session/TestSession.java:45-46`, `ai/app/tempstore.py:6-16`, `infra/modules/waf/main.tf:313,319`, `backend/src/main/java/app/accentury/backend/common/AccenturyProperties.java:29` (admin 시크릿) | — |
| 12. 동의 방식 | 동의 화면을 따로 두지 않되 광고는 별도 동의 | KAN-2 「동의 화면 범위 제외」 결정 + 2026-09-07 광고 결정의 부분 번복 (6항 참조) | — |
| 13. 보호책임자 | 이성주, team2pl1@gmail.com | 2026-09-07 팀 결정 | `연락처가 있다 (Play·App Store 심사가 요구하는 항목)` |
| 14. 시행일 | 정식 게시일에 기재 | prod 게이트 (102·571행) | — |
| 전 절 | 법정 필수 절이 빠지지 않음 | 「개인정보 보호법」 제30조 + 실제 처리(국외 이전, 자동 수집 장치, 광고) | `법정 필수 절이 모두 있다` |
| 페이지 전체 | 외부 CSS·글꼴·스크립트 0 | KAN-133 AC "본문 교체는 S3 업로드 하나" | `외부 자원을 하나도 쓰지 않는다` |
| 페이지 전체 | noindex 없음, 자리표시자 문구 없음 | KAN-133 → KAN-176 인계 | `자리표시자를 막던 noindex가 없다`·`자리표시자 문구가 남아 있지 않다` |

## 2. 스토어 신고 대조표

Play 데이터 안전 답안의 정본은 `docs/wiki/play-store-listing.md` §5다 (**KAN-174 브랜치에만 있고
Dev에는 아직 없다**). 그 문서는 광고 도입 결정 이전에 쓰였으므로 지금 답안과 방침 본문 사이에
어긋나는 자리가 넷 있다. **고치는 것은 KAN-196 소관이고, 이 티켓에서는 건드리지 않는다.**

| Play 데이터 안전 질문 | 지금 KAN-174 답 | 방침 본문의 대응 | 광고 도입 후 바뀔 답 |
|---|---|---|---|
| 데이터를 수집·공유하나 | 예 | 1항 표 전체 | 그대로 예 |
| 제3자와 공유하나 | **아니요** ("광고 네트워크로 보내는 곳도 없다") | 2항 제3자 제공 표 — 광고 사업자 1행 | **예**. 제공 항목은 광고 식별자·IP·기기 정보·노출/클릭 기록 |
| 광고 ID 수집 | **수집하지 않음** (`google_analytics_adid_collection_enabled=false`) | 8항·10항 — 광고 SDK가 광고 식별자를 사용 | **수집함**. 목적은 「광고 또는 마케팅」. Analytics 쪽 false는 그대로 두고 광고 SDK 몫을 따로 신고 |
| 광고 포함 (§6 콘텐츠 등급) | **아니요** | 10항 「서비스에는 광고가 표시됩니다」 | **예**. 콘텐츠 등급 설문도 다시 제출 |
| 음성 또는 사운드 녹음 | 수집됨, 「일시적으로만 처리되며 저장되지 않음」 | 1항 음성 행 + 「음성 녹음」 산문 | 변화 없음 |
| 앱 상호작용 | 수집됨, 목적 분석 | 1항 이용 통계 이벤트 행, 4항 | 변화 없음 |
| 비정상 종료 로그 | 수집됨, 목적 분석 | 1항 비정상 종료 로그 행, 4항 | 변화 없음 |
| 삭제 요청 방법 제공 | 아니요 | 6항 — 지목할 수단이 없음 | 변화 없음 |

App Store(KAN-175)도 같은 이유로 두 줄이 바뀐다.

| App Store 개인정보 라벨 | 지금 기준 | 광고 도입 후 |
|---|---|---|
| 「추적(Tracking)」 해당 여부 | 해당 없음 — IDFA 미수집, ATT 프롬프트 불필요 (`docs/wiki/analytics.md` §8) | **해당함**. ATT 프롬프트 필요, 「사용자를 추적하는 데 사용되는 데이터」에 식별자·사용 데이터 신고 |
| 식별자 › 기기 ID | 신고 없음 | **신고함** (광고 SDK의 IDFA — 이용자가 허용한 경우) |
| 사용 데이터 › 제품 상호작용 | 「사용자와 연결되지 않음」 | 광고 SDK 몫은 「추적에 사용됨」으로 별도 표기 |
| 진단 › 비정상 종료·성능 | 「사용자와 연결되지 않음」 | 변화 없음 |

## 3. prod 게시 게이트

**staging은 지금 그대로 게시해도 된다.** `scripts/publish-privacy.sh staging`에 막는 조건이 없고,
자리표시자가 남은 문서를 심사관이 보지 않는다.

prod 게시(`scripts/publish-privacy.sh prod`) 전에는 아래를 전부 닫는다.

| # | 자리 | 본문 행 | 닫는 조건 |
|---|---|---|---|
| 1 | 2항 제3자 제공 표 「광고 사업자 (확정 후 기재)」 | 286 | 광고 사업자 확정 (KAN-196) |
| 2 | 4항 광고 국외 이전 「(확정 후 기재)」 | 353~355 | 사업자 확정 + 소재 국가·항목·기간 확인 |
| 3 | 10항 「광고 사업자(확정 후 기재)」 | 483 | 사업자 확정 |
| 4 | 10항 동의 설정 위치 「(도입 시 위치 확정)」 | 514 | 앱 광고 동의 UI 구현 (KAN-196) |
| 5 | 10항 「4항과 이 항, 2항의 제3자 제공 표를 함께 채웁니다 (확정 후 기재)」 | 523~525 | 위 1~4가 닫히면 이 문장도 확정 문장으로 교체 |
| 6 | 시행일 「정식 게시일에 기재합니다」 | 102, 571 | 게시 당일 날짜로 교체 (두 자리 모두) |
| 7 | 스토어 답안 일치 | — | KAN-174 §5·§6과 KAN-175 라벨이 2절 「광고 도입 후 바뀔 답」대로 갱신됐는지 확인 |

자리표시자 자체는 계약 테스트로 막지 않는다. 막으면 확정 전 단계의 본문을 커밋할 수 없어
문서가 코드보다 뒤처지기 때문이다 (`privacy.test.mjs` 머리주석).

## 4. 변경 절차

코드에 적힌 사실이 바뀌면 순서는 이렇다.

1. **계약 테스트가 깨진다** — `node --test 'infra/privacy/*.test.mjs'`. 깨지지 않는 사실이면 2번부터 사람이 시작한다.
2. **본문을 고친다** — `infra/privacy/privacy.html`.
3. **이 문서의 매핑을 갱신한다** — 1절의 해당 행. 근거 경로와 행 번호까지.
4. **스토어 답안을 갱신한다** — `docs/wiki/play-store-listing.md` §5·§6 (KAN-174), App Store 라벨 (KAN-175). 신고와 실제 동작이 어긋나는 것이 정책 위반이다.
5. **게시한다** — `scripts/publish-privacy.sh staging` → 확인 → `scripts/publish-privacy.sh prod` (3절 게이트를 먼저 닫는다).

예를 들어 `infra/modules/fargate/variables.tf`의 `log_retention_days`를 14에서 30으로 올리면
본문에서 고칠 자리는 두 곳이다 — **1항 표의 「서버 운영 로그」 행**과 **5항 파기 목록의 로그 줄**.
계약 테스트는 로그 일수를 붙들지 않으므로 이때는 아무것도 깨지지 않는다. 이 표가 유일한 경보다.

반대로 음성 즉시 삭제·30분 청소·24시간 보존·서울 리전을 건드리면 테스트가 먼저 깨진다.
1항 표는 행 단위로 붙들려 있어(`1항 표의 보유 기간이 행마다 코드와 맞는다`), 보유 기간 칸의
수치나 기준점이 바뀌면 다른 절에 같은 낱말이 남아 있어도 그 행에서 걸린다.

## 5. 결정 기록

| 날짜 | 결정 | 본문에 남은 자리 |
|---|---|---|
| 2026-09-01 | 사용자 음성을 S3에 저장하지 않는다 | 1항 「음성은 데이터베이스나 S3 같은 영속 저장소에 저장하지 않습니다」 |
| 2026-09-01 | 방침 URL은 `/privacy.html` (확장자 없는 경로는 SPA 재작성에 먹힌다) | 14항 URL, `infra/privacy/README.md` |
| 2026-09-07 | 개인정보 보호책임자 이성주, 문의처 team2pl1@gmail.com | 13항 |
| 2026-09-07 | 시행일은 초안 날짜가 아니라 정식 게시일에 기재한다 | 102·571행 (prod 게이트) |
| 2026-09-07 | **1차 배포에 맞춤형 광고를 넣는다 (앱과 웹 모두).** 사업자 미정 | 1항 광고 행, 2항 제3자 제공 표, 4항, 8항, 10항 전체, 12항 |
| 2026-09-07 | 위 결정으로 KAN-2의 「동의 화면 범위 제외」가 부분 번복됐다 — 개인정보 수집·이용 동의 화면은 여전히 두지 않지만, **맞춤형 광고 동의 UI는 별도로 둔다** | 12항 「다만 맞춤형 광고는 별도로 동의를 받습니다」 |

## 6. 팀 확인이 필요한 것

- **Crashlytics 90일, GA4 2개월** — 둘 다 콘솔 기본값으로 적었고 레포에는 근거가 없다. 콘솔에서 실제 설정을 확인해 다르면 1항 표와 4항을 고친다.
- **운영 주체 표기** — 지금은 「Accentury 팀(이박이일)」이다. 사업자 등록이 없어 상호·대표자·주소를 적지 않았다. 광고 수익이 생기면 사업자 표기가 필요한지 확인해야 한다.
- **App Store 연령 등급** — Play는 13세 이상으로 정했으나(`docs/wiki/play-store-listing.md` §6) App Store 쪽 등급은 아직 정하지 않았다. 7항의 「만 14세 미만 대상 아님」과 어긋나지 않게 맞춘다.
- **광고 사업자** — 확정되면 3절의 자리표시자 7개가 한꺼번에 열린다.
