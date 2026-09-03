# 공유 카드 자산 (KAN-30 · KAN-162)

카카오 피드 템플릿의 카드 이미지다. 결과 등급마다 한 장이고, 백엔드가 `/v0/sessions/{id}/result`의
`share.imageUrl`로 내려준다.

여기 있는 PNG 5장은 **생성물**이다 — `assets/characters/build.py`가 캐릭터 원본에서 만든다.
손으로 고치지 말고 스크립트를 고쳐 다시 돌린다.

## 파일

| 파일 | 등급 code | 등급명 |
| --- | --- | --- |
| `outsider.png` | OUTSIDER | 외지인 |
| `traveler.png` | TRAVELER | 여행객 |
| `wannabe.png` | WANNABE | 사투리 호소인 |
| `honorary.png` | HONORARY | 명예주민 |
| `native.png` | NATIVE | 경남 토박이 |

파일명은 백엔드 `application.yml`의 `accentury.result.tiers.<code>.image-url` 파일명과 같다 —
업로드하는 쪽이 이름을 다시 정하지 않아도 되게 맞춰 뒀다.

## 구성·규격

- **800×400 PNG** (2:1, 카카오 피드 카드 권장 비율), 각 **300KB 이하** (지금은 70~140KB).
- 배경은 앱 종이색 `#F3ECD9`. 좌측에 등급 캐릭터, 우측에 등급명(Jua, 잉크 테두리 종이 조각) +
  "Accentury · 경남 사투리 레벨 테스트". 순위·점수는 싣지 않는다.
- **5MB 이하**, **https 필수** — 카카오가 이미지를 자기 서버로 가져가는 조건이다.
- 이미지를 올린 도메인을 **카카오 개발자 콘솔에 등록**해야 카드가 그려진다. 등록되지 않은
  도메인이면 카드가 이미지 없이 나간다.
- 점수는 싣지 않는다. 수신자는 남의 결과를 보는 게 아니라 자기 테스트를 새로 응시한다 (KAN-30 요구).

## 왜 `web/public/`이 아닌가

웹 배포(`web-deploy.yml`)가 `dist/`를 `Cache-Control: immutable`, 1년으로 S3에 올린다. 그 경로에
두면 자산을 교체해도 1년 동안 옛 이미지가 그대로 나간다. 공유 자산은 웹 번들과 수명이 다르므로
배포 경로도 다르다.

## 게시 (KAN-132)

```
scripts/publish-share-assets.sh staging     # 또는 prod
```

환경의 웹 S3 버킷 `share/<tier>.png`로 올리고 CloudFront `/share/*`를 무효화한 뒤 도메인으로
200 `image/png`와 내용 일치를 확인한다. 백엔드는 등급마다 URL을 적지 않고 기준 URL 하나
(`accentury.result.asset-base-url`, 배포에서는 SSM `ACCENTURY_RESULT_ASSETBASEURL` =
`https://<도메인>/share`)에 등급 code 소문자와 `.png`를 붙여 `share.imageUrl`을 만든다 - 그래서
위 표의 파일명이 곧 계약이다. 캐릭터가 바뀌면 다시 만들어 이 스크립트만 돌리면 되고 서버와
앱 배포는 없다. 자세한 것은 `infra/README.md` "등급 공유 이미지".

## 재생성

```
/Users/iseongju/accentury/.venv/bin/python assets/characters/build.py
```

규격·색·문구는 전부 `build.py` 상단 상수다. 캐릭터 그림 자체를 바꾸려면 `assets/characters/README.md`.
