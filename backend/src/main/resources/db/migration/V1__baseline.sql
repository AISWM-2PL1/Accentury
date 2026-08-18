-- KAN-123 baseline: 현 엔티티 전체의 스키마 정본.
--
-- 이 파일이 만들어지기 전까지 스키마는 ddl-auto: update가 만들었다. 이제부터 스키마 변경은
-- 반드시 새 V{n}__*.sql 마이그레이션으로만 한다 - 이 파일은 적용된 순간 불변이다 (checksum 검증).
-- Hibernate는 validate로만 돈다 - 단 validate는 컬럼 존재와 타입 코드만 대조한다.
-- 길이, nullable, 유니크, 인덱스, FK는 검증 대상이 아니므로 마이그레이션이 그것들의
-- 정본이고, 엔티티와의 드리프트는 SchemaBaselineTest의 가드가 잡는다 (2026-08-18 리뷰).
--
-- 타입 대응 (Hibernate 7 기본 매핑 기준):
--   String @Column(length=n) -> varchar(n) / Instant -> timestamp(6) with time zone
--   LocalDate -> date / int -> integer / long -> bigint / boolean -> boolean
--
-- FK와 ON DELETE CASCADE (KAN-107 선행 조건 흡수):
--   세션을 참조하는 하위 테이블 3개(analysis_job, vocab_answer, test_result)는 전부
--   ON DELETE CASCADE다 - 세션 행이 지워지면 하위 행이 DB 수준에서 함께 지워진다.
--   재응시 폐기(KAN-107 purgeForRetake)의 명시적 벌크 삭제는 그대로 유지된다 - 잠금 순서를
--   코드가 통제한다는 장점이 있고, CASCADE는 코드가 삭제를 빠뜨렸을 때의 마지막 안전망이다.
--   부수효과 - 만료 세션 주기 삭제(SessionService.purgeExpired)도 이제 하위 3테이블을 함께
--   지운다. 미완주 세션의 하위 행 수명이 테이블별 24시간 보존에서 세션 TTL(30분) 언저리로
--   당겨지는데, 조회 경로가 전부 세션 인증을 요구해 사용자 영향은 없다 (2026-08-18 리뷰).
--   daily_counter는 CASCADE 제외다 - 세션을 참조하는 컬럼 자체가 없다. 익명 통계는 세션
--   폐기(재응시, 24시간 만료)와 무관하게 영속하는 것이 설계다 (KAN-106, NFR-PR-03).
--
-- CASCADE 인덱스: PostgreSQL은 FK 컬럼에 인덱스를 만들지 않는다. 하위 3테이블 모두
-- session_id로 시작하는 유니크/인덱스가 이미 있어 CASCADE 삭제가 순차 스캔을 타지 않는다.

create table test_session (
    id             varchar(40)  not null,
    token_hash     varchar(64)  not null,
    test_version   varchar(40)  not null,
    score_version  varchar(20)  not null,
    platform       varchar(10),
    app_version    varchar(32),
    campaign_token varchar(64),
    created_at     timestamp(6) with time zone not null,
    expires_at     timestamp(6) with time zone not null,
    completed_at   timestamp(6) with time zone,
    constraint pk_test_session primary key (id)
);

-- 토큰 해시로 세션을 찾는 인증 경로의 조회 인덱스이자 중복 방지 (엔티티 @Index와 동일 이름)
create unique index ux_test_session_token_hash on test_session (token_hash);

create table analysis_job (
    id               varchar(40)  not null,
    session_id       varchar(40)  not null,
    item_id          varchar(40)  not null,
    attempt          integer      not null,
    idempotency_key  varchar(100) not null,
    status           varchar(20)  not null,
    created_at       timestamp(6) with time zone not null,
    intonation_score integer,
    quality_code     varchar(40),
    error_code       varchar(40),
    model_version    varchar(60),
    score_version    varchar(40),
    started_at       timestamp(6) with time zone,
    finished_at      timestamp(6) with time zone,
    constraint pk_analysis_job primary key (id),
    constraint fk_analysis_job_session foreign key (session_id)
        references test_session (id) on delete cascade,
    constraint ux_analysis_job_idempotency unique (session_id, item_id, idempotency_key)
);

-- 상태 조회와 채점 대상 선정이 (세션, 문항)으로 시도들을 훑는다 (KAN-24).
create index ix_analysis_job_session_item on analysis_job (session_id, item_id);

create table vocab_answer (
    id              varchar(40)  not null,
    session_id      varchar(40)  not null,
    item_id         varchar(40)  not null,
    choice_id       varchar(40)  not null,
    is_correct      boolean      not null,
    idempotency_key varchar(100) not null,
    created_at      timestamp(6) with time zone not null,
    constraint pk_vocab_answer primary key (id),
    constraint fk_vocab_answer_session foreign key (session_id)
        references test_session (id) on delete cascade,
    constraint ux_vocab_answer_session_item unique (session_id, item_id)
);

create table test_result (
    id            varchar(40) not null,
    session_id    varchar(40) not null,
    test_version  varchar(40) not null,
    score_version varchar(20) not null,
    intonation    integer     not null,
    vocabulary    integer     not null,
    overall       integer     not null,
    tier_code     varchar(40) not null,
    tier_name     varchar(60) not null,
    tier_rank     integer     not null,
    tier_count    integer     not null,
    created_at    timestamp(6) with time zone not null,
    expires_at    timestamp(6) with time zone not null,
    constraint pk_test_result primary key (id),
    constraint fk_test_result_session foreign key (session_id)
        references test_session (id) on delete cascade,
    constraint ux_test_result_session unique (session_id)
);

-- 익명 집계 카운터 (KAN-106) - 위에 적은 대로 FK 없음이 설계다. id는 키 셋에서 유도한
-- 문자열(2026-08-17|gn-2026.08.1|sv-0.3)이고, 유니크 제약은 유도 규칙 버그가 같은 키 셋을
-- 두 행으로 가르는 것을 막는 안전망이다 (DailyCounter.idOf 참고).
create table daily_counter (
    id                 varchar(100) not null,
    stat_date          date         not null,
    test_version       varchar(40)  not null,
    score_version      varchar(20)  not null,
    sessions_started   bigint       not null,
    sessions_completed bigint       not null,
    tier_outsider      bigint       not null,
    tier_traveler      bigint       not null,
    tier_wannabe       bigint       not null,
    tier_honorary      bigint       not null,
    tier_native        bigint       not null,
    intonation_sum     bigint       not null,
    vocabulary_sum     bigint       not null,
    overall_sum        bigint       not null,
    scored_count       bigint       not null,
    constraint pk_daily_counter primary key (id),
    constraint ux_daily_counter_key unique (stat_date, test_version, score_version)
);
