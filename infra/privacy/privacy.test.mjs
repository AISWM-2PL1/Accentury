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
// 광고 사업자는 2026-09-11에 Google AdMob으로 확정됐다 (KAN-196 1단계). 그 전까지 2항(제3자
// 제공 표), 4항(국외 이전), 10항(광고)에 남아 있던 「확정 후 기재」 자리표시자를 이때 함께
// 채웠고, 이제는 되살아나는 쪽을 테스트로 막는다. 확정 전에 막지 않았던 것은 막으면 확정 전
// 단계의 본문을 커밋할 수 없어서 오히려 문서가 코드보다 뒤처졌기 때문이다.
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
  // 검사 목록 - 페이지가 바깥에서 무언가를 받아오는 경로 전부다.
  //   1. <script>
  //   2. 외부 스타일시트·글꼴(<link>)
  //   3. CSS @import
  //   4. CSS가 끌어오는 원격 자산 - `url(https://…)`와 프로토콜 상대 `url(//…)` 둘 다
  //   5. 자산을 끌어오는 태그 - <img> <iframe> <source> <object> <embed> <video> <audio>
  //   6. src·srcset·poster 속성 - 5번 목록이 놓친 경로를 덮는다
  // <a href>는 링크일 뿐 페이지가 무언가를 받아오지 않으므로 대상이 아니다. 본문에 외부
  // https 링크와 mailto:가 있어서, href를 6번에 넣으면 전부 오탐이 된다.
  assert.ok(!/<script/i.test(html), '<script>가 있다');
  assert.ok(!/<link/i.test(html), '<link>가 있다');
  assert.ok(!/@import/i.test(html), 'CSS @import가 있다');
  // 프로토콜 상대 경로(`url(//cdn.example/x.woff2)`)도 원격이다 - 페이지가 https로 열리면
  // 브라우저가 https로 받아온다. `url(http`만 보던 앞 판은 이 형태를 통째로 놓쳤다.
  assert.ok(!/url\(\s*['"]?\s*(?:https?:)?\/\//i.test(html), 'CSS가 원격 자산을 참조한다');
  // 자산을 끌어오는 태그 자체를 막는다. 위 넷은 스타일과 스크립트만 보므로, 본문에 이미지 한 장이
  // 끼어들면 전부 통과한다 - 그러면 S3에 html 하나를 올리는 것으로 교체가 끝나지 않는다.
  for (const tag of ['<img', '<iframe', '<source', '<object', '<embed', '<video', '<audio']) {
    assert.ok(!html.toLowerCase().includes(tag), `${tag}>가 있다`);
  }
  // 위 태그 목록이 놓친 경로(<input src>, 미래의 새 태그)까지 한 번에 덮는다. `\ssrc=`만 보던
  // 앞 판은 등호 앞 공백(`src = "…"`)과 srcset·poster를 놓쳤다 - 셋 다 유효한 HTML이다.
  assert.ok(!/\b(?:src|srcset|poster)\s*=/i.test(html), 'src·srcset·poster 속성이 있다');
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
    '광고',
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

// ---------------------------------------------------------------------------
// 1항 표의 행 단위 검사.
//
// 위 세 테스트는 「30분」·「24시간」·「즉시 삭제」가 문서 어딘가에 한 번이라도 있으면 통과한다.
// 그래서 표의 보유 기간 칸이 통째로 틀려도, 다른 절에 같은 낱말이 남아 있는 한 아무 경보가
// 울리지 않는다 (2026-09-07 Codex 검증 지적). 심사관과 이용자가 실제로 읽는 것은 이 표이므로,
// 「어느 구분의 보유 기간이 무엇인가」를 행에 묶어서 본다.
// ---------------------------------------------------------------------------

/** 1항 표의 각 행을 `{ 구분, 보유기간 }`으로 뽑는다. 의존성 없이 문자열만 자른다. */
function retentionRows() {
  const section = html.slice(
    html.indexOf('<h2>1. 개인정보의 처리 목적'),
    html.indexOf('<h2>2. 개인정보의 제3자 제공'),
  );
  assert.ok(section.length > 0, '1항을 찾지 못했다 - 절 제목이 바뀌었나');
  const rows = new Map();
  for (const row of section.split('<tr>').slice(1)) {
    const cells = [...row.matchAll(/<td>([\s\S]*?)<\/td>/g)].map((m) =>
      m[1].replace(/\s+/g, ' ').trim(),
    );
    // thead의 <th> 행은 <td>가 없어 자연히 걸러진다.
    if (cells.length < 2) continue;
    rows.set(cells[0], cells[cells.length - 1]);
  }
  return rows;
}

test('1항 표의 보유 기간이 행마다 코드와 맞는다', () => {
  const rows = retentionRows();
  // [구분, 그 행의 보유 기간 칸에 반드시 있어야 하는 문자열들, 근거]
  const expected = [
    ['음성 녹음', ['즉시 삭제'], 'KAN-27, ai/app/tempstore.py'],
    // 미완주 세션은 생성 30분 뒤 만료 정리(SessionService.java:133), 완주 세션은 완료 시점부터
    // 24시간(TestSession.java:140-157, CompletionService.java:169-175). 기준점이 둘이라 셋을 함께 본다.
    ['익명 테스트 세션', ['30분', '완료', '24시간'], 'session/SessionService.java, TestSession.java'],
    ['테스트 결과와 어휘 답안', ['24시간'], 'application.yml analysis.retention: 24h'],
    // 카카오 공유 웹훅의 중복 판별용 수신 기록 (KAN-164). 기본값 7일은
    // AccenturyProperties.Share.receiptRetention의 @DefaultValue이고, 정리 잡이 그 값으로 지운다.
    ['공유 전송 알림 기록', ['7일'], 'AccenturyProperties.java:249, ShareWebhookReceiptRetention.java:36-40'],
    ['서버 운영 로그', ['14일'], 'infra/modules/fargate/variables.tf log_retention_days'],
    ['보안 로그', ['7일'], 'infra/modules/waf/variables.tf'],
    ['비정상 종료 로그', ['90일'], 'Crashlytics 콘솔 기본값'],
  ];
  for (const [label, needles, source] of expected) {
    const cell = rows.get(label);
    assert.ok(cell !== undefined, `1항 표에 「${label}」 행이 없다`);
    for (const needle of needles) {
      assert.ok(
        cell.includes(needle),
        `「${label}」 행의 보유 기간에 「${needle}」이 없다 (근거: ${source}) - 실제: ${cell}`,
      );
    }
  }
});

test('세션 보유 기간의 기준점이 「생성 시점」으로 되돌아가지 않았다', () => {
  // 완주 세션의 24시간은 세션 생성이 아니라 완료 시점부터 센다 (CompletionService.java:169-175가
  // markCompleted로 만료를 다시 잡는다). 1단계 초안에 있던 「세션 생성 24시간 후」가 되살아나면
  // 실제보다 긴 보유 기간을 고지하게 된다.
  assert.ok(!html.includes('세션 생성 24시간'), '「세션 생성 24시간」이 남아 있다');
});

test('맞춤형 광고 절이 있고 동의·거부 방법이 적혀 있다 (2026-09-07 팀 결정: 1차 배포에 광고 포함)', () => {
  // 「광고」는 본문 어디에나 나오는 낱말이라 필수 절 목록의 includes만으로는 절이 실제로 있는지
  // 알 수 없다. 제목 자체를 본다.
  assert.ok(/<h2>\d+\. 광고<\/h2>/.test(html), '광고 절 제목이 없다');
  assert.ok(html.includes('맞춤형 광고'), '맞춤형 광고 고지가 없다');
  // 동의를 받는 경로(iOS ATT)와 거부하는 경로(Android 광고 개인 최적화)를 둘 다 적어야
  // 이용자가 실제로 철회할 수 있다. 스토어 심사도 이 두 가지를 본다.
  assert.ok(html.includes('앱 추적 투명성'), 'iOS ATT 안내가 없다');
  assert.ok(html.includes('광고 개인 최적화'), 'Android 광고 개인 최적화 해제 안내가 없다');
});

test('"광고와 추적이 없습니다"가 남아 있지 않다 (광고 도입으로 거짓이 된 문장)', () => {
  // KAN-176 1단계 초안에 있던 문장이다. 1차 배포에 맞춤형 광고를 넣기로 한 2026-09-07 팀 결정
  // 이후로는 거짓 고지라서, 본문을 손보다가 되살아나는 것을 여기서 막는다.
  assert.ok(!html.includes('광고와 추적이 없습니다'), '거짓이 된 "광고와 추적이 없습니다"가 남아 있다');
});

test('진행 기록의 삭제 시점이 적혀 있다 (KAN-198, web/src/App.tsx)', () => {
  // 「서비스가 따로 지우지 않으므로」는 clearSnapshot 호출점이 하나도 없던 시절의 사실이다.
  // 결과 화면 진입(clearSnapshot)과 인트로 진입(sweepSnapshots)에 삭제를 결선한 뒤로는 거짓
  // 고지라서, 본문을 손보다가 되살아나는 것을 여기서 막는다. 반대 방향(배선이 사라지는 쪽)은
  // web/src/App.test.tsx가 먼저 깨져서 알려 준다.
  assert.ok(!html.includes('서비스가 따로 지우지 않'), '삭제 배선 전의 문장이 남아 있다');
  assert.ok(html.includes('결과 화면을 보시는 때에'), '진행 기록을 지우는 시점이 적혀 있지 않다');
  assert.ok(html.includes('다음에 첫 화면을 여시면'), '끊긴 응시의 기록을 지우는 시점이 없다');
});

test('「확정 후 기재」 자리표시자가 남아 있지 않다 (KAN-196)', () => {
  // 2026-09-11 사업자 확정으로 2·4·10항의 자리표시자를 전부 채웠다. 본문을 손보다가 한 자리라도
  // 되살아나면 prod에 미정 문구가 게시되므로 여기서 막는다. 10항의 동의 설정 위치에 있던
  // 「도입 시 위치 확정」도 같은 자리표시자다.
  assert.ok(!html.includes('확정 후 기재'), '「확정 후 기재」가 남아 있다');
  assert.ok(!html.includes('도입 시 위치 확정'), '「도입 시 위치 확정」이 남아 있다');
});

/** 절 제목 사이의 본문을 자른다. `from` 제목부터 `to` 제목 직전까지. */
function section(from, to) {
  const start = html.indexOf(from);
  const end = html.indexOf(to);
  assert.ok(start >= 0 && end > start, `절을 찾지 못했다: ${from} ~ ${to}`);
  return html.slice(start, end);
}

test('2항 제3자 제공 표와 10항 광고 절에 Google AdMob이 적혀 있다 (KAN-196 2026-09-11 사업자 확정)', () => {
  // 「Google」은 3항·4항의 Firebase 행에도 나오므로 문서 전체 includes로는 광고 사업자가 적혔는지
  // 알 수 없다. 광고 사업자를 적어야 하는 절 둘을 각각 본다.
  const thirdParty = section('<h2>2. 개인정보의 제3자 제공', '<h2>3. 개인정보 처리의 위탁');
  assert.ok(thirdParty.includes('Google LLC (Google AdMob)'), '2항 제3자 제공 표에 Google AdMob이 없다');
  const ads = section('<h2>10. 광고</h2>', '<h2>11. 개인정보의 안전성 확보 조치');
  assert.ok(ads.includes('Google AdMob'), '10항 광고 절에 Google AdMob이 없다');
  // 사업자가 정해졌으니 광고가 어디서 나오는지도 적어야 한다 (전면 광고는 분석 대기, 보상형은 재응시).
  assert.ok(ads.includes('전면 광고'), '10항에 전면 광고 자리가 없다');
  assert.ok(ads.includes('보상형 광고'), '10항에 보상형 광고 자리가 없다');
});

test('4항 국외 이전에 광고 항목이 들어 있다 (KAN-196, Google LLC 행에 합침)', () => {
  // AdMob도 Google LLC라 Firebase·GA와 같은 이전받는 자다. 별도 행을 만들지 않고 기존 Google LLC
  // 행의 이전받는 자·이전되는 항목·이용 목적에 광고 몫을 더했다. 셋 중 하나라도 빠지면 광고
  // 식별자가 국외로 가는 사실을 고지하지 않는 셈이다.
  const abroad = section('<h2>4. 개인정보의 국외 이전', '<h3>이용 통계 이벤트');
  assert.ok(abroad.includes('Google AdMob'), '4항 이전받는 자에 Google AdMob이 없다');
  assert.ok(abroad.includes('광고 식별자'), '4항 이전되는 항목에 광고 식별자가 없다');
  assert.ok(abroad.includes('맞춤형 광고 표시'), '4항 이용 목적에 맞춤형 광고 표시가 없다');
});

test('데이터 소재지가 서울 리전이라고 적혀 있다 (infra, AWS ap-northeast-2)', () => {
  // 국외 이전 고지의 반대편이다. 음성·세션·결과는 국내에 머무르고 Google로 가는 것은
  // 이용 통계와 오류 로그뿐이라는 구분이 이 문구에 걸려 있다.
  assert.ok(html.includes('ap-northeast-2'), '리전 표기가 없다');
  assert.ok(html.includes('서울 리전'), '서울 리전 표기가 없다');
});
