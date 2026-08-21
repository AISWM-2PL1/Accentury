# syntax=docker/dockerfile:1
# AI 분석 서버(FastAPI) 컨테이너 이미지 (KAN-120).
# 빌드 컨텍스트는 ai/다.
#   docker build -t accentury-ai:dev ai
#
# [배포 제약 - 워커는 1개다]
# 이 서버는 임시 디렉터리 하나를 프로세스 하나가 전용으로 쓴다는 전제 위에 서 있다(KAN-27).
# uvicorn --workers로 여러 프로세스를 한 디렉터리에 띄우면, 기동 시 도는 잔여물 정리가
# 형제 워커가 지금 처리 중인 오디오를 지운다. 워커를 늘려야 하면 반드시 워커마다 다른
# ACCENTURY_AI_TEMP_DIR를 준다. 어차피 GPU 동시 슬롯 때문에 프로세스 1개가 전제다(KAN-36).
FROM python:3.13-slim AS runtime

# PYTHONDONTWRITEBYTECODE - 비루트 사용자가 소스 디렉터리에 .pyc를 못 써서 나는 잡음을 없앤다.
# PYTHONUNBUFFERED - 로그가 컨테이너 표준출력에 즉시 흐르게 한다.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

# 의존성을 먼저 설치한다 - 소스만 바뀐 재빌드가 설치 레이어를 재사용한다.
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app app

# 비루트 실행 (NFR-SC-04).
RUN groupadd --system --gid 10001 accentury \
 && useradd --system --uid 10001 --gid accentury --home-dir /app --shell /usr/sbin/nologin accentury \
 && chown -R accentury:accentury /app
USER accentury

EXPOSE 8000

# --workers 1은 위 배포 제약의 강제다. 값을 올리기 전에 반드시 임시 디렉터리를 나눈다.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
