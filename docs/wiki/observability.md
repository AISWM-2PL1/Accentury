# 관측성 - 로그, 지표, 대시보드, 경보 (KAN-38)

구현 기록 (2026-09-05). **무엇을 어떻게 보는지의 정본**이다. 지표 이름의 코드 쪽 정본은
`backend/src/main/java/app/accentury/backend/observability/ServiceMetrics.java`, 경보와 대시보드의
정본은 `infra/modules/monitoring/`이고, 여기는 그 둘을 잇는 규약과 "왜 이 값을 보는가"를 적는다.

이 문서가 다루지 않는 것: 서버가 죽은 것을 알리는 최소 경보 4종(KAN-134, KAN-165, KAN-36)의
설계 근거는 `infra/README.md`의 "경보와 알림"이 정본이다. 여기는 그 이후, "서버는 살아 있는데
파이프라인이 고장 났다"를 보는 층이다.

## 1. correlation ID 규약

한 사람의 한 번의 응시가 앱, backend, AI 세 곳에 로그를 남긴다. 그 셋을 잇는 값이
`X-Correlation-Id` 하나다.

### 지금 이어져 있는 구간

| 구간 | 무엇이 하는가 |
|---|---|
| 클라이언트 → BE | 앱(`SessionClient`, `UploadClient`), iOS(`URLSessionSessionClient` 등), 웹(`uploadRecording.ts`)이 헤더를 붙인다 |
| BE 요청 전 구간 | `CorrelationIdFilter`가 MDC에 넣고 모든 로그 줄 앞에 `[...]`로 찍는다 (`logging.pattern.console`) |
| BE 응답 | 같은 값을 응답 헤더와 오류 봉투의 `correlationId` 필드로 돌려준다 (§2.2, §2.3) |
| BE → 워커 | 업로드 요청 스레드의 값을 `HttpAnalysisDispatcher`가 붙잡아 워커 MDC로 옮긴다 - 분석은 요청 스레드 밖에서 끝난다 |
| BE → AI | `RestAiAnalysisClient`가 같은 헤더로 실어 보낸다 |

### 형식

```
[A-Za-z0-9._-]{1,64}
```

`CorrelationIdFilter.SAFE_ID`가 검사하고, 형식 밖의 값(줄바꿈 등 로그 위조에 쓰이는 것)은
무시하고 서버가 새로 발급한다 - 클라이언트가 보낸 값이 무조건 그대로 쓰이지는 않는다는 뜻이다.
서버가 발급하는 형식은 `c_<uuid>`이고, 클라이언트가 보내는 맨 UUID도 이 형식 안에 든다.

### 앱 쪽에 남은 것 (KAN-33)

**두 가지가 비어 있고, 둘 다 KAN-33의 몫이다.** 이 티켓은 규약만 정한다.

1. **ID의 수명이 요청 하나다.** 세 클라이언트 모두 요청마다 새 UUID를 만든다
   (`UUID.randomUUID()`, `newIdempotencyKey()`). 그래서 같은 세션의 업로드 다섯 건이 서로 다른
   다섯 개의 ID를 갖고, "이 사람의 응시 전체"를 한 값으로 훑을 수 없다.
   **규약: 응시 1회(세션 생성부터 결과까지)에 ID 하나를 만들어 그 세션의 모든 요청에 같은 값을
   싣는다.** 재응시는 새 세션이므로 새 ID다.
2. **폴링 요청에는 헤더가 없다.** 지금 붙는 곳은 세션 생성과 업로드뿐이라, 대기 화면에서 무슨
   일이 있었는지(`/analyses`, `/complete`)가 추적에서 빠진다. **규약: `/v0/**`의 모든 요청에 붙인다.**

앱 로그에 남길 것은 그 ID와 화면 전이, 오류 코드다. 세션 토큰과 오디오는 앱 로그에도 남기지
않는다 (§2.6과 같은 규칙).

### 실패한 세션 하나를 추적하는 절차 (AC 1)

사용자가 "결과가 안 나왔다"고 하면 오류 화면의 `correlationId`를 받아서:

```
# CloudWatch Logs Insights - backend와 ai 로그 그룹을 함께 선택하고 실행한다
fields @timestamp, @logStream, @message
| filter @message like /c_1e4f.../
| sort @timestamp asc
| limit 200
```

BE는 `[<id>] ...` 형태로 모든 줄 앞에 찍고, AI는 요청 로그에 같은 값을 남기므로 한 질의로
업로드 접수 → 워커 전달 → AI 호출 → 종결까지가 시간순으로 나온다. `jobId=a_...`가 나오면
그 값으로 다시 훑어 그 시도의 재전송 이력까지 볼 수 있다.

## 2. 로그 마스킹

`LogMasking`이 출력 직전에 지운다. 코드가 애초에 넣지 않는 것이 1차 방어이고 이것은 마지막
관문이다 - 사람이 실수로 넣든, 프레임워크가 예외 메시지에 요청 헤더를 끼워 넣든 걸린다.

| 대상 | 규칙 | 남는 것 |
|---|---|---|
| 세션 토큰 | `st_...` 원문, `sessionToken=` 이름 | `st_***` (토큰이 찍혔다는 사실) |
| 관리자 토큰, AI 내부 토큰 | 헤더, 설정 키, 바인딩 필드, 환경 변수 네 갈래 철자 | 이름만 |
| `Authorization` 헤더 | 스킴을 가리지 않고 자격증명 전체 | 스킴 단어 |
| 오디오 바이트 | base64 200자 이상, `[82, 73, 70, ...]` 십진수 목록 | 길이만 |
| 임시파일 경로 | `accentury-voice-tmp/` 아래, `upload_*.tmp`, `audio-*.wav` | 디렉터리 이름 |

마스킹은 **어펜더별 패턴에 걸린다**(`logback-spring.xml`의 `%mask`, `%maskEx`). 어펜더를
추가하면 그 패턴에도 반드시 넣어야 하고, 구조화 로깅(`logging.structured.format`)을 켜려면
마스킹 경로를 먼저 만들어야 한다.

### 로그 샘플 검사 (AC 4)

배포 뒤 한 번, 그리고 로그 형식을 건드릴 때마다:

```
aws logs filter-log-events --region ap-northeast-2 \
  --log-group-name <backend 로그 그룹> --start-time $(( ($(date +%s) - 3600) * 1000 )) \
  --query 'events[].message' --output text \
  | grep -nE 'st_[A-Za-z0-9_-]{8,}|Bearer [^*]|accentury-voice-tmp/[^*]|[0-9]{1,3}(, ?[0-9]{1,3}){20,}'
```

한 줄도 안 나오는 것이 통과다. 규칙 자체의 회귀는 `LogMaskingTest`가 막는다 - 규칙만 맞고
실제 콘솔 어펜더에 안 걸리는 경우까지 보므로, 이 수동 검사는 "코드가 새 값을 만들어 냈는가"를
보는 것이다.

## 3. 지표

### 수집 경로 - Micrometer CloudWatch push (2026-09-05 확정)

티켓이 남긴 선택지는 둘이었다. **Micrometer CloudWatch 레지스트리로 push**를 택했다.

- 이미 깔려 있다. KAN-36이 회로 상태를 올리려고 `CloudWatchMetricsConfig`를 조립해 두었고,
  네임스페이스(`accentury/backend`), 차원(`env`), IAM 조건, 발행 주기(1분)가 전부 잡혀 있다.
  구조화 로그 기반으로 가면 그 배선을 버리고 metric filter를 새로 만들면서, 회로 상태만은
  여전히 push로 남는 이중 체계가 된다.
- 경보와 대시보드가 지표를 직접 읽는다. 로그 기반이면 대시보드 위젯마다 Logs Insights 질의가
  붙고 경보는 metric filter를 한 겹 더 거친다.
- P95를 낼 수 있다. 로그 기반으로 백분위를 내려면 질의 시점에 계산해야 하고 경보에는 못 건다.

**대가는 요금이다.** CloudWatch 커스텀 지표는 이름 x 차원 조합마다 월 0.30달러다. 이 티켓이
더하는 것은 29개다.

| 미터 | 조합 수 | 셈 |
|---|---|---|
| `accentury.http.requests` | 5 | Timer 4(sum/count/avg/max) + 백분위 1 |
| `accentury.analysis.duration` | 5 | 같음 |
| `accentury.ratelimit.rejected` | 5 | 제한 축 5개 |
| `accentury.http.errors` · `.polling` · `analysis.timeouts` · `analysis.poll` | 8 | 각 태그 값 2개씩 |
| `accentury.sessions.active` · `analysis.processing` · `analysis.inflight` | 3 | 태그 없는 게이지 |
| `accentury/ai`의 `TempFiles` · `TempOldestAge` · `TempScanFailures` | 3 | |

환경당 월 약 8.7달러다. 태그는 값이 다섯 이하로 닫힌 것만 쓴다 - 세션 ID나 IP를 태그로 쓰면
조합 수가 트래픽에 비례해 요금이 상한 없이 늘어난다.

### 이름 규칙

Micrometer의 CloudWatch 레지스트리가 종류마다 접미사를 붙인다. **경보와 대시보드는 접미사가
붙은 이름을 적어야 한다.**

| 미터 종류 | CloudWatch 이름 |
|---|---|
| Gauge | `<이름>.value` |
| Counter | `<이름>.count` |
| Timer | `<이름>.sum` · `<이름>.count` · `<이름>.avg` · `<이름>.max` (단위 ms) |
| 백분위 | `<이름>.percentile.value` (차원 `phi`) |

**백분위는 레지스트리가 스스로 내보내지 않는다.** Timer 스냅샷에는 있어도 CloudWatch 쪽 코드가
sum, count, avg, max만 쓴다. 그래서 `ServiceMetrics.registerPercentiles`가 Micrometer의
`HistogramGauges`로 백분위를 게이지로 따로 등록한다. 이것이 없으면 대시보드의 P95가 영영 빈다.
`.avg`에 CloudWatch의 p95 통계를 거는 것은 답이 아니다 - "분당 평균들의 p95"라서 지연 P95가 아니다.

### P95는 "가장 나쁜 태스크의 P95"다

백분위는 **태스크마다 따로 계산된다**. 태스크가 여럿이면(KAN-168, 최대 3) 같은 `env` 차원으로
값이 여럿 올라오고, 그것을 서비스 전체의 P95로 되돌릴 방법이 없다 - CloudWatch가 백분위를
계산하려면 관측값이 낱개로 올라와야 하는데 레지스트리는 분당 집계값 하나만 올린다.

대시보드는 그 여럿을 `Maximum`으로 접는다. NFR-PF-01 판단에 안전한 쪽이기 때문이다 - 평균으로
접으면 태스크 하나가 3초를 넘겨도 나머지에 희석되어 준수로 보인다. 위젯 라벨도 "P95 (태스크 중
최댓값)"으로 적어 두었다.

**읽을 때 주의가 하나 있다.** 막 뜬 태스크처럼 요청이 몇 건뿐인 태스크의 P95는 표본이 작아 크게
튄다 - 그래프의 뾰족한 봉우리가 "서비스가 느려졌다"가 아니라 "새 태스크에 느린 요청 하나가
있었다"일 수 있다. 그래서 두 지연 위젯에 평균(그리고 분석 쪽은 최댓값)을 함께 그린다. 백분위만
튀고 평균이 평평하면 한 태스크의 표본 문제이고, 둘이 같이 오르면 진짜 지연이다.

숫자와 비율(요청 수, 오류, 폴링, 429, 타임아웃)은 이 문제가 없다 - 전부 `Sum`이라 태스크 여럿의
값이 그대로 더해진다. 게이지 중 `analysis.processing`도 태스크마다 같은 DB 수를 올리므로
`Maximum`이 맞다(합하면 태스크 수만큼 부풀려진다).

### 카탈로그

`accentury/backend` 네임스페이스, 전부 `env` 차원이 붙는다.

| 지표 | 종류 | 태그 | 무엇을 보는가 |
|---|---|---|---|
| `accentury.http.requests` | Timer + P95 | 없음 | API 지연 P95, 오류율과 폴링 비율의 분모 |
| `accentury.http.errors` | Counter | `status` (4xx, 5xx) | 오류율 |
| `accentury.http.polling` | Counter | `endpoint` (analyses, complete) | 폴링 QPS, KAN-24 트리거 "30% 초과" |
| `accentury.ratelimit.rejected` | Counter | `axis` (ip, session), `scope` (5축) | 429 발생률, 재시도 폭풍 |
| `accentury.sessions.active` | Gauge | 없음 | KAN-24 트리거 "동시 응시자 만 명대" |
| `accentury.analysis.processing` | Gauge | 없음 | 전 인스턴스 진행 중 - 큐가 없어 이 값이 병목 지표 |
| `accentury.analysis.inflight` | Gauge | 없음 | 이 태스크가 붙든 건수 |
| `accentury.analysis.timeouts` | Counter | `reason` (stuck, lost) | 버려진 분석 |
| `accentury.analysis.poll` | Counter | `congested` (true, false) | 혼잡 pollAfterMs 발동 비율 |
| `accentury.analysis.duration` | Timer + P95 | 없음 | 업로드 접수부터 분석 완료까지, NFR-PF-01(3초) |
| `accentury.ai.circuit.state` | Gauge | 없음 | AI 회로 (KAN-36) |
| `accentury.upload.temp.files` | Gauge | 없음 | backend 임시파일 잔존 (KAN-27) |
| `accentury.upload.temp.oldest.age` | Gauge | 없음 | 같은 파일의 최장 잔존 시간 |
| `accentury.upload.temp.delete.failures` | Counter | 없음 | 삭제 실패 누적 |
| `accentury.upload.temp.scan.failures` | Counter | 없음 | 훑기 실패 - 잔존 게이지가 멎었다는 신호 |

`accentury/ai` 네임스페이스, 호스트의 systemd 타이머(`ai-health-metric.sh`)가 1분마다 올린다.

| 지표 | 무엇을 보는가 |
|---|---|
| `Healthy` | AI health 프로브 0/1 (KAN-36) |
| `TempFiles` | AI 임시 디렉터리 잔존 파일 수 |
| `TempOldestAge` | 그중 최장 잔존 시간(초) - 보존 기간 30분을 넘으면 삭제 실패다 |
| `TempScanFailures` | 훑기 실패 누적 |

AI 쪽 값의 출처는 `GET /internal/v0/metrics`이고 **토큰이 필요하다**(health와 달리 인증 예외가
아니다). 타이머는 SSM을 매분 다시 읽지 않고 `accentury-up.sh`가 기동 때 만들어 둔
`/run/accentury/ai.env`(root 전용 tmpfs)에서 토큰을 가져온다. 그 파일이 아직 없거나 조회에
실패하면 **지표를 아예 올리지 않는다** - 0으로 덮으면 "깨끗하다"가 되어, 조회가 막힌 바로 그
순간에 경보가 거꾸로 조용해진다.

### 계측이 세는 범위

`accentury.http.*`는 **`/v0/**`만** 센다. ALB 헬스체크(`/actuator/health`)가 수 초마다 두드리므로
그것까지 세면 요청 수가 헬스체크로 채워져 "/analyses가 전체의 몇 %인가"가 무의미해지고, 즉답하는
헬스체크가 지연 분포를 아래로 끌어내린다. 운영자 API(`/admin/v0/**`)도 뺀다.

계측 필터는 요청 제한 필터보다 **바깥**이다 - 안쪽에 두면 한도를 넘긴 트래픽이 지표에서 통째로
사라져, 부하가 몰릴수록 대시보드가 조용해진다.

`accentury.analysis.duration`은 **성공한 건만** 센다. 실패와 타임아웃을 섞으면 분포가 "AI가
답하는 데 걸리는 시간"이 아니라 "포기하는 데 걸리는 시간"과 뒤섞여 NFR-PF-01 판단에 쓸 수 없다.

### actuator는 여전히 health만 노출한다

`management.endpoints.web.exposure.include: health`는 그대로다. 지표는 push로 나가므로 웹에
`/actuator/metrics`를 열 이유가 없고, 열면 내부 구성(빈 이름, JVM 상태)이 인증 없이 드러난다.
이 원칙은 티켓이 수집 경로와 무관하게 유지하라고 못박은 것이다.

## 4. 대시보드

CloudWatch 대시보드 `accentury-{env}-ops` **하나**다. 바로가기는 환경 루트에서
`terraform output -raw dashboard_url`이다 (모듈 출력을 `infra/envs/*/outputs.tf`가 넘겨 준다 -
자식 모듈 출력은 루트에 자동으로 올라오지 않는다).

화면이 여럿이면 어느 것도 습관적으로 보지 않게 되고, 무엇보다 KAN-24 재검토 트리거는 여러 값을
**함께** 봐야 판단이 된다. 배치 규칙은 "위에서 아래로 사용자에게서 멀어진다"이다.

| 행 | 위젯 |
|---|---|
| 1 사용자가 겪는 것 | API 지연 P95 · 오류율(%) · 분석 지연 P95 (3초 기준선) |
| 2 부하의 모양 | `/analyses` 요청 비율(30% 기준선) · 동시 활성 세션 수 · 폴링 요청과 429(축별) |
| 3 파이프라인 내부 | 진행 중 건수와 회로 상태 · 타임아웃과 혼잡 발동 비율 · 임시파일 잔존 |

### KAN-24 재검토 트리거를 이 화면에서 판단하는 법

| 트리거 | 보는 위젯 | 넘으면 |
|---|---|---|
| `/analyses`가 전체 요청의 30% 초과 | 2행 첫째 (기준선이 그어져 있다) | long polling 전환 검토 |
| 동시 응시자 만 명대 | 2행 둘째 | 폴링 구조 재검토 |
| 대기 체류 시간 | **여기 없다** - 클라이언트 지표라 KAN-33 몫이다 | |

## 5. 경보

전부 같은 SNS 토픽(`accentury-{env}-alerts`)으로 간다. 심각도별 채널을 나누지 않는다 - 3인 팀에
채널이 여럿이면 어느 쪽도 보지 않게 된다.

KAN-134/165/36의 7종은 "서버가 죽었다"를 알린다(근거는 `infra/README.md`). KAN-38이 더한 셋은
"서버는 살아 있는데 파이프라인이 고장 났다"를 알린다 - 죽음은 사용자가 바로 알지만 이쪽은 대기
화면이 길어지는 것으로만 드러나 아무도 신고하지 않는다.

| 경보 | 조건 | 왜 이 값인가 |
|---|---|---|
| `ai-temp-residue` | `TempFiles` >= 20이 10분(5분 x 2회) | 이 지표는 처리 중인 파일도 센다. 동시 추론이 구조적으로 12건(워커 4 x 태스크 3)을 넘지 못하므로 20이면 정상 부하가 닿지 않는다 |
| `analysis-backlog-high` | 진행 중 >= 60이 5분 연속 | 폴링 혼잡 임계치(30)의 두 배. 서버가 폴링 간격을 올려 압력을 뺀 뒤에도 그만큼 쌓였다면 사람이 볼 일이다 |
| `analysis-timeouts-high` | 5분에 타임아웃 5건 초과 | 정상 운영에서는 0이다. 배포 중 태스크 교체로 나는 한두 건 위에 선을 긋는다 |

`analysis-backlog-high`가 보는 것은 **전 인스턴스 합**(DB의 PROCESSING 행 수)이다. 태스크별
인메모리 카운터로 걸면 태스크 셋이 임계치를 나눠 가져 아무도 울지 않는다 - KAN-167이 혼잡 판정을
DB로 옮긴 것과 같은 이유다. 태스크마다 같은 값을 올리므로 `Maximum`으로 읽는다(합이 아니다).

`analysis-timeouts-high`가 두 사유를 합해서 보는 것은 사용자에게는 어느 쪽이든 같은 실패이기
때문이다 - 어느 쪽이었는지는 대시보드가 나눠 그린다.

### 임계치를 언제 다시 보는가

- `ai_temp_residue_threshold` - 동시 추론 상한이 바뀔 때 (워커 수 `dispatch-concurrency`,
  오토스케일링 상한 KAN-168)
- `analysis_backlog_threshold` - `congestion-threshold`를 바꿀 때 (이 값의 두 배가 근거다)
- 셋 다 - 부하 테스트(KAN-40) 뒤에 실측으로 재조정한다

## 6. 이 티켓이 남긴 것

- **앱 correlation ID 규약의 구현** - 위 1절의 두 가지(세션 단위 수명, 폴링 요청에도 부착)는
  KAN-33이 클라이언트 지표와 함께 한다.
- **대기 체류 시간** - 클라이언트만 알 수 있는 값이라 이 대시보드에 없다. KAN-33.
- **GPU 지표** - 스텁 단계에는 GPU가 없다. 실모델 이후 KAN-36 범위다.
