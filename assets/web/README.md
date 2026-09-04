# 웹 링크 미리보기·파비콘·manifest 자산 (KAN-179)

브라우저 탭 아이콘, iOS "홈 화면에 추가" 아이콘, web manifest, 그리고 **링크를 사람이 붙여 넣었을 때
뜨는 미리보기 카드**(OG 이미지)가 여기서 나온다.

여기에는 원본이 없다 — `build.py`가 앱 아이콘 도상(`assets/app-icon/source.png`)과 등급 캐릭터
원본(`assets/characters/wannabe/source.png`)에서 만든다. 산출물은 `web/public/`에 있고 **손으로
고치지 않는다**. 도상이나 캐릭터를 바꾸려면 그쪽 원본을 갈아끼우고 그쪽 `build.py`를 돌린 다음
이것을 돌린다.

## 산출물

| `web/public/` 파일 | 규격 | 소비처 |
| --- | --- | --- |
| `favicon-v1.svg` | 192px 래스터를 품은 SVG | 브라우저 탭 |
| `apple-touch-icon-v1.png` | 180×180 RGB, 크림 꽉 참 | iOS Safari "홈 화면에 추가" |
| `icon-192-v1.png` · `icon-512-v1.png` | RGB, 크림 꽉 참 | manifest `icons` |
| `og-card-v1.png` | 1200×630 RGB, 400KB 이하 | `og:image` · `twitter:image` |
| `manifest-v1.webmanifest` | JSON | `<link rel="manifest">` |

`web/index.html`이 이 파일들을 절대 URL·절대 경로로 가리킨다. 그 참조가 실제 파일과 어긋나면
`web/src/webAppMeta.test.ts`가 깨진다.

## 왜 파일명에 `-v1`이 붙는가

`web-deploy.yml`(KAN-127)은 index.html을 뺀 `dist/` 전부를
`Cache-Control: public, max-age=31536000, immutable`로 S3에 올리고, CloudFront 무효화 대상은 `/`와
`/index.html` 둘뿐이다. `public/`의 파일은 Vite가 해시를 붙이지 않고 이름 그대로 내보내므로, 내용을
바꿔도 브라우저와 CDN이 1년 동안 옛 파일을 준다.

그래서 **교체 = 파일명 변경**이다:

1. `build.py`의 `VERSION`을 올린다 (`v1` → `v2`)
2. 스크립트를 돌린다 — 새 이름으로 파일이 생긴다
3. `web/index.html`의 참조를 새 이름으로 바꾸고 옛 파일을 지운다

index.html만 `no-cache`라, 참조가 바뀌는 즉시 새 이름이 나간다. `manifest`도 같은 이유로 버전을
문다 — 아이콘 파일명이 그 안에 들어 있어서, manifest가 1년 캐시에 박히면 아이콘을 갈아도 옛
이름을 계속 가리킨다.

옛 파일을 `git rm`으로 지워도 S3에는 남는다(`web-deploy.yml`이 `sync --delete`를 쓰지 않고 역할에
`DeleteObject` 권한도 없다). 옛 index.html을 아직 든 브라우저가 옛 아이콘을 받아야 하므로 그게 맞고,
정리가 필요하면 사람이 콘솔에서 지운다.

## 등급 공유 카드(`assets/share/`)와 무엇이 다른가

| | `assets/share/*.png` (KAN-30) | `web/public/og-card-v1.png` (KAN-179) |
| --- | --- | --- |
| 누가 본다 | 결과를 공유한 사람의 **수신자** | 링크가 붙여 넣어진 자리를 지나가는 사람 |
| 무엇을 그린다 | 그 사람이 받은 **등급** 5종 | 고정 한 장 — 등급을 말하지 않는다 |
| 규격 | 800×400 (카카오 피드 카드) | 1200×630 (OG 표준 1.91:1) |
| 배포 | `scripts/publish-share-assets.sh` → `s3://.../share/` | 웹 번들과 함께 `web-deploy.yml` |

SPA라 `/t?c=...`도 같은 `index.html`이 응답한다 — 등급별로 다른 OG를 내보내려면 서버 렌더가 필요해서,
프로토타입에서는 고정 카드 한 장으로 간다.

캐릭터는 **사투리 호소인(`wannabe`)** 한 종이다. 손 마이크와 확성기가 그림에 있어 "말해서 재는
테스트"가 글자 없이도 읽히고, 5등급 중 가운데라 카드가 특정 결과를 암시하지 않는다.

## 파비콘이 벡터가 아닌 이유

`favicon-v1.svg`는 모서리와 배경만 벡터이고 도상은 원본에서 뽑은 래스터를 data URI로 안는다.
도상 정본이 생성 모델 산출물(래스터)이라 벡터본이 없고, 손으로 다시 그리면 앱 아이콘과 웹
파비콘이 서로 다른 원본을 갖게 된다 — KAN-178이 "원본 한 장에서 전부"로 정리한 것을 웹에서
되돌리는 셈이다. 파비콘은 탭·즐겨찾기에서 최대 64px로 그려지므로 192px 래스터면 2배 이상 남는다.

## 재생성

```
/Users/iseongju/accentury/.venv/bin/python assets/web/build.py
```

Pillow·numpy·scipy가 필요하다(위 venv에 있다). 끝에 검증표를 찍고 하나라도 FAIL이면 종료 코드 1이다.
보는 것: 정확한 픽셀 크기·모드·모서리 색(크림)·용량 상한·manifest가 적은 아이콘이 실제로 옆에 있는지.

카드에 박히는 문구는 그리기 전에 Jua cmap과 대조한다(`assets/screenshots/build.py`의
`assert_glyphs`). Pillow는 글리프가 없어도 조용히 두부(□)를 그려서, 검증표가 전부 통과한 그림이
바깥에서만 깨진 적이 있다 (KAN-178 §6 — 가운뎃점 U+00B7). 문구를 고치면 여기서 막힌다.

키잉과 종이색 정규화는 옆 스크립트에서 그대로 가져다 쓴다 — `build.py`가
`assets/app-icon/build.py`와 `assets/characters/build.py`를 모듈로 읽는다. 두 스크립트의 키잉은
이름만 같고 알맹이가 달라서(아이콘은 흰 배경 + 도상 안 종이, 캐릭터는 배경이 곧 종이색) 아이콘은
아이콘 쪽, 캐릭터는 캐릭터 쪽 함수를 쓴다.
