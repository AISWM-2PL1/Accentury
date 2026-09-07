// 개인정보처리방침 본문의 계약 테스트 (KAN-176 1단계).
//
// 이 파일이 있는 이유는 두 가지다.
//
// 하나는 페이지가 홀로 서야 한다는 조건이다 (KAN-133). 본문 교체는 S3에 이 파일 하나를
// 올리는 것으로 끝나야 하므로, 외부 CSS·글꼴·스크립트가 한 줄이라도 섞이면 web/ 번들이
// 바뀔 때 정책 문서가 조용히 깨진다. 심사관이 보는 페이지라 깨져도 우리가 먼저 알기 어렵다.
//
// 다른 하나는 문서가 코드보다 오래 산다는 점이다. "분석이 끝나면 즉시 삭제한다", "24시간 뒤
// 지운다" 같은 문장은 우리가 코드로 지키고 있는 약속이고, 그 코드가 바뀌면 문서가 먼저 거짓말이
// 된다. 핵심 사실을 여기서 붙들어 두면, 사실이 바뀔 때 이 테스트가 먼저 깨져서 문서를 고치라고
// 알려 준다 - 거짓 고지가 배포되는 쪽보다 낫다.
//
// `node --test 'infra/privacy/*.test.mjs'` 로 돈다. 디렉터리 경로를 그냥 넘기면 안 된다 -
// node 22.6부터 --test의 위치 인자는 glob 패턴으로 해석돼서, 디렉터리를 주면 그 이름의 모듈을
// 찾다가 MODULE_NOT_FOUND로 죽는다.
// CI는 node 22, 로컬은 26이라 양쪽에서 도는 표준 API만 쓴다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const html = readFileSync(join(HERE, 'privacy.html'), 'utf8');

test('자리표시자를 막던 noindex가 없다 (KAN-133 -> KAN-176)', () => {
  // 자리표시자가 검색에 잡히지 않게 걸어 둔 줄이다. 확정 본문에 남아 있으면 스토어와 이용자가
  // 검색으로 이 문서를 찾지 못한다.
  assert.ok(!/noindex/.test(html), 'noindex 메타가 남아 있다');
});

test('외부 자원을 하나도 쓰지 않는다 (KAN-133 "S3 업로드만으로 교체")', () => {
  // 스크립트, 외부 스타일시트·글꼴(<link>), CSS import, CSS가 끌어오는 원격 자산 넷을 막는다.
  // <a href>는 링크일 뿐 페이지가 무언가를 받아오지 않으므로 대상이 아니다.
  assert.ok(!/<script/i.test(html), '<script>가 있다');
  assert.ok(!/<link/i.test(html), '<link>가 있다');
  assert.ok(!/@import/i.test(html), 'CSS @import가 있다');
  assert.ok(!/url\(\s*['"]?http/i.test(html), 'CSS가 원격 자산을 참조한다');
});

test('자리표시자 문구가 남아 있지 않다', () => {
  assert.ok(!html.includes('이 문서는 준비 중입니다'), '자리표시자 문구가 남아 있다');
});

test('법정 필수 절이 모두 있다', () => {
  // 「개인정보 보호법」 제30조가 처리방침에 담으라고 정한 항목들과, 이 서비스에서 실제로
  // 일어나는 처리(국외 이전, 자동 수집 장치)를 함께 본다. 절을 지우거나 제목을 갈아엎으면
  // 여기서 걸린다.
  for (const heading of [
    '개인정보의 처리 목적, 처리 항목, 보유 기간',
    '개인정보의 제3자 제공',
    '개인정보 처리의 위탁',
    '개인정보의 국외 이전',
    '개인정보의 파기',
    '정보주체의 권리와 행사 방법',
    '만 14세 미만 아동의 개인정보',
    '개인정보 자동 수집 장치의 설치·운영과 거부',
    '개인정보의 안전성 확보 조치',
    '개인정보 보호책임자와 문의처',
    '시행일',
  ]) {
    assert.ok(html.includes(heading), `필수 절이 없다: ${heading}`);
  }
});

test('연락처가 있다 (Play·App Store 심사가 요구하는 항목)', () => {
  assert.ok(html.includes('team2pl1@gmail.com'), '보호책임자 연락처가 없다');
});

// ---------------------------------------------------------------------------
// 코드가 지키고 있는 약속. 아래 사실이 바뀌면 이 테스트가 먼저 깨져야 한다.
// ---------------------------------------------------------------------------

test('음성은 분석 직후 즉시 삭제라고 적혀 있다 (KAN-27, ai/app/tempstore.py)', () => {
  // 분석이 성공·실패·시간 초과·취소 어느 쪽으로 끝나든 임시 파일을 지운다. 이 처리가 바뀌면
  // 권한 안내 문구("녹음은 분석 후 즉시 삭제돼요")와 스토어 설명도 함께 거짓이 된다.
  assert.ok(html.includes('즉시 삭제'), '음성 즉시 삭제 문구가 없다');
});

test('임시 파일 청소 기준 30분이 적혀 있다 (KAN-27, ai temp-retention: 30m)', () => {
  // 세션 토큰 수명(30m, backend application.yml)도 같은 값이라 문서에서 두 자리에 쓰인다.
  assert.ok(html.includes('30분'), '30분 기준이 없다');
});

test('세션·결과 보유 기간 24시간이 적혀 있다 (KAN-25, backend retention: 24h)', () => {
  assert.ok(html.includes('24시간'), '24시간 보유 기간이 없다');
});

test('데이터 소재지가 서울 리전이라고 적혀 있다 (infra, AWS ap-northeast-2)', () => {
  // 국외 이전 고지의 반대편이다. 음성·세션·결과는 국내에 머무르고 Google로 가는 것은
  // 이용 통계와 오류 로그뿐이라는 구분이 이 문구에 걸려 있다.
  assert.ok(html.includes('ap-northeast-2'), '리전 표기가 없다');
  assert.ok(html.includes('서울 리전'), '서울 리전 표기가 없다');
});
