# syntax=docker/dockerfile:1
# BE(Spring Boot) 컨테이너 이미지 (KAN-120).
# 빌드 컨텍스트는 backend/다 - 이 디렉터리가 자체 settings.gradle과 래퍼를 가진 독립
# Gradle 프로젝트이고, 루트 프로젝트(:app, Android)와는 별개다.
#   docker build -t accentury-backend:dev backend

# --- 빌드 스테이지 ---------------------------------------------------------
# 툴체인이 Java 25를 요구한다(build.gradle) - JDK도 25로 맞춰 foojay 리졸버가 런타임을
# 따로 내려받지 않게 한다.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# 래퍼와 빌드 스크립트를 먼저 복사한다 - 소스만 바뀐 재빌드가 Gradle **배포본** 내려받기
# 레이어를 재사용한다. 의존성은 여기서 받지 않는다(--version은 배포본만 받는다) - 의존성
# 해석은 아래 bootJar에서 일어나고, 그 레이어는 COPY src 뒤라 소스가 바뀔 때마다 무효화된다.
# 그래서 의존성 캐시는 레이어가 아니라 BuildKit 캐시 마운트로 재사용한다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon --version > /dev/null

COPY src src
# 테스트는 이미지 빌드에서 돌리지 않는다 - Testcontainers가 도커 데몬을 요구하는데(KAN-123)
# 빌드 컨테이너 안에는 없다. 테스트는 CI(test.yml)가 담당한다.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test \
 && rm -f build/libs/*-plain.jar \
 && mv build/libs/*.jar build/app.jar

# --- 실행 스테이지 ---------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

# 비루트 실행 (NFR-SC-04) - 컨테이너가 뚫려도 호스트 권한으로 번지지 않게 한다.
RUN groupadd --system --gid 10001 accentury \
 && useradd --system --uid 10001 --gid accentury --home-dir /app --shell /usr/sbin/nologin accentury

WORKDIR /app
COPY --from=build /workspace/build/app.jar /app/app.jar
USER accentury

EXPOSE 8080

# JVM은 기본적으로 컨테이너 메모리 한도의 25%만 힙으로 쓴다 - 512MB 컨테이너면 힙이
# 128MB라 기동조차 빠듯하다. 한도의 75%를 쓰게 올리고, 힙이 마르면 살아 있는 척하지 말고
# 즉시 죽어 오케스트레이터가 다시 띄우게 한다(ExitOnOutOfMemoryError).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"

# exec로 넘겨 java가 PID 1이 되게 한다 - 그래야 docker stop의 SIGTERM이 셸에 먹히지 않고
# 스프링의 우아한 종료까지 도달한다.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
