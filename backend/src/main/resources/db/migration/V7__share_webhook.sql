-- KAN-164: 카카오톡 공유 웹훅 - "전송 완료"의 익명 집계 (FR-SH-06).
--
-- 앱은 카톡으로 넘긴 것(share_launched)까지만 안다. 실제로 보냈는지는 카카오가 우리 서버로
-- 되돌려 주는 웹훅으로만 알 수 있고, 그 수를 여기 두 테이블이 받는다.
--
-- daily_counter에 컬럼을 더하지 않는다 (2026-09-06 확정). 그쪽 키는 (일자, testVersion, scoreVersion,
-- traffic)인데 웹훅에는 그 값이 없다 - 세션과 연결하지 않는 것이 이 집계의 요구(익명)라서다.
-- 그래서 키가 다른 별도 카운터다. 캠페인은 앱이 serverCallbackArgs로 실어 보내는 공용 상수
-- (kko_share)이고 사람을 가리키지 않는다.
--
-- share_webhook_receipt는 멱등 처리용이다. 카카오가 같은 전송을 두 번 알려도 X-Kakao-Resource-ID가
-- 같으므로 그 ID를 기억해 한 번만 센다. 값은 카카오가 붙인 불투명 ID라 개인을 가리키지 않고,
-- 보존 기간(accentury.share.receipt-retention, 기본 7일)이 지나면 정리 잡이 지운다.
-- 채팅방 해시(HASH_CHAT_ID), 채팅방 종류, 세션 id, 토큰, 점수는 어디에도 저장하지 않는다 (티켓 AC).

create table share_daily_counter (
    id        varchar(60) not null,
    stat_date date        not null,
    campaign  varchar(32) not null,
    sent      bigint      not null,
    constraint pk_share_daily_counter primary key (id),
    constraint ux_share_daily_counter_key unique (stat_date, campaign)
);

create table share_webhook_receipt (
    resource_id varchar(128) not null,
    received_at timestamp(6) with time zone not null,
    constraint pk_share_webhook_receipt primary key (resource_id)
);

-- 정리 잡이 받은 시각으로 지운다 - 인덱스가 없으면 행이 쌓일수록 삭제가 전체 스캔이다.
create index ix_share_webhook_receipt_received_at on share_webhook_receipt (received_at);
