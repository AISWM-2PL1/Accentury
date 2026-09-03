// SPA 재작성 Function과 App Link 검증 파일의 단위 테스트 (KAN-126, KAN-32 4단계).
//
// 이 두 가지를 한 파일에 둔 이유는 서로가 서로의 전제이기 때문이다 — 검증 파일이 올바르게
// 생겨도 리라이트 예외가 없으면 애플 CDN은 HTML을 받고, 예외가 있어도 파일의 경로 집합이
// 매니페스트와 갈라지면 링크가 앱으로 오지 않는다.
//
// `node --test 'infra/modules/edge/*.test.mjs'` 로 돈다. 디렉터리 경로를 그냥 넘기면 안 된다 —
// node 22.6부터 --test의 위치 인자는 glob 패턴으로 해석돼서, 디렉터리를 주면 그 이름의 모듈을
// 찾다가 MODULE_NOT_FOUND로 죽는다 (node 22·26 둘 다 실측).
// CI는 node 22, 로컬은 26이라 양쪽에서 도는 표준 API만 쓴다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = join(HERE, '..', '..', '..');

// CloudFront Function은 모듈이 아니라 전역 `handler`를 노출하는 스크립트라 import할 수 없다.
// 실행 환경(cloudfront-js-2.0)도 ES5에 가까운 격리 런타임이라, 소스를 그대로 읽어 빈 컨텍스트에서
// 평가하는 쪽이 실제 실행에 가장 가깝다.
const source = readFileSync(join(HERE, 'spa-rewrite.js'), 'utf8');
const context = vm.createContext({});
vm.runInContext(source + '\nglobalThis.__handler = handler;', context);
const handler = context.__handler;

/** 함수가 받는 event 모양은 uri 하나만 보면 충분하다. */
function rewrite(uri) {
  return handler({ request: { uri } }).uri;
}

test('확장자 없는 경로는 index.html로 돌아간다', () => {
  assert.equal(rewrite('/t'), '/index.html');
  assert.equal(rewrite('/t/'), '/index.html');
  assert.equal(rewrite('/some/deep/route'), '/index.html');
});

test('해시 자산과 index.html은 그대로 지나간다', () => {
  assert.equal(rewrite('/assets/app.abc123.js'), '/assets/app.abc123.js');
  assert.equal(rewrite('/index.html'), '/index.html');
});

test('/.well-known/ 아래는 재작성하지 않는다 (KAN-32)', () => {
  // 확장자가 없어 예외가 없으면 index.html이 되는 쪽. 이 한 줄이 iOS 검증의 전부다.
  assert.equal(
    rewrite('/.well-known/apple-app-site-association'),
    '/.well-known/apple-app-site-association',
  );
  // 점이 있어 원래도 통과하지만, 예외가 접두사 전체를 덮는지 함께 본다.
  assert.equal(rewrite('/.well-known/assetlinks.json'), '/.well-known/assetlinks.json');
});

test('점 없는 이름이 우연히 비슷해도 예약 접두사가 아니면 재작성된다', () => {
  // `/well-known`(점 없음)은 RFC 8615의 그 자리가 아니다 — SPA 경로로 다뤄야 한다.
  assert.equal(rewrite('/well-known/foo'), '/index.html');
});

// ---------------------------------------------------------------------------
// App Link 검증 파일 (KAN-32 4단계)
// ---------------------------------------------------------------------------

const ENVS = ['prod', 'staging'];
const RELEASE_FINGERPRINT =
  '48:C1:0D:86:23:E2:69:1A:37:5A:9B:03:82:2F:4F:84:6C:CC:01:69:25:E8:12:F6:CD:9D:94:8C:48:E3:FB:F2';
const APP_ID = '559P9SYY57.com.accentury.app';

function readWellKnown(env, name) {
  const path = join(REPO_ROOT, 'infra', 'well-known', env, '.well-known', name);
  return JSON.parse(readFileSync(path, 'utf8'));
}

test('두 환경의 검증 파일이 모두 JSON으로 파싱된다', () => {
  // 확장자가 없는 AASA는 편집기가 JSON으로 봐 주지 않아 문법 오류가 눈에 안 띈다.
  // 깨진 채로 올라가도 200으로 서빙되므로, 여기서 막지 못하면 며칠 뒤에 드러난다.
  for (const env of ENVS) {
    assert.ok(readWellKnown(env, 'assetlinks.json'));
    assert.ok(readWellKnown(env, 'apple-app-site-association'));
  }
});

test('prod assetlinks는 릴리스 지문 하나만 든다', () => {
  const statements = readWellKnown('prod', 'assetlinks.json');
  assert.equal(statements.length, 1);
  const target = statements[0].target;
  assert.deepEqual(statements[0].relation, ['delegate_permission/common.handle_all_urls']);
  assert.equal(target.namespace, 'android_app');
  assert.equal(target.package_name, 'com.accentury.app');
  assert.deepEqual(target.sha256_cert_fingerprints, [RELEASE_FINGERPRINT]);
});

test('staging assetlinks는 릴리스 + 디버그 지문 둘을 든다', () => {
  // 디버그 지문은 팀이 릴리스 전에 실제 링크 탭 흐름을 밟아 보라고 staging에만 있다.
  // prod에 새어 들어가면 그 맥에서 서명한 아무 빌드나 운영 도메인 링크를 가져간다.
  const statements = readWellKnown('staging', 'assetlinks.json');
  assert.equal(statements.length, 1);
  const fingerprints = statements[0].target.sha256_cert_fingerprints;
  assert.equal(fingerprints.length, 2);
  assert.ok(fingerprints.includes(RELEASE_FINGERPRINT));
});

test('두 환경의 AASA가 같은 appID 하나만 든다', () => {
  for (const env of ENVS) {
    const details = readWellKnown(env, 'apple-app-site-association').applinks.details;
    assert.equal(details.length, 1);
    assert.deepEqual(details[0].appIDs, [APP_ID]);
  }
});

/** AASA의 components에서 경로 집합을 뽑는다. */
function aasaPaths(env) {
  const details = readWellKnown(env, 'apple-app-site-association').applinks.details;
  return new Set(details[0].components.map((component) => component['/']));
}

/** AndroidManifest의 `android:path` 집합. XML 파서를 끌어오지 않고 속성만 긁는다. */
function manifestPaths() {
  const xml = readFileSync(join(REPO_ROOT, 'app', 'src', 'main', 'AndroidManifest.xml'), 'utf8');
  const matches = xml.matchAll(/android:path="([^"]*)"/g);
  return new Set(Array.from(matches, (match) => match[1]));
}

test('AASA와 매니페스트의 경로 집합이 같다', () => {
  // 진입 경로는 세 곳(매니페스트, AASA, 양 플랫폼 parseAppLink)에 흩어져 있고, 한쪽만 고쳐도
  // 빌드와 단위 테스트는 조용한데 링크만 죽는다. parseAppLink 쪽은 AppLinkTest·AppLinkTests가
  // 매니페스트와 대조하고 있으므로, 여기서 AASA를 그 매니페스트에 묶으면 셋이 한 줄로 이어진다.
  const expected = new Set(['/t', '/t/']);
  assert.deepEqual(manifestPaths(), expected);
  for (const env of ENVS) {
    assert.deepEqual(aasaPaths(env), expected);
  }
});
