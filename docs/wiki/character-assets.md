# 등급 캐릭터 자산 (KAN-162)

결과 화면의 5등급 캐릭터와 카카오 공유 카드가 쓰는 그림의 **정본이 어디 있고, 파생본이 어떻게
만들어지는지**를 적는다. 그림 자체의 확정 근거(구도·소품)는 노션 「결과화면 티어 이미지」와
지라 KAN-162의 표가 갖고, 이 문서는 코드 쪽 — 파일·스크립트·규칙 — 만 다룬다.

- 티켓: **KAN-162** (선행 KAN-161 Papercut 팔레트·halo 규칙)
- 소비처: 결과 화면 `web/src/result/tierAssets.ts` (KAN-29) · 공유 카드 `assets/share/<code>.png` (KAN-30) · S3 `image-url` 결선 (KAN-132)
- 자산 폴더의 실행 문서: `assets/characters/README.md` (생성 도구·프롬프트·명령 전문)

## 1. 정본과 파생본

| 층 | 위치 | 성격 |
|---|---|---|
| 확정 그림 | 노션 「결과화면 티어 이미지」 상단 "확정본 (2026-08-28)" | 사람이 보고 승인한 정본. 미채택 구버전은 같은 페이지 아래에 남긴다 |
| 원본 파일 | `assets/characters/<code>/source.png` + `prompt.md` | 생성 도구가 뽑은 그대로(1024×1536 또는 1122×1402, 균일 크림 배경). 손으로 고치지 않는다 |
| 파생본 | `web/src/assets/characters/<code>.webp` · `assets/share/<code>.png` | **`build.py`가 만든다.** 직접 편집 금지 — 고칠 것은 스크립트나 원본이다 |

`<code>`는 백엔드 `application.yml`의 `result.tiers.<code>` 키 = `image-url` 파일명 =
`tierAssets.ts`의 키(소문자 `outsider` `traveler` `wannabe` `honorary` `native`)다. 서버의 `tier.code`는
대문자이고 `tierImageFor()`가 정규화한다.

## 2. 재생성

```
/Users/iseongju/accentury/.venv/bin/python assets/characters/build.py
```

12초. 끝에 검증표(크기·용량·모서리 알파)를 찍고 하나라도 어긋나면 exit 1. 같은 원본이면 같은
바이트가 나오므로(2026-08-28 확인) 재실행 뒤 `git status`에 파생본 diff가 없어야 정상이다.

## 3. `build.py`가 하는 일

1. **배경 키잉** — 테두리 평균색을 배경으로 잡고, 그 색에 가까운 픽셀 중 바깥에서 이어진 영역만
   투명으로 만든다(flood-fill). 캐릭터 안쪽의 같은 크림 면은 남는다. 스티커 컷 림(검정 선 바깥의
   크림 여백)은 종이 두께 선이 벽이 되어 살아남고, 그 선의 틈으로 새는 것은 침식 3px로 끊는다.
   색 키만 쓰면 림이 배경과 같은 색이라 함께 사라진다 — flood-fill을 고른 이유다.
2. **종이색 정규화** — 생성 모델은 hex를 정확히 못 맞춘다(장마다 `#F3ECD9` 대비 +1~+10). 밝은 픽셀을
   토큰 값으로 델타 이동하고 잉크는 그대로 둔다. 화면 배경과 캐릭터 종이가 같은 값이어야
   앱 배경 위에서 사각 경계가 안 보인다.
3. **결과 화면 WebP** — 알파 bbox 트림 → 4:5 캔버스 → 폭 780 → 150KB 아래 첫 quality.
   투명 배경인 이유는 다크 모드다: 캐릭터는 크림 종이 고정 + 크림 2px halo(KAN-161 규칙)인데
   래스터는 투명해야 halo가 그림 가장자리에 붙는다.
4. **공유 카드 PNG** — 800×400, 좌측 캐릭터 + 우측 등급명 종이 조각(Jua) + 브랜드 줄. 순위·점수는
   싣지 않는다(KAN-30: 수신자는 자기 테스트를 하러 온다). 300KB 이하.

## 4. 결과 화면에서 쓰는 방식

- `tierAssets.ts`가 code → WebP `src`만 안다. `alt`와 등급명은 서버 `tier.name`이다 — 등급명을
  클라이언트에 두지 않는 KAN-29 결정은 그대로다.
- `<img width=780 height=975 decoding="async">` + 실패·미매핑 시 같은 슬롯에 등급명 텍스트 폴백
  (`aria-hidden`, 바로 아래 h1이 같은 이름을 읽으므로).
- 슬롯 `.illustration--result`는 폭 192 · 높이 240(4:5). 168이면 "호소인" 배지 글자가 뭉개진다.
- halo는 CSS `drop-shadow` 4방향 2px 크림 — 라이트에서는 배경과 같은 색이라 보이지 않고, 다크
  팔레트가 붙는 순간 살아난다.
- `decoding="async"`라 로드 완료 직후 한 프레임은 빈 슬롯이 찍힐 수 있다(캡처 시 주의, 결함 아님).

## 5. 생성 도구와 출처

Codex CLI 내장 `image_gen`(gpt-image-2)으로 생성했고, 참조 이미지는 우리 이전 초안뿐이다(타인
시안 없음). 색은 토큰 고정(종이 `#F3ECD9` · 그림자 `#CFC5AA` · 잉크 `#1C1A17`), 실행 명령과
`-i` 인자 함정, 호소인 v1→v3 이력, Jua OFL은 `assets/characters/README.md`에 있다. 4× 업스케일본은
보관하지 않는다 — 현재 소비처는 1024px 원본으로 충분하고 장당 8~14MB라 LFS 없는 레포에 넣지 않았다.

## 6. AC 검증 기록 (2026-08-28)

- 5등급 결과 화면·나란히·다크 halo 캡처, 공유 카드 규격 5/5, `build.py` 재실행 diff 없음,
  웹 테스트 752건·빌드 통과. 등급별 화면은 `POST /v0/sessions`로 세션을 만들고 `test_session.completed_at`
  + `test_result` 행을 DB에 직접 넣어 띄웠다(KAN-29 때와 같은 개발 통로, `?screen=result&sessionId=`).
