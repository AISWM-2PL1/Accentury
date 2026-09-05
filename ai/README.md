# Accentury AI 서버 (FastAPI)

BE(Spring Boot)만 호출하는 **사설망 전용** 분석 서버입니다 (API 명세서 §1.1, §4, NFR-SC-04).
퍼블릭 인터넷에 노출하지 않습니다.

## 지금 들어 있는 것 (KAN-27)

이 스켈레톤이 완성한 범위는 **원본 음성의 수명 관리**입니다.

- `POST /internal/v0/analyze` (§4.1) - multipart(audio + meta)를 받아 §4.1 봉투로 응답합니다.
  점수는 스텁이 만듭니다 - 기본값은 `correlationId`를 해시해 0~100을 고르게 덮는
  분산 모드입니다 (KAN-136).
- 입력 오디오는 전용 임시 디렉터리에만 내려놓고, 응답을 돌려주는 즉시 지웁니다.
  성공, 판정 실패, 예외, 클라이언트 취소 어느 경로로 빠져나가도 같습니다.
- 임시파일은 예측 불가능한 이름 + 0600, 디렉터리는 0700입니다.
- 프로세스가 만드는 모든 임시파일이 이 디렉터리에 모입니다(`tempfile.tempdir`를 돌려둡니다) -
  프레임워크가 큰 업로드를 디스크로 스풀해도 공용 임시 디렉터리로 새지 않습니다.
- 추론에 상한(기본 30초)을 걸어 멈춘 요청이 임시파일을 무한정 붙들지 못하게 합니다. 초과하면 503입니다.
- 본문 상한(2MB)을 multipart 파싱 전에 끊고, 오디오 파트 상한(1MB)을 다시 확인합니다. 초과하면 413입니다.
- **디렉터리 하나는 프로세스 하나가 전용으로 씁니다.** 기동 정리가 남아 있던 파일을 전부 지우므로,
  `uvicorn --workers`로 여러 프로세스를 띄우려면 워커마다 다른 `ACCENTURY_AI_TEMP_DIR`를 줘야 합니다.
- 기동 시 디렉터리에 남아 있던 파일은 나이와 무관하게 전부 지웁니다(앞선 프로세스의 잔여물뿐입니다).
- 이후 청소 잡이 5분마다 돌면서 30분 이상 잔존한 파일을 지웁니다. 삭제는 멱등합니다.
- `GET /internal/v0/metrics`가 잔존 파일 수와 최장 잔존 시간을 돌려줍니다 (KAN-38이 소비).
- `GET /internal/v0/health` (§4.2) - 기동 중(워밍업 전)에는 503 `{"status": "STARTING"}`,
  준비가 끝나면 200 `{"status": "UP"}`입니다. BE는 200 + `UP`만 살아 있는 것으로 읽습니다.

## 내부 호출 인증과 준비 상태 (KAN-36)

AI 서버는 backend와 다른 EC2에서 돕니다 (KAN-36 A단계). 같은 compose 네트워크라는 전제가
사라졌으므로 두 가지가 더해졌습니다.

- **공유 시크릿 헤더** `X-Accentury-Internal-Token` (`app/auth.py`). `ACCENTURY_AI_INTERNAL_TOKEN`이
  설정된 서버는 `/internal/v0/health`를 뺀 모든 요청에서 이 헤더를 설정값과 대조하고, 없거나
  다르면 401 `{"status": "FAILED"}`로 끊습니다. 검사는 미들웨어라 multipart 본문을 임시파일로
  내려놓기 전에 돕니다. backend는 같은 값을 `accentury.analysis.ai-token`으로 받아 헤더를 붙입니다
  (배포에서는 Terraform `infra/modules/config`가 한 난수를 두 SSM 이름으로 싣습니다). 값이 없으면
  검사를 건너뛰고 기동 로그에 경고를 남깁니다 - 로컬 개발 편의이고 배포에서는 있을 수 없는 상태입니다.
- **준비 상태 게이트**. 기동 직후 엔진의 `warm_up()`(있으면)을 백그라운드로 돌리고, 끝나야 health가
  UP이 됩니다. uvicorn은 lifespan이 끝나야 포트를 열므로 워밍업을 lifespan 안에서 기다리면 그동안은
  503이 아니라 연결 거부입니다 - 그래서 뒤에서 돌립니다. 실모델(KAN-22)은 가중치 적재를 정확히 이
  이름의 `async def warm_up(self) -> None`(동기 `def`도 됩니다 - 스레드로 넘깁니다)에 두면 그동안
  backend의 회로 복구 프로브와 compose healthcheck가 503 `STARTING`을 보고 요청을 보내지 않습니다.
  이름이 다르면 조용히 건너뛰므로 기동 로그의 `warmUp=있음`으로 확인합니다. 워밍업이 실패하면
  준비 전에 머물러 컨테이너가 unhealthy로 남고 파이프라인이 롤백합니다. 스텁은 워밍업이 없습니다.
- **배포에서는 토큰이 없으면 기동하지 않습니다.** 운영 compose가 `ACCENTURY_AI_INTERNAL_TOKEN_REQUIRED=true`를
  주므로 SSM에서 토큰이 빠진 채 뜨는 fail-open 상태가 없습니다 (backend `DeploymentConfigGuard`와 대칭).

## 분석 엔진 어댑터 (KAN-135)

점수를 만드는 일은 `app/engine.py`의 `AnalysisEngine` 뒤에 있습니다. 라우트는 엔진이
무엇인지 모르고, 엔진은 오디오 파일 하나를 보고 결과를 말하는 일만 합니다.

| 자리 | 책임 |
| --- | --- |
| 라우트 (`app/analyze.py`) | 임시파일 수명, 추론 상한, 오디오 파트 상한(413), §4.1 봉투 조립 |
| 엔진 (`app/engine.py`) | 오디오 1건 -> 상태, 억양 점수, 신뢰도, 품질 코드, `segments` |

- 엔진은 기동 시 `ACCENTURY_AI_ANALYSIS_ENGINE`으로 한 번만 고릅니다. 모르는 이름이면
  기동이 실패합니다 - 스텁으로 조용히 흘러가면 실모델을 띄웠다고 믿는 환경이 고정 점수를
  내보내면서 아무 신호도 남기지 않기 때문입니다.
- `modelVersion`은 설정이 아니라 엔진이 스스로 보고합니다. 스텁은 `stub-0.1`을 냅니다.
  비어 있으면 기동이 실패합니다 - BE가 성공 응답을 계약 위반으로 끊고 회로 차단기를
  세우기 때문입니다.
- 실모델(KAN-22)을 붙여도 **라우트(`app/analyze.py`)는 고치지 않습니다.** 엔진 구현
  하나를 더하면 됩니다. 프로토콜만 맞추면 상속은 필요 없습니다 (`tests/conftest.py`의
  `FakeEngine`이 그 예입니다). 다만 `GET /internal/v0/models`와 워밍업 상태는 이 경계
  바깥이라 `app/main.py`에 자리를 만들어야 합니다.
- `AnalysisOutcome`은 만들어지는 시점에 스스로를 검사합니다. 아래를 어기면 값 자체가
  존재하지 못합니다 - 그런 응답을 BE가 받으면 계약 위반으로 끊으면서 회로 차단기를
  세우기 때문입니다.
  - 성공이면 억양 점수가 **반올림한 정수**이고 0~100 안입니다. 실수는 거절됩니다.
  - 성공이면 신뢰도가 비어 있지 않습니다.
  - 품질 코드는 비어 있지 않고 40자 이하입니다 (BE의 `analysis_job.quality_code` 컬럼 폭).
    성공 경로의 코드는 BE가 검사 없이 저장하고 사용자에게도 내보냅니다.
  - 실패면 품질 코드가 §2.4 판정 코드여야 합니다. 허용 목록은 `AUDIO_TOO_QUIET`,
    `AUDIO_TOO_LONG`, `AUDIO_FORMAT_UNSUPPORTED`, `ANALYSIS_MISREAD` 넷입니다
    (`engine.py`의 `JUDGED_QUALITY_CODES`). 기본값 `OK`도, 오타도, 지어낸 서술적 코드도
    거절됩니다 - BE의 `ErrorCode`에 없는 이름은 계약 위반이 되고 그 문항은 재시도 없이
    죽습니다. BE가 §2.4에 판정 코드를 더하면 이 집합에도 더합니다.
  - 실패면 `retryable`이 불리언이어야 합니다. 문자열 `"yes"`는 JSON으로 멀쩡히 나가고
    BE의 역직렬화에서 터집니다.
  - 봉투 재료 전체가 JSON으로 직렬화됩니다. 실모델이 채우는 `confidence`와 `segments`가
    `numpy.float32`, `numpy.int64`면 거절됩니다 - 파이썬 `float`/`int`의 하위 타입이
    아니라서, 막지 않으면 응답 조립 시점에 500이 되고 BE가 재전송 예산을 태웁니다.
    엔진은 파이썬 기본 타입으로 변환해서 냅니다.
- `StubEngine`은 실모델 전환 시 통째로 제거됩니다. 스텁 전용 동작은 전부 이 클래스 안에만
  둡니다 - 점수 분산(KAN-136)의 해시도 그 안입니다.

### 스텁 점수 (KAN-136)

- 기본은 **분산 모드**입니다. `correlationId`를 `blake2b`로 해시해 0~100 중 하나로 접습니다.
  같은 `correlationId`는 언제나 같은 점수라, BE 재전송이 멱등이고 E2E도 재현됩니다.
- 고정 75점이던 시절에는 종합 점수가 50.0~83.3에만 놓였습니다. 억양이 상수라 종합
  `(억양x2 + 단어)/3`이 단어 점수에만 좌우되고, 5등급 중 셋만 나왔습니다.
- 앱은 업로드마다 새 `X-Correlation-Id`를 발급하므로 한 세션의 음성 5문항은 서로 독립이고,
  억양 점수는 그 다섯의 평균입니다. **한 세션의 다섯 요청에 같은 `X-Correlation-Id`를
  고정하면** 다섯 문항이 같은 점수가 되어 세션 억양 점수를 원하는 자리로 끌 수 있습니다 -
  특정 등급 화면을 재현하는 수단입니다.
- 씨앗은 라우트가 정한 추적 ID 하나입니다 (§2.2 - 헤더 우선, 없으면 `meta`). 손으로 호출할
  때 헤더만 주고 `meta.correlationId`를 빠뜨려도 헤더 값이 그대로 씨앗이 됩니다.
- 회귀 테스트나 계약 테스트처럼 기준값이 필요하면 `ACCENTURY_AI_STUB_SCORE_MODE=fixed`로
  기존 동작(75점)을 그대로 씁니다.
- 해시는 추론이 아닙니다. 오디오를 한 바이트도 보지 않습니다.

### 엔진 쪽 계약

라우트가 거는 보장(추론 상한, 임시파일 삭제)은 엔진이 다음 둘을 지킬 때만 성립합니다.
편의가 아니라 계약이고, 지켰는지는 아래 계약 적합성 스위트가 봅니다 (KAN-137).

1. **이벤트 루프를 막지 않습니다.** `asyncio.timeout`은 `await` 지점에서만 발화하므로,
   동기 추론을 `async def` 안에서 그대로 돌리면 상한이 걸리지 않습니다. 블로킹 추론은
   `asyncio.to_thread`나 전용 executor로 넘깁니다. 엔진 호출 구간이 예산을 넘겼는데도
   상한이 발화하지 않은 요청은 ERROR 로그로 남습니다 (끊지는 못합니다).
2. **취소가 실제로 닿아야 합니다.** `asyncio.to_thread`에 넘긴 작업은 취소되지 않아서,
   라우트가 503을 내고 임시파일을 지운 뒤에도 워커가 계속 돌며 이미 사라진 파일을 봅니다.

### 계약 적합성 스위트 (KAN-137)

`tests/contract`는 **엔진 종류를 모르는** 테스트입니다. 스텁이든 실모델이든 같은 본문이
돌고, 이 스위트의 통과가 실모델(KAN-22)의 인수 조건입니다.

| 항목 | 근거 |
| --- | --- |
| 응답 봉투가 §4.1과 일치합니다 (필드 집합과 타입) | BE `RestAiAnalysisClient`가 필드로 역직렬화합니다 |
| 억양 점수가 0~100입니다 | §4.3 스케일 |
| 동일 입력 반복 분석 오차가 ±2점 이내입니다 | KAN-19 AC |
| 오디오가 같으면 추적 ID가 달라도 점수가 같습니다 | 점수는 오디오에서 나와야 합니다 (스텁은 해시라 건너뜁니다) |
| 예산을 넘기면 503입니다 | §4.1. 엔진이 이벤트 루프를 막으면 여기가 200으로 떨어집니다 |
| 오디오 상한을 넘기면 413입니다 | §3.3과 같은 1MB. 계약값을 직접 쓰고 서버 설정이 그 값인지도 봅니다 - 설정에서 유도하면 어긋난 상한이 그대로 통과합니다 |
| meta 형식 오류는 400입니다 | 계약 위반은 BE 버그이고, 5xx로 내면 재전송을 부릅니다 |
| `scoreVersion`을 그대로 에코백합니다 | §5.4 불일치 가드의 전제 |
| 판정 실패가 §2.4 코드와 불리언 `retryable`을 냅니다 | BE `ErrorCode`에 없는 이름은 문항을 죽입니다 |
| `scriptKey` 없는 meta의 처리 | KAN-182 계약 - 엔진별 선언을 따릅니다 |
| 모든 종료 경로 뒤에 오디오가 남지 않습니다 | KAN-27 AC-1 |

엔진마다 다른 것(느리게 만드는 설정, 판정 실패를 유도할 문항, `scriptKey` 없는 meta의
처리, 점수가 오디오에서 나오는지)은 테스트 본문이 아니라 `tests/contract/conftest.py`의
`ENGINE_PROFILES` 표 한 줄에 있습니다. **엔진을 갈아끼울 때 고치는 것은 그 표뿐입니다.**
프로파일이 없는 이름으로 돌리면 스위트가 안내와 함께 실패하고, 프로파일이 비워 둔 항목은
사유를 적어 건너뜁니다 - 조용히 통과시키지 않습니다.

```bash
pytest tests/contract                                    # 스텁 (기본, 그냥 pytest에도 딸려 돕니다)
pytest tests/contract --contract-engine=<이름> \
       --contract-audio=<실제 발화 WAV>                  # 실모델
```

실모델로 돌릴 때 둘을 맞춥니다. 맞추지 않으면 계약과 무관한 이유로 항목이 깨집니다.

- `--contract-audio`에 **실제 발화 WAV**를 줍니다. 기본 픽스처는 합성 사인파라 실모델이
  판정 실패(422)를 낼 수 있고, 그러면 성공 경로 항목이 통째로 오탐합니다.
- `ACCENTURY_AI_ANALYSIS_TIMEOUT_SECONDS`를 KAN-172 재조정값으로 올립니다. 기본 30초와
  08-30 실측(14~30초)이 겹쳐 정상 분석이 503으로 끊깁니다. 스위트는 나머지 설정도
  `Settings.from_env()`로 읽으므로 환경 변수를 그대로 씁니다 (내부 토큰만 끕니다 -
  인증은 KAN-36의 전용 테스트가 봅니다).

## 아직 없는 것

| 항목 | 담당 |
| --- | --- |
| 실제 추론 (F0 추출, guideF0 정렬, 점수 산출) - `AnalysisEngine` 구현 하나로 들어옵니다 | KAN-22 |
| `GET /internal/v0/models` (모델 버전) | KAN-22 |
| `correlationId` 기반 멱등 캐시 (§4.1) | KAN-22 - 지금은 스텁이 무상태이고 결정적이라 재요청 결과가 같습니다 |
| 실모델 컨테이너 이미지 (`accentury/ai-model` 베이스) | KAN-22 (전용 EC2 배치는 KAN-36 A단계 완료, GPU는 KAN-57 판정 후) |

## 실행

```bash
cd ai
python -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
uvicorn app.main:app --port 8000     # BE의 accentury.analysis.ai-base-url과 맞춥니다
pytest
```

## 설정 (환경 변수)

| 변수 | 기본값 | 용도 |
| --- | --- | --- |
| `ACCENTURY_AI_TEMP_DIR` | `<시스템 임시 디렉터리>/accentury-ai-tmp` | 임시파일 전용 디렉터리 |
| `ACCENTURY_AI_TEMP_RETENTION_SECONDS` | `1800` | 잔존 파일 삭제 기준 (30분) |
| `ACCENTURY_AI_SWEEP_INTERVAL_SECONDS` | `300` | 청소 잡 주기 |
| `ACCENTURY_AI_ANALYSIS_TIMEOUT_SECONDS` | `30` | 분석 1건의 상한 - BE 읽기 타임아웃(10초) 뒤의 방어선 |
| `ACCENTURY_AI_MAX_AUDIO_BYTES` | `1048576` | 오디오 파트 상한 (§3.3과 동일) |
| `ACCENTURY_AI_MAX_REQUEST_BYTES` | `2097152` | 요청 본문 전체 상한 - multipart 파싱 전에 끊습니다 |
| `ACCENTURY_AI_ANALYSIS_ENGINE` | `stub` | 붙일 분석 엔진 - 모르는 이름이면 기동이 실패합니다 |
| `ACCENTURY_AI_STUB_SCORE_MODE` | `hashed` | 스텁 점수 산출 방식 - `hashed`는 `correlationId` 해시, `fixed`는 아래 고정값. 모르는 값이면 기동이 실패합니다 |
| `ACCENTURY_AI_STUB_SCORE` | `75` | `fixed` 모드의 억양 점수 (0~100, 문항 20점 환산은 BE). 범위 밖이면 기동이 실패합니다 |
| `ACCENTURY_AI_STUB_DELAY_MS` | `1500` | 추론 지연 흉내 |
| `ACCENTURY_AI_STUB_FAIL_ITEM` | (없음) | 이 itemId면 422 판정 실패를 돌려줍니다 |
| `ACCENTURY_AI_INTERNAL_TOKEN` | (없음) | backend와 나눠 갖는 내부 호출 시크릿 (KAN-36). 있으면 health를 뺀 모든 요청에 `X-Accentury-Internal-Token` 헤더를 요구합니다 |
| `ACCENTURY_AI_INTERNAL_TOKEN_REQUIRED` | `false` | `true`면 위 토큰이 없을 때 기동이 실패합니다 (KAN-36). 운영 compose가 켭니다 |
