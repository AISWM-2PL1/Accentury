# 개인정보처리방침 정적 호스팅 (KAN-133)

마이크를 쓰는 앱은 Play와 App Store 심사 모두 개인정보처리방침 URL을 요구하고, 그 URL 뒤에는
실제 문서가 있어야 한다 (FR-DP-06, KAN-2에서 "동의 화면은 범위 제외하되 이 고지는 별도 유지"로
남겨 둔 항목). 이 디렉터리는 그 문서가 사는 자리다.

```
infra/privacy/privacy.html    각 환경의 웹 S3 버킷 루트에 privacy.html로 올라간다
```

## 확정 URL

| 환경 | URL |
| --- | --- |
| prod | `https://accentury.app/privacy.html` |
| staging | `https://staging.accentury.app/privacy.html` |

스토어 등록 정보(KAN-174 Play, KAN-175 App Store)와 앱 내 링크(KAN-177), 출시 검증(KAN-39)이
참조하는 주소는 prod 쪽이다. **이 주소는 바뀌지 않는다.** 본문이 바뀌어도 URL은 그대로다.

## 왜 `/privacy`가 아니라 `/privacy.html`인가

CloudFront의 SPA 재작성 함수(`infra/modules/edge/spa-rewrite.js`)가 마지막 경로 조각에 점이
없으면 `/index.html`로 돌린다. 확장자 없는 `/privacy`로 들어가면 정책 문서 대신 앱 화면이 뜨고,
심사관이 그 화면을 보게 된다. 함수가 조용히 이기는 자리라 배포 전에는 드러나지 않는다.

확장자를 붙이면 그 규칙을 그냥 지나가므로 함수를 고칠 필요가 없고, "본문 교체는 S3 업로드만"이라는
AC도 그대로다. 확장자 없는 URL이 꼭 필요해지면 `/.well-known/`처럼 함수에 예외 한 줄을 넣는
대안이 있다 (KAN-133 코멘트, 2026-09-01).

이 전제는 `infra/modules/edge/spa-rewrite.test.mjs`가 붙들고 있다. 재작성 규칙을 고치다가
`/privacy.html`이 index.html로 넘어가면 그 테스트가 먼저 깨진다.

## 게시

```bash
scripts/publish-privacy.sh staging
scripts/publish-privacy.sh prod
```

S3에 올리고 CloudFront를 무효화한 뒤 도메인으로 실제 응답을 받아 상태, content-type, 내용까지
대조한다. 멱등이라 몇 번 돌려도 된다.

**웹 배포와 무관하게 이 파일만 갈아 끼울 수 있다.** `web-deploy.yml`은 `aws s3 sync`를
`--delete` 없이 쓰고 배포 역할에는 `s3:DeleteObject` 자체가 없어서
(`infra/modules/deploy/main.tf`), 여기서 올린 `privacy.html`은 이후의 웹 배포에 지워지지 않는다.
반대로 이 스크립트는 그 키 하나만 올리므로 웹 번들을 건드리지 않는다.

필요한 권한은 해당 버킷의 `s3:PutObject`, 배포의 `cloudfront:CreateInvalidation`과
`cloudfront:GetInvalidation` 셋이다. `GetInvalidation`은 스크립트가 무효화 완료를 기다릴 때
(`aws cloudfront wait invalidation-completed`) 폴링에 쓴다. 빼면 업로드와 무효화가 이미
일어난 뒤에 AccessDenied로 죽어서, 게시는 됐는데 명령은 실패한 애매한 상태로 남는다.
`s3:DeleteObject`는 필요 없다.

## 본문은 확정됐다 (KAN-176)

`privacy.html`은 더 이상 자리표시자가 아니다. KAN-176이 14개 절의 확정 본문을 넣고 검색 노출을
막던 `<meta name="robots" content="noindex">`를 지웠다. 이 티켓(KAN-133)이 마련한 게시 경로는
그대로고, 바뀐 것은 그 경로로 올라가는 내용뿐이다.

본문을 고칠 때 순서는 셋이다.

1. `infra/privacy/privacy.html`의 `<body>` 안을 고친다.
2. `node --test 'infra/privacy/*.test.mjs'` 를 돌린다. 디렉터리 경로를 그냥 넘기면 안 된다 —
   node 22.6부터 `--test`의 위치 인자가 glob으로 해석돼 `MODULE_NOT_FOUND`로 죽는다.
3. `scripts/publish-privacy.sh <env>` 로 게시한다.

`privacy.test.mjs`는 페이지가 홀로 서는 조건(외부 CSS·글꼴·스크립트 0)과 법정 필수 절, 그리고
본문이 약속한 핵심 수치(음성 즉시 삭제, 임시 파일 30분, 세션·결과 24시간, 서울 리전, 광고 절과
동의·거부 경로)를 붙든다. 코드가 바뀌어 문서가 거짓이 되기 전에 이 테스트가 먼저 깨지라는 것이
목적이다. CI에서는 `.github/workflows/test.yml`의 `edge-test` 잡이 돌리고, 그 잡의 paths 필터에
`infra/privacy/**`가 들어 있다.

페이지는 외부 CSS, 외부 글꼴, 스크립트를 하나도 쓰지 않는다. `web/` 번들이 어떻게 바뀌든
이 파일 하나로 렌더링이 끝나야 "S3 업로드만으로 교체"가 성립하기 때문이다.

### prod 게시 게이트

**staging은 지금 그대로 게시해도 된다.** prod는 아래가 닫히기 전까지 게시하지 않는다.

- ~~**광고 사업자 확정**~~ — 닫혔다 (2026-09-11, KAN-196 1단계). 1차 배포에 맞춤형 광고를
  넣기로 한 2026-09-07 팀 결정(KAN-196 앱, KAN-197 웹) 뒤 사업자가 Google LLC의 Google AdMob으로
  정해져, 2항 제3자 제공 표, 4항 국외 이전, 10항 광고의 「확정 후 기재」 자리표시자를 전부
  채웠다.
- **시행일** — 본문 머리(`시행일: 정식 게시일에 기재합니다`)와 14항, 두 자리를 게시 당일
  날짜로 바꾼다.
- **스토어 답안 일치** — KAN-174(Play 데이터 안전)와 KAN-175(App Store 라벨)의 답이 광고
  도입 후 기준으로 갱신돼 있어야 한다.

사업자 확정 전에는 자리표시자를 테스트로 막지 않았다 — 막으면 확정 전 단계의 본문을 커밋할 수
없어 오히려 문서가 코드보다 뒤처진다. 확정 뒤로는 반대로 「확정 후 기재」가 되살아나는 쪽을
`privacy.test.mjs`가 막는다.

### 근거 장부

본문의 문장 하나하나가 어떤 코드·티켓·결정을 근거로 삼았는지, 코드가 바뀌면 본문의 어디를
고쳐야 하는지는 [`docs/wiki/privacy-policy.md`](../../docs/wiki/privacy-policy.md)에 있다.
자리표시자 목록의 행 번호와 스토어 신고 대조표도 그쪽이다.
