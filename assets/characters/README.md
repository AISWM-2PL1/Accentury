# 5등급 캐릭터 원본 (KAN-162)

결과 화면(KAN-29)과 카카오 공유 카드(KAN-30)에 쓰는 등급 캐릭터 5장의 **원본**이 여기 있다.
화면에 실제로 실리는 파일은 여기 없다 — `build.py`가 원본에서 파생본을 만든다.

| 등급 | code | 그림 | 원본 |
| --- | --- | --- | --- |
| 외지인 | `outsider` | 갈매기 2마리에 쫓겨 울며 달아남 | `outsider/source.png` |
| 여행객 | `traveler` | 튜브·하와이안 셔츠·선글라스·카메라·쪼리 | `traveler/source.png` |
| 사투리 호소인 | `wannabe` | 손 마이크·확성기·번개·"호소인" 배지 | `wannabe/source.png` |
| 명예주민 | `honorary` | 등산복·산 정상·"명예주민" 깃발·구름 | `honorary/source.png` |
| 경남 토박이 | `native` | 정장·큰 의자·경남 지도 실루엣 | `native/source.png` |

code는 백엔드 `application.yml`의 `accentury.result.tiers.<code>.image-url` 파일명과 같다.
그림의 확정 근거는 노션 「결과화면 티어 이미지」(허브 직속)이고, 지라 KAN-162의 표와 같다.

## 폴더 구성

```
<code>/source.png     생성 도구가 뽑은 원본 그대로 (1024×1536 또는 1122×1402, 균일 크림 배경)
<code>/prompt.md      그 그림을 뽑은 프롬프트 전문 (Codex에 넘긴 파일 그대로)
build.py              파생본 생성 스크립트
```

파생본(생성물, 손으로 고치지 않는다):

- `web/src/assets/characters/<code>.webp` — 결과 화면용. 폭 780px(390 CSS px의 2x), 4:5, **투명 배경**, 각 150KB 이하
- `assets/share/<code>.png` — 카카오 공유 카드. 800×400, 각 300KB 이하 (규격은 `assets/share/README.md`)

원본은 웹 번들에도 APK에도 들어가지 않는다 — Vite는 `web/src/assets/`만 번들하고, Android
빌드(`app/build.gradle.kts`)는 레포 루트 `assets/`를 참조하지 않는다.

## 재생성

```
/Users/iseongju/accentury/.venv/bin/python assets/characters/build.py
```

Pillow·numpy·scipy가 필요하다(위 venv에 있다). 끝에 규격 검증표를 찍고, 하나라도 FAIL이면
종료 코드 1이다. 스크립트가 하는 일:

1. **배경 키잉** — 테두리 평균색을 배경으로 잡고, 그 색과 가까운(채널 차 ≤10) 픽셀 중 **바깥에서
   이어진 영역만** 투명으로 만든다. 캐릭터 안쪽의 같은 크림 면은 남는다. 스티커 컷 림(검정 선
   바깥 크림 여백)은 종이 두께 선(배경보다 약 30 어두움)이 벽이 되어 살아남고, 그 선의 작은 틈으로
   새는 것은 침식 3px로 끊는다. 경계 픽셀은 배경 거리로 알파를 펴고 섞인 색을 되돌린다.
2. **종이색 정규화** — 생성 모델은 hex를 정확히 못 맞춘다(장마다 `#F3ECD9` 대비 +1~+10). 밝은
   픽셀을 토큰 값으로 델타 이동하고 잉크는 그대로 둔다. 다크 모드에서 캐릭터는 크림 종이 고정 +
   크림 halo(KAN-161 규칙)이므로 투명 배경이어야 halo가 붙는다.
3. **결과 화면 WebP** — 알파 bbox로 트림 → 4:5 캔버스 가운데(여백 3%) → 폭 780 → quality 90부터
   내려가며 150KB 아래 첫 값.
4. **공유 카드 PNG** — 좌측 캐릭터(카드 폭 40% 이내) + 우측 등급명 종이 조각(Jua, 잉크 테두리,
   오프셋 그림자 3px 4px `#CFC5AA`) + 브랜드 줄. 순위·점수는 싣지 않는다(KAN-30 — 수신자는 남의
   결과를 보는 게 아니라 자기 테스트를 하러 온다). 등급명이 길면 글자를 줄여
   조각이 잘리지 않게 한다. 300KB를 넘으면 256색 팔레트로 줄인다.

## 생성 방법 (출처 기록)

- **도구**: Codex CLI 0.145 내장 `image_gen` 도구(gpt-image-2). API 키·MCP 없이 기본 모드로 돌렸다.
  Codex가 직접 SVG로 그린 시도는 품질 미달로 폐기.
- **참고 이미지**: 우리가 앞서 뽑은 초안(노션 「결과화면 티어 이미지」 2026-08-27 버전)만 `-i`로
  첨부했다. Dribbble 등 타인 시안은 쓰지 않았다.
- **실행**:
  ```
  codex exec --skip-git-repo-check --sandbox workspace-write -C <출력 폴더> -i ref.png - < prompt.md
  ```
  `-i`는 가변 인자라 프롬프트를 인자로 주면 이미지 파일로 먹고 stdin을 기다린다(멈춤). 프롬프트는
  반드시 stdin(`-`)으로 넘긴다. `omc ask codex`는 이미지 첨부가 안 된다.
- **색**: 프롬프트에 앱 토큰을 박았다 — 종이 `#F3ECD9`, 오프셋 그림자 `#CFC5AA`, 잉크 `#1C1A17`.
  Papercut 규칙("크림 종이 위 잉크 한 색, 색조 없음", `docs/wiki/design-tokens.md`)이라 피치 채색은
  쓰지 않는다. 배경은 균일 단색(바닥띠·질감·워터마크 금지)이어야 키잉이 된다.
- **호소인 이력**: v1(단일 실루엣) → v2(조각별 겹침·종이 두께) → **v3(스티커 컷 크림 림, 채택)**.
  `wannabe/prompt.md`는 v3 프롬프트다.
- **업스케일**: 보관하지 않는다. 현재 소비처(WebP 780px·카드 800×400)는 원본 1024px로 충분하고,
  4× 본은 장당 8~14MB라 LFS 없는 레포 히스토리에 넣지 않기로 했다(2026-08-28). 인쇄·스토어
  자산이 필요해지면 Upscayl(맥 앱 내장 CLI) `digital-art-4x`로 12초에 다시 만든다:
  ```
  /Applications/Upscayl.app/Contents/Resources/bin/upscayl-bin -i <code>/source.png -o <code>/source@4x.png \
    -s 4 -m /Applications/Upscayl.app/Contents/Resources/models -n digital-art-4x -f png
  ```
- **폰트**: 그림 안 글자("호소인"·"명예주민")는 생성 모델이 그린 것이고, 공유 카드 등급명은 Jua
  (`app/src/main/res/font/jua_regular.ttf`, SIL OFL 1.1)다. 라이선스 문제 없음.
