-- KAN-138: 합성 트래픽(E2E 스모크)을 사용자 통계에서 분리한다.
--
-- 전 구간 스모크는 prod에서도 돌아야 한다(티켓 AC). 그런데 세션 생성과 완주는 익명 집계
-- 카운터(KAN-106)를 올리고, 그 카운터는 개인 결과가 24시간 뒤 파기돼도 남는 유일한 영속
-- 데이터다. 표시가 없으면 스모크 1회마다 완주율과 등급 분포가 영구히 흔들리고, 일자 합계라
-- 나중에 빼낼 수도 없다 (Codex sol 리뷰 P2).
--
-- 버리지 않고 나눈다 - 카운터 키에 축을 하나 더한다. 이미 (일자, 테스트 버전, 점수 버전)으로
-- 나뉘어 있던 것과 같은 성격의 축이고, 그래야 리포트(KAN-20)가 실사용자 옆에 스모크를
-- 나란히 볼 수 있다. 자세한 이유는 app.accentury.backend.analytics.Traffic 참고.

-- 세션이 합성인지는 생성 시점에 한 번만 정하고(SyntheticTraffic), 완주 카운터는 이 컬럼을
-- 따라간다. 기본값 REAL은 안전한 쪽이다 - 표시가 없으면 실사용자다.
alter table test_session
    add column traffic varchar(10) not null default 'REAL';

alter table daily_counter
    add column traffic varchar(10) not null default 'REAL';

-- 기존 행의 식별자는 건드리지 않는다. 실사용자 행은 트래픽 축이 생기기 전과 같은 형식을
-- 그대로 쓰기 때문이다(DailyCounter.idOf) - 여기서 '|REAL'을 붙여 두면, 롤백이나 배포 겹침으로
-- 옛 바이너리가 이 스키마 위에서 도는 동안 두 쪽이 같은 업무 키에 서로 다른 식별자를 만들어
-- 한쪽의 증가가 통째로 조용히 사라진다. 롤백은 KAN-128의 AC이므로 실제로 일어나는 일이다.
-- 합성 행만 '|SYNTHETIC' 접미사를 받고, 그 행은 옛 바이너리가 만들지 않는다.

-- 유니크 제약은 새 키 셋을 덮어야 한다. 그대로 두면 같은 날 같은 버전의 실사용자 행과
-- 합성 행이 두 줄로 공존할 수 없어, 스모크 첫 건이 INSERT에서 계속 지고 UPDATE로 되돌아가
-- 실사용자 행에 조용히 합산된다 - 분리하려던 것이 정확히 그 자리에서 무너진다.
alter table daily_counter drop constraint ux_daily_counter_key;
alter table daily_counter
    add constraint ux_daily_counter_key unique (stat_date, test_version, score_version, traffic);
