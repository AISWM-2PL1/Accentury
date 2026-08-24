// SPA 경로 재작성 (KAN-126). 기본 동작(S3 오리진)에만 붙는 viewer-request 함수.
// 확장자 없는 경로 직접 진입을 /index.html로 돌린다. /v0/*는 이 함수가 붙지 않는
// 별도 동작이라 API 오류 봉투가 치환될 일이 없다.
function handler(event) {
  var request = event.request;
  var lastSegment = request.uri.split('/').pop();
  if (!lastSegment.includes('.')) {
    request.uri = '/index.html';
  }
  return request;
}
