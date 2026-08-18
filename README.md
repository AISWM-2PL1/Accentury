# Accentury

## 백엔드 로컬 DB

스키마는 Flyway 마이그레이션(`backend/src/main/resources/db/migration`)이 만들고, Hibernate는 validate로만 돕니다 (KAN-123).
`ddl-auto: update` 시절에 만들어진 로컬 DB 볼륨은 Flyway 이력이 없어 기동이 막히므로, 한 번만 `backend/`에서 `docker compose down -v && docker compose up -d`로 리셋합니다.
백엔드 테스트는 Testcontainers PostgreSQL로 돌아 Docker가 필요합니다.
