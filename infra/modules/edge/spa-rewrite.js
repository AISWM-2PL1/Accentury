// SPA 경로 재작성 (KAN-126). 기본 동작(S3 오리진)에만 붙는 viewer-request 함수.
// 확장자 없는 경로 직접 진입을 /index.html로 돌린다. /v0/*는 이 함수가 붙지 않는
// 별도 동작이라 API 오류 봉투가 치환될 일이 없다.
function handler(event) {
  var request = event.request;

  // /.well-known/ 아래는 손대지 않는다 (KAN-32 4단계). App Links 검증 파일
  // apple-app-site-association은 확장자가 없어서 아래 규칙에 걸려 index.html이 되는데,
  // 그러면 애플 CDN이 JSON 대신 HTML을 받아 Universal Link 검증이 성립하지 않는다.
  // assetlinks.json은 점이 있어 원래 영향을 받지 않지만, /.well-known/ 자체가 RFC 8615가
  // 기계 판독 메타데이터용으로 예약한 접두사라 그 아래는 통째로 예외로 둔다.
  if (request.uri.startsWith('/.well-known/')) {
    return request;
  }

  var lastSegment = request.uri.split('/').pop();
  if (!lastSegment.includes('.')) {
    request.uri = '/index.html';
  }
  return request;
}
