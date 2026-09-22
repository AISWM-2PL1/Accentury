# backend

Spring Boot 4.1 (Java 25) + PostgreSQL 16. 앱과 웹이 부르는 테스트 API와 AI 서버 호출을 맡는다. API 정본은
Notion "App-Backend API 명세서"이고, 인프라와 배포는 [`infra/README.md`](../infra/README.md)가 정본이다.

## 로컬 실행

```
docker compose up -d      # PostgreSQL 16 (accentury / postgres / local, 127.0.0.1:5432)
./gradlew bootRun         # 기동 시 Flyway가 스키마와 발행본을 만든다
./gradlew test            # Testcontainers PostgreSQL - Docker가 떠 있어야 한다
```

AI 서버 없이도 기동한다 (`accentury.analysis.ai-base-url` 미설정 = 전달하지 않는 개발 모드). 설정 값과
그 이유는 전부 `src/main/resources/application.yml` 주석에 있다.

## Flyway 마이그레이션

스키마 정본은 `src/main/resources/db/migration`이다. Hibernate는 `ddl-auto: validate`로만 돌아 엔티티와
스키마가 어긋나면 기동을 막고, validate가 안 보는 길이, nullable, 인덱스 집합은 `SchemaBaselineTest`가
대조한다 (KAN-123).

**2026-09-21 재베이스라인 (KAN-220).** 옛 V1~V12를 `V1__baseline.sql` 하나로 합쳤다. 정의 발행 마이그레이션
네 벌(각 640KB)이 쌓여 있었는데 쓰는 정의는 gn-2026.09.4 하나라서다. 경위와 합친 출처 표는 그 파일
머리 주석에 있고, 다음 마이그레이션은 `V2__<설명>.sql`부터 다시 번호를 매긴다.

| 상황 | 하는 일 |
| --- | --- |
| 빈 DB (테스트, 새 로컬) | V1이 그대로 실행된다. 이력에 `1 / SQL` 한 행이 남는다 |
| 옛 V1~V12 이력이 남은 로컬 DB | 체크섬이 어긋나 기동이 막힌다. `docker compose down -v && docker compose up -d`로 리셋한다 (개발 데이터는 버려도 된다) |
| staging, prod | 운영자가 이력 표의 이름을 바꾸고 구 정의 행을 지운 뒤 배포한다. `baseline-on-migrate`(application.yml)가 V1을 실행하지 않고 baseline 행만 기록한다. 절차와 복구 경로는 infra README "RDS 운영자 접속과 Flyway 재베이스라인 (KAN-220)" 절 |

규칙:

- 적용된 마이그레이션 파일은 고치지 않는다 (checksum 검증). 스키마 변경도 정의 변경도 새 파일이다.
- 테스트 정의는 발행 후 불변이다 (KAN-26, §5.4). 대본 한 글자를 고쳐도 새 testVersion을 새 마이그레이션으로
  발행하고, 활성 전환은 배포 뒤 `PUT /admin/v0/active-version`이 따로 한다 (2단계 롤아웃).
- 정의 마이그레이션은 손으로 쓰지 않는다 - `tools/content/build_definition.py`가 만든다.
- 정의를 품은 파일의 주석에 달러 인용 구분자 문자열을 적지 않는다. 앱, iOS, 웹의 발행본 전수 검사가
  `V1__baseline.sql`에서 처음 만나는 구분자 두 개 사이를 정의 JSON으로 읽는다.
- 테스트 전용 발행본은 `src/test/resources/db/testdata`(V899 더미 gn-2026.08.1과 테스트 활성 되돌림, V900
  구버전, V901 풀 픽스처)에 두고 test 프로파일만 읽는다 (`application-test.yml`).
