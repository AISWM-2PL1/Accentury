-- KAN-211: 결과 화면의 이용 후기 - 응시자가 개발팀에 보내는 서술 후기 (2026-09-15 결정).
--
-- 로그인이 없는 익명 서비스라 후기를 매달 곳이 세션뿐이다. 본인 확인도 세션 토큰(Bearer)이고,
-- 세션당 한 번만 받는다 (ux_session_feedback_session).
--
-- test_session에 FK를 걸지 않는다. 세션과 결과는 24시간 뒤 정리 잡이 지우는데(명세서 §5.5)
-- 후기는 1년(accentury.feedback.retention) 남아야 하기 때문이다 - FK가 있으면 세션이 사라지는
-- 순간 후기도 함께 지워지거나(CASCADE) 세션 정리가 막힌다(RESTRICT). 대신 후기를 읽을 때
-- 필요한 세션/결과 값(tier_code, test_version, score_version, platform, traffic)을 저장 시점에
-- 복사해 둔다 - 세션 행이 사라진 뒤에도 "어떤 등급을 본 사람이 무슨 말을 했나"를 알 수 있어야
-- 후기가 쓸모가 있다.
--
-- traffic을 함께 복사하는 것은 합성 트래픽(E2E 스모크, KAN-138)이 남긴 후기를 나중에 걸러내기
-- 위해서다. 개인 식별 정보는 선택 입력인 contact_email 하나뿐이고, 이 값과 본문은 로그에 남기지
-- 않는다 (FeedbackService).
--
-- 슬랙 전송은 2단계다 - 이 마이그레이션은 저장까지만 다룬다.

create table session_feedback (
    id              varchar(40)   not null,           -- "fb_" + UUID
    session_id      varchar(40)   not null,
    idempotency_key varchar(100)  not null,
    rating          smallint,
    body            varchar(500)  not null,
    contact_email   varchar(254),
    tier_code       varchar(40)   not null,           -- 저장 시점 test_result.tier_code 스냅샷
    test_version    varchar(40)   not null,           -- 세션의 test_version 스냅샷
    score_version   varchar(20)   not null,
    platform        varchar(10),                      -- 세션의 platform 스냅샷(nullable)
    traffic         varchar(10)   not null,           -- 세션의 traffic(REAL/SYNTHETIC) - 합성 트래픽 후기를 나중에 거르기 위해
    created_at      timestamp(6) with time zone not null,
    constraint pk_session_feedback primary key (id),
    constraint ux_session_feedback_session unique (session_id)
);

-- 정리 잡이 작성 시각으로 지운다 - 인덱스가 없으면 행이 쌓일수록 삭제가 전체 스캔이다.
create index ix_session_feedback_created_at on session_feedback (created_at);
