# Accentury AI 서버 (FastAPI)

BE(Spring Boot)만 호출하는 **사설망 전용** 분석 서버입니다 (API 명세서 §1.1, §4, NFR-SC-04).
퍼블릭 인터넷에 노출하지 않습니다.

## 지금 들어 있는 것 (KAN-27)

이 스켈레톤이 완성한 범위는 **원본 음성의 수명 관리**입니다.

- `POST /internal/v0/analyze` (§4.1) - multipart(audio + meta)를 받아 §4.1 봉투로 응답합니다.
  점수는 고정값 스텁입니다.
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
- `GET /internal/v0/health` (§4.2) - 프로세스 생존만 알립니다.

## 아직 없는 것

| 항목 | 담당 |
| --- | --- |
| 실제 추론 (F0 추출, guideF0 정렬, 점수 산출) | KAN-22 |
| `GET /internal/v0/models` (모델 버전, 워밍업 상태) | KAN-22 |
| `correlationId` 기반 멱등 캐시 (§4.1) | KAN-22 - 지금은 스텁이 무상태이고 결정적이라 재요청 결과가 같습니다 |
| GPU 배포, 컨테이너 이미지 | KAN-36 |

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
| `ACCENTURY_AI_STUB_SCORE` | `75` | 스텁 억양 점수 (0~100, 문항 20점 환산은 BE) |
| `ACCENTURY_AI_STUB_MODEL_VERSION` | `stub-0.1` | 스텁 모델 버전 |
| `ACCENTURY_AI_STUB_DELAY_MS` | `1500` | 추론 지연 흉내 |
| `ACCENTURY_AI_STUB_FAIL_ITEM` | (없음) | 이 itemId면 422 판정 실패를 돌려줍니다 |
