# syntax=docker/dockerfile:1
# AI 분석 서버(FastAPI) 컨테이너 이미지 (KAN-120, KAN-22).
# 빌드 컨텍스트는 ai/다.
#   docker build -t accentury-ai:dev ai
#
# [베이스는 모델 전달본이다 - KAN-22]
# 채점 모델은 모델 담당이 ECR accentury/ai-model에 모델 해시 태그로 직접 올린다 (KAN-159,
# KAN-173). 우리는 그 이미지 위에 FastAPI 앱과 어댑터만 얹는다. 참조 분포와 Whisper 가중치,
# MFA 음향 모델과 문장 목록이 전부 그 안에 있어서 운영 컨테이너가 인터넷과 S3에 닿지 않고도
# 채점한다 (ai 호스트는 iptables로 egress를 끊는다, KAN-36).
#
# 모델 태그는 여기 한 곳에만 적는다. 태그가 IMMUTABLE이라 "이 태그 = 그 모델"이 성립하고,
# 모델이 바뀌면 이 줄을 바꾸는 것이 곧 모델 교체다. 레지스트리만 빌드 인자로 덮는다
# (scripts/push-images.sh가 계정에서 유도한 값을 넘긴다).
#
# [배포 제약 - 워커는 1개다]
# 이 서버는 임시 디렉터리 하나를 프로세스 하나가 전용으로 쓴다는 전제 위에 서 있다(KAN-27).
# uvicorn --workers로 여러 프로세스를 한 디렉터리에 띄우면, 기동 시 도는 잔여물 정리가
# 형제 워커가 지금 처리 중인 오디오를 지운다. 워커를 늘려야 하면 반드시 워커마다 다른
# ACCENTURY_AI_TEMP_DIR를 준다. 어차피 모델이 RSS 7GB대라 프로세스 1개가 전제다(KAN-36).
ARG MODEL_REGISTRY=325771561913.dkr.ecr.ap-northeast-2.amazonaws.com
ARG MODEL_TAG=6b0f406
FROM ${MODEL_REGISTRY}/accentury/ai-model:${MODEL_TAG} AS runtime

# PYTHONDONTWRITEBYTECODE - 비루트 사용자가 소스 디렉터리에 .pyc를 못 써서 나는 잡음을 없앤다.
# PYTHONUNBUFFERED - 로그가 컨테이너 표준출력에 즉시 흐르게 한다.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# 베이스의 WORKDIR과 같다 - 전달본의 src와 registry가 여기 있고, 어댑터의 기본 경로
# (ACCENTURY_AI_TRACK1_SRC_DIR=/app/src)가 그것을 가리킨다.
WORKDIR /app

# 서비스 계층의 의존성만 더한다 - 추론 쪽(torch, transformers, librosa, MFA)은 베이스에 이미
# 있다. 파일명을 requirements.txt로 두지 않는 이유는 베이스의 같은 이름 파일(모델 의존성 목록)을
# 덮어써 이미지 안에서 두 목록을 구분할 수 없게 되기 때문이다.
COPY requirements.txt ./service-requirements.txt
RUN pip install --no-cache-dir -r service-requirements.txt

COPY app app

# 비루트 실행 (NFR-SC-04). 베이스는 root로 도는 이미지라 실행 중 쓰는 경로를 이 사용자에게
# 넘겨줘야 한다 - 하나라도 빠지면 첫 요청이 아니라 워밍업에서 죽는다.
#
#   /app       HOME이다. 라이브러리가 ~/.cache에 쓴다
#   /opt/mfa   MFA_ROOT_DIR - 정렬 작업 폴더가 여기 생긴다 (약 60MB, pretrained_models 포함)
#
# HF_HOME(/opt/hf)은 일부러 두고 온다. Whisper 가중치 2.8GB가 있는 곳이라 소유자를 바꾸면
# 그 2.8GB가 우리 이미지의 레이어로 통째로 복사된다. 오프라인 모드(HF_HUB_OFFLINE=1)라 읽기만
# 하면 되고, 그래도 잠금 파일을 만들려는 경로가 있어 그 디렉터리 하나만 미리 내준다.
RUN groupadd --system --gid 10001 accentury \
 && useradd --system --uid 10001 --gid accentury --home-dir /app --shell /usr/sbin/nologin accentury \
 && mkdir -p /opt/hf/hub/.locks \
 && chown -R accentury:accentury /app /opt/mfa /opt/hf/hub/.locks
USER accentury

EXPOSE 8000

# 베이스가 물려주는 ENTRYPOINT를 지운다. 모델 전달본 이미지는 `python`을 진입점으로 두고
# `src/scoring/serve.py`를 CMD로 주는 CLI라(KAN-159), 그대로 두면 아래 CMD가
# `python uvicorn app.main:app ...`으로 실행돼 컨테이너가 기동 즉시 죽는다.
ENTRYPOINT []

# --workers 1은 위 배포 제약의 강제다. 값을 올리기 전에 반드시 임시 디렉터리를 나눈다.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
