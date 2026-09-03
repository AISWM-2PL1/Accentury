-- KAN-182: 음성 문항 세트 다중화 - 세션이 응시하는 세트를 세션과 결과에 고정한다.
--
-- 발행본 하나가 음성 문장 풀(N개)을 담고, BE가 풀을 5문항 세트로 나눈다(VoiceSets). 세션은
-- testVersion만이 아니라 "그 버전의 몇 번째 세트"까지 고정해야 제출 검증, 상태 조회, 완주
-- 판정, 집계가 전부 같은 10문항을 본다. 세트는 발행본에 저장하지 않고 규칙으로 유도하므로
-- 세트 자체의 테이블은 없다 - 세션이 드는 것은 번호 하나뿐이다.
--
-- 기본값 1: 기존 행은 전부 세트 1 세션이 된다. 지금까지의 발행본은 음성이 5개뿐이라 세트가
-- 하나였고(N = 5면 세트 1개, 현행과 같다), 세트를 모르는 옛 바이너리가 이 스키마 위에서
-- 돌아도 기본값으로 INSERT가 통과한다 (롤백 호환, KAN-128 AC).
--
-- daily_counter에는 세트 축을 넣지 않는다 (티켓 범위 제외) - 세트별 완주율이 필요해지면
-- KAN-138의 traffic 축과 같은 방식으로 별도 티켓에서 더한다.
alter table test_session
    add column voice_set integer not null default 1;

-- 결과에도 남긴다 - 결과 응답에 노출할지는 FE 요구가 나오면 정하고, DB에는 먼저 둔다.
alter table test_result
    add column voice_set integer not null default 1;
