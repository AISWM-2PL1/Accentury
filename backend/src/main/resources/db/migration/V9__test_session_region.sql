-- KAN-201: 응시자의 출신(모어 사투리) 지역 - staging 학습 데이터 S3 객체 키의 첫 조각이다.
--
-- 웹 응시 흐름의 지역 선택 화면(KAN-202, staging 빌드 한정)이 세션 생성 요청(§3.1 region)으로
-- 넘긴 값을 그대로 둔다. API가 공용이라 prod에도 컬럼은 생기지만 prod 앱과 웹은 보내지 않으므로
-- 항상 null이다. 코드 10개(SEOUL, GYEONGGI, GANGWON, CHUNGBUK, CHUNGNAM, JEONBUK, JEONNAM,
-- GYEONGBUK, GYEONGNAM, JEJU) 중 하나이고 그 밖의 값은 backend가 400으로 거른다 (Region).
-- 광역 단위 코드 하나라 개인을 좁히지 않는다 (KAN-9 AC, SessionServiceTest의 컬럼 허용 목록).

alter table test_session add column region varchar(16);
