# 웹 링크 미리보기·파비콘·manifest (KAN-179)

붙여 넣은 주소가 카드로 그려지고, 브라우저 탭과 홈 화면에 확정 아이콘이 서게 하는 배선을 적는다.
자산을 **어떻게 만드는지**는 `assets/web/README.md`가 갖고, 여기는 코드 쪽 — 무엇이 왜 그렇게
배선돼 있고 어떻게 확인하는지 — 만 다룬다.

- 티켓: **KAN-179** (선행 KAN-178 도상 정본, KAN-162 캐릭터, KAN-161 팔레트, KAN-127 배포·캐시)
- 배선: `web/index.html` `<head>` · `web/public/` · 계약 테스트 `web/src/webAppMeta.test.ts`

## 1. 무엇을 고치는가

카카오 SDK 공유(KAN-30)는 **앱이 카드를 직접 만들어 보낸다** — 제목·이미지·버튼을 피드 템플릿에
실어 보내므로 OG 태그를 읽지 않는다. 그래서 OG가 없어도 그 경로는 멀쩡했고, 없다는 것이 오래
안 보였다.

빈 카드가 나가는 것은 **링크를 사람이 붙여 넣는 경로**다:

- iOS OS 공유 시트(KAN-108)가 내보내는 텍스트 링크 한 줄
- 카톡 안에서 받은 링크를 다른 방으로 재전달
- 슬랙·인스타 DM, 중간평가 자료에 적힌 주소

이때는 받는 쪽 앱이 문서를 긁어 OG를 읽는다. 태그가 없으면 제목도 이미지도 없는 회색 칸이 되고,
브라우저 탭은 기본 지구본, "홈 화면에 추가"는 첫 글자 타일이 된다.

**등급별로 다른 카드는 이 구조로 불가능하다.** SPA라 `/t?c=...`도 같은 `index.html`이 응답하고,
OG는 스크립트가 돌기 전에 읽히므로 클라이언트에서 갈아 끼울 수 없다. 서버 렌더나 엣지 치환이
필요해서, 프로토타입은 등급을 말하지 않는 고정 카드 한 장으로 간다.

## 2. 파일과 배선

| `web/public/` | `<head>` 참조 | 소비처 |
| --- | --- | --- |
| `og-card-v1.png` (1200×630) | `og:image` · `og:image:{width,height,alt}` | 카톡·슬랙·DM의 링크 카드 |
| `favicon-v1.svg` | `<link rel="icon" type="image/svg+xml">` | 브라우저 탭 |
| `apple-touch-icon-v1.png` (180×180) | `<link rel="apple-touch-icon">` | iOS Safari "홈 화면에 추가" |
| `icon-192-v1.png` · `icon-512-v1.png` | manifest `icons` | Android 홈 화면 바로가기 |
| `manifest-v1.webmanifest` | `<link rel="manifest">` | 이름·아이콘·테마색 |

전부 `assets/web/build.py`가 앱 아이콘 도상(`assets/app-icon/source.png`)과 호소인 캐릭터
(`assets/characters/wannabe/source.png`)에서 만든다 — **웹에만 있는 원본은 없다.** 도상이 팀 결정으로
바뀌면(KAN-178 §7) 그쪽 원본을 갈고 두 스크립트를 차례로 돌린다.

`manifest`의 `display`는 **`browser`**다. `standalone`으로 두면 크롬이 "앱 설치" 배너를 띄워
스토어 설치(KAN-174·175)와 두 갈래가 되고, 주소창 없는 창에서 열려 공유·새로고침 경로까지
달라진다. 여기서 manifest가 하는 일은 홈 화면에 추가했을 때의 이름과 아이콘뿐이다.

`theme-color`와 manifest의 `theme_color`·`background_color`는 셋 다 `--color-background`(크림)와
같은 값이어야 한다 — 갈리면 스크롤 끝에서 주소창 밑에 다른 색 띠가 보인다. 계약 테스트가
`tokens.css`에서 값을 읽어 대조한다.

## 3. 절대 URL과 도메인

`og:url`·`og:image`는 **절대 URL이어야 한다.** 상대 경로면 카카오가 이미지를 자기 서버로 가져가지
못해 카드가 글자만 남는다(카카오는 이미지를 5MB 이하·https로 받아 캐시한다).

도메인은 `https://accentury.app`으로 **박혀 있다.** `web-deploy.yml`은 staging과 prod가 같은 빌드를
쓰고 환경 변수를 주지 않는다 — "산출물이 환경을 몰라야 한다"가 KAN-127의 결정이다. 그래서
staging에 올라간 문서도 prod 주소를 가리키는데, 공유되는 주소가 원래 prod 하나뿐이라 그게 맞는
값이다. 환경별로 갈라야 할 이유가 생기면 빌드 시 주입이 아니라 엣지 치환을 먼저 본다 — 주입을
켜는 순간 두 환경의 산출물이 갈려 "staging에서 통과한 것"이 prod의 근거가 아니게 된다.

`/.well-known/` 밖의 확장자 있는 경로는 SPA 재작성 함수(KAN-126)가 손대지 않으므로
`/og-card-v1.png`·`/manifest-v1.webmanifest`는 그대로 S3 객체에 닿는다.

## 4. 캐시 — 왜 파일명에 `-v1`이 붙는가

`web-deploy.yml`(KAN-127)은 `index.html`을 뺀 `dist/` 전부를
`Cache-Control: public, max-age=31536000, immutable`로 올리고, CloudFront 무효화 대상은 `/`와
`/index.html` 둘뿐이다. 해시가 붙는 번들 자산은 이름이 매번 바뀌니 이 규칙이 맞지만, **`public/`의
파일은 Vite가 이름을 그대로 내보낸다** — 내용을 바꿔도 브라우저와 CDN이 1년 동안 옛 파일을 준다.

그래서 **교체 = 파일명 변경**이다:

1. `assets/web/build.py`의 `VERSION`을 올린다 (`v1` → `v2`)
2. 스크립트를 돌린다 — 새 이름으로 파일이 생긴다
3. `web/index.html`의 참조를 새 이름으로 바꾸고 옛 파일을 `git rm` 한다

`index.html`만 `no-cache`라 참조가 바뀌는 즉시 새 이름이 나간다. manifest도 아이콘 파일명을 안고
있어 같이 버전을 문다 — manifest가 1년 캐시에 박히면 아이콘을 갈아도 옛 이름을 계속 가리킨다.

지운 파일은 S3에 남는다(`sync --delete`를 쓰지 않고 역할에 `DeleteObject` 권한도 없다). 옛
index.html을 아직 든 브라우저가 옛 아이콘을 받아야 하므로 그게 맞고, 정리는 사람이 콘솔에서 한다.

## 5. 계약 테스트가 지키는 것

`web/src/webAppMeta.test.ts`가 head와 `public/`을 **양방향**으로 맞춘다.

이 배선은 깨져도 화면에 안 나타나기 때문이다 — 파비콘 경로 오타도, 버전만 올리고 index.html을
안 고친 참조도, manifest가 사라진 아이콘을 가리키는 것도 앱은 멀쩡히 뜨고 나머지 테스트도 전부
초록불이다. 깨진 것은 링크를 붙여 넣은 사람의 화면에서만 보이고, 그때는 이미 나간 뒤다.

- OG 필수 태그가 비어 있지 않은지, `twitter:card`가 `summary_large_image`인지
- `og:url`·`og:image`가 `https://accentury.app/`으로 시작하는 절대 URL인지
- **선언한 `og:image` 크기가 실제 PNG의 IHDR과 같은지** — 값만 고치고 그림을 안 바꾸는 것을 잡는다
- head가 가리키는 자산이 `public/`에 다 있는지
- manifest가 `display: browser`이고, 아이콘의 `sizes`가 실제 픽셀과 같은지
- 테마색 셋이 `tokens.css`의 `--color-background`와 같은지
- **`public/`에 아무도 가리키지 않는 파일이 없는지** — 반대 방향만 보면 버전을 올리고 옛 파일을
  안 지운 것이 영영 안 잡힌다(새 파일은 다 있으니 초록불이다)

카드에 박히는 글자는 `assets/web/build.py`가 Jua cmap과 대조한다(`assert_glyphs`). Pillow는
글리프가 없어도 조용히 두부(□)를 그려서, 검증표가 다 통과한 카드가 카톡에서만 깨진 적이 있다
(KAN-178 §6, 가운뎃점 U+00B7).

## 6. 확인 절차

로컬 (2026-09-04 실측, `npm run build` 뒤 `npx vite preview --port 4173`):

- `public/` 6개 전부 `dist/`로 복사되고 200으로 응답한다. manifest는
  `application/manifest+json`으로 나간다
- 파비콘 SVG를 브라우저가 192×192로 디코드하고, 32×32로 그렸을 때 잉크 픽셀 152개·크림 648개,
  네 모서리는 투명하다 — data URI로 박은 래스터가 실제로 그려진다는 뜻이다
- 문서를 다시 로드해도 콘솔에 아무것도 찍히지 않는다. 크롬은 manifest 파싱 문제를 콘솔 오류로
  적으므로 침묵이 곧 "파싱 오류 없음"이다

배포 뒤 (prod에 올라간 다음):

- 카카오톡 디버거 `developers.kakao.com/tool/debugger/sharing`에 `https://accentury.app/t?c=og_test`를
  넣어 제목·설명·이미지가 있는 카드를 확인한다. 이미지를 올린 도메인이 카카오 개발자 콘솔에
  등록돼 있어야 카드에 그림이 실린다(`android-release-signing.md` "카카오 등록 상태")
- iOS Safari에서 "홈 화면에 추가" — 타일이 첫 글자가 아니라 도상이어야 한다

**카카오는 자기 OG 캐시를 따로 갖는다.** CloudFront 무효화와 별개라, 한 번 긁힌 주소는 카드를
고쳐도 옛것이 나간다. 카드를 바꿨으면 카카오 디버거에서 그 주소로 **캐시 초기화**를 눌러야 한다 —
이미지 파일명을 `-v2`로 바꿔도 페이지 URL(`https://accentury.app/t?c=...`)은 그대로라 초기화가 필요하다.

## 7. 결정 기록

- **OG 카드는 캐릭터 합성이지 새 생성물이 아니다** (2026-09-04) — 티켓은 Codex image_gen으로
  뽑으라고 적었지만, 등급 공유 카드(KAN-30)가 이미 캐릭터 원본 + Jua 조각을 합성하는 파이프라인을
  갖고 있어 같은 방식을 썼다. 생성 모델은 hex를 정확히 못 맞춰 종이색·그림자를 후보정해야 하고
  (`assets/characters/README.md`), 결과가 매번 달라 재생성이 안 된다. 캐릭터 자체는 이미
  image_gen 산출물이라 그림의 출처는 같다.
- **캐릭터는 호소인(`wannabe`)** — 손 마이크와 확성기가 "말해서 재는 테스트"를 글자 없이 알리고,
  5등급 중 가운데라 카드가 특정 결과를 암시하지 않는다.
- **`<title>`은 그대로 두고 `og:title`만 히어로 문구로** — 탭 제목과 카드 제목은 읽는 자리가 다르다.
  `<title>`을 바꾸면 브라우저 히스토리·북마크 이름까지 따라간다.
- **파비콘 SVG는 벡터가 아니다** — 모서리와 배경만 벡터이고 도상은 192px 래스터를 data URI로
  안는다. 도상 정본이 생성 모델 산출물이라 벡터본이 없고, 손으로 다시 그리면 앱 아이콘과 웹
  파비콘이 서로 다른 원본을 갖게 된다. 파비콘은 최대 64px로 그려지므로 192px면 남는다.
- **OG 카드 문구에 문항 수를 넣지 않았다** — 인트로의 `10문항`은 KAN-10 연동으로 서버 값이 될
  상수다. 1년 캐시에 박히는 그림에 그 숫자를 넣으면 화면과 카드가 조용히 갈린다.

## 8. 이월

- **등급별 OG 카드** — 서버 렌더나 CloudFront Function의 HTML 치환이 필요하다. 공유 링크에 등급이
  실려 있지 않아(`/t?c=<계측 코드>`, KAN-32) 링크 스킴부터 바꿔야 한다.
- **도상 팀 결정** — C2가 임시 확정이다(KAN-178 §1). 바뀌면 `assets/web/build.py`도 다시 돌리고
  `VERSION`을 올린다.
- **카카오 도메인 등록** — 카드에 그림이 실리려면 `accentury.app`이 카카오 개발자 콘솔에 등록돼
  있어야 한다(KAN-163 이월과 같은 항목).
