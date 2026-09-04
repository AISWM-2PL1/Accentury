# 앱 아이콘 원본 (KAN-178)

Android 런처(adaptive icon)·iOS `AppIcon`·스플래시·Play/App Store 아이콘에 쓰는 도상의 **원본**이 여기 있다.
`build.py`가 이 원본 한 장에서 플랫폼별 파생본을 전부 만든다 — 파생본은 손으로 고치지 않는다.

## 현재 상태 — 임시 확정 C2 (2026-09-03)

`candidates/c2`(가로 "ㅅㅌㄹ" 세 글자, 잉크 종이 조각 + 크림 스티커 림 + 오프셋 그림자)를 `source.png`로
승격했다. **팀 결정 전 임시 확정이다** — 다른 도상으로 정해지면 `source.png`를 그 파일로 갈아끼우고
`build.py`를 다시 돌린다. 스크립트가 `source.png` 하나만 읽으므로 그 밖에 손댈 것은 없다.

| 후보 | 도상 | 원본 | 비고 |
| --- | --- | --- | --- |
| `c2` | "ㅅㅌㄹ" 세 글자, 잉크 종이 조각 + 크림 스티커 림 + 오프셋 그림자 | `candidates/c2/source.png` | **채택(임시)**. 원본은 흰 배경(생성 편차). `c2/cream.png`가 배경을 크림 `#F3ECD9`로 정규화한 미리보기 |
| `d3` | 한 문장의 피치 곡선(F0 contour) 한 획, 같은 종이 구성 | `candidates/d3/source.png` | 원본 배경 `#F6EDDB`(+3). `d3/cream.png`가 정규화 미리보기 |

두 후보를 런처 크기(400/144/96/72/48px)로 나란히 놓은 비교 시트가 `candidates/compare.png`다(정규화본 기준).
`<code>/cream.png`은 테두리에서 잰 배경색과 가까운(채널 차 ≤14) 바깥 연결 영역만 크림으로 바꾼 것이다 —
스티커 림·그림자·잉크는 손대지 않는다. `build.py`가 같은 규칙을 정식으로 구현한다.
결정은 노션 「FE 개발 중 진행 상황 정리」 §1-12에 올렸다(비교 시트·원본 2장 첨부). 탈락본은 `candidates/`에
그대로 둔다(이력).

시안 이력(폐기, 레포에 없음): A 말풍선 속 곡선 → B 호소인 얼굴 → C 가로 ㅅㅌㄹ → C3a 테두리 없음 /
C3b 얇은 테두리(원복) → D 큰 곡선(N처럼 읽힘) → D2 곡선 + 점선 가이드(점선 제거) → E C2+D3 합성(곡선 뒤·글자 앞, Codex 2회 + 로컬 합성 6종 — 글자가 곡선을 가려 폐기, 후보는 C2·D3 두 개로 유지).

## 앱 표시 이름

**Accentury** (2026-09-02 확정). Android `app/src/main/res/values/strings.xml`의 `app_name`,
iOS `Info-Debug.plist`·`Info-Release.plist`의 `CFBundleDisplayName`, 스토어 등록 정보(KAN-174·KAN-175)의
앱 이름이 전부 이 값이어야 한다. Play 피처 그래픽에 박히는 글자도 같은 값이다(`build.py`의 `FEATURE_TITLE`).

## 폴더 구성

```
source.png            채택 도상 원본, 생성 도구가 뽑은 그대로 (1024×1024, 흰 배경)
prompt.md             그 그림을 뽑은 프롬프트 전문 (Codex에 넘긴 파일 그대로)
build.py              파생본 생성 스크립트
candidates/           후보 이력 (c2 · d3 · compare.png) — 여기서는 파생본을 만들지 않는다
store/                스토어 업로드용 파생본 (생성물)
```

## 파생본 (생성물, 손으로 고치지 않는다)

`build.py`가 실행마다 덮어쓴다. 파일을 바꿔야 하면 스크립트의 상수를 고치고 다시 돌린다.

**Android — adaptive icon** (`app/src/main/res/`)

- `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png` — 108dp 캔버스(108/162/216/324/432px),
  가운데 66dp 안전 영역 안쪽(여백 2dp, 실효 62dp)에 도상, 투명 배경
- `mipmap-<density>/ic_launcher_monochrome.png` — 같은 캔버스·같은 안전 영역. 잉크 피복률만 알파로 남긴
  흰 실루엣(Android 13 테마 아이콘). 스티커 림과 오프셋 그림자는 뺀다 — 단색으로 칠하면 도상이 두 겹으로 보인다
- `mipmap-anydpi/ic_launcher.xml` · `ic_launcher_round.xml` — `<adaptive-icon>`. 배경은 drawable이 아니라
  **색 리소스** `@color/ic_launcher_background`다
- `values/colors.xml`의 `ic_launcher_background` = `#F3ECD9` — 이 줄만 **손으로 관리**한다(한 파일에 다른 색도
  있어서). `build.py`는 값이 맞는지 검증만 한다

**Android — 레거시 런처 아이콘**

- `mipmap-<density>/ic_launcher.png` · `ic_launcher_round.png` — 48dp(48/72/96/144/192px), 크림 배경을 꽉 채우고
  도상 폭 80%(원형은 72% — 원 마스크가 모서리를 자른다). adaptive를 못 읽는 런처가 이걸 쓴다

**Android — 스플래시**

- `drawable-<density>/ic_splash.png` — 288dp 캔버스(288/432/576/864/1152px), 가운데 192dp 원 안에 도상,
  투명 배경. SplashScreen API의 "배경 없는 아이콘" 규격이다. 창 배경은 `themes.xml`의
  `Theme.Accentury.Starting`이 `@color/ic_launcher_background`로 깐다

**iOS** (`ios/Accentury/Assets.xcassets/`)

- `AppIcon.appiconset/AppIcon-1024.png` — 1024×1024, **알파 없음**(RGB), 크림 배경을 꽉 채우고 도상 폭 78%.
  `Contents.json`은 1024 universal 한 항목 그대로다
- `LaunchIcon.imageset/` — `LaunchIcon.png`(200×200) · `@2x`(400) · `@3x`(600), 투명 배경 + `Contents.json`
- `LaunchBackground.colorset/Contents.json` — sRGB `0.953/0.925/0.851`(= `#F3ECD9`), universal 하나.
  다크 대응 항목은 없다(앱이 라이트 고정, 정본 §7)
- 두 이름은 `Info-Debug.plist`·`Info-Release.plist`의 `UILaunchScreen` 딕셔너리가 `UIColorName`·`UIImageName`으로
  참조한다. 값은 에셋 카탈로그의 **이름**이지 파일명이 아니다

**스토어** (`store/`)

- `play-icon-512.png` — 512×512, 알파 없음, 1MB 이하. Play Console 앱 아이콘
- `play-feature-1024x500.png` — 1024×500. 좌측 도상 + 우측 "Accentury" 종이 조각(Jua, 잉크 테두리,
  오프셋 그림자) + 태그라인 "경남 사투리 레벨 테스트"(Jua, `#6B6459`). 네 변 여백 6%. 다른 문구는 넣지 않는다
- `app-store-icon-1024.png` — iOS 1024 아이콘 사본. App Store Connect 업로드용 편의 파일이다

## 재생성

```
python3 assets/app-icon/build.py
```

Pillow·numpy·scipy가 필요하다 — 없으면 가상환경을 만들어 `pip install pillow numpy scipy`로
넣고 그 안에서 돌린다. 끝에 규격 검증표를 찍고, 하나라도 FAIL이면 종료 코드 1이다.
스크립트가 하는 일:

1. **배경 키잉** — 테두리 평균색을 배경으로 잡고, 그 색과 가까운(채널 차 ≤14) 픽셀 중 **바깥에서 이어진
   영역만** 투명으로 만든다. 도상 안쪽의 같은 색 면은 남는다. 스티커 림은 배경보다 채널 최대 29 어두워서
   벽이 되고, 그 벽의 작은 틈으로 새는 것은 침식 3px로 끊는다. 경계 픽셀은 배경 거리로 알파를 펴고 섞인
   색을 되돌린다. 여기까지가 `assets/characters/build.py`와 같은 코드다
2. **종이색 정규화** — 도상 안 밝은 면의 중앙값(`#FBF3E3`)을 기준색으로 잡고 그 색과 가까운 픽셀만 정확히
   `#F3ECD9`로 옮긴다. 캐릭터 쪽은 배경이 곧 종이색이라 휘도 램프로 됐지만, 여기는 배경이 흰색이고 종이가
   도상 안에만 있어서 기준이 다르다. 휘도 램프를 쓰면 오프셋 그림자(휘도 ~206)까지 밀려 이미 토큰 값인
   `#CFC5AA`에서 벗어난다. **원본은 생성 결과 그대로 두고 손대지 않는다** — 색 보정은 파생본이 책임진다
3. **알파 bbox 트림** → 도상 940×620
4. **기본 아이콘 잔재 삭제** — Android Studio가 깔아 둔 `mipmap-*/ic_launcher*.webp`와
   `drawable/ic_launcher_{background,foreground}.xml`. 남기면 같은 이름의 리소스가 둘이 된다
5. **플랫폼별 배치** — 위 파생본 목록대로. 축소는 알파를 미리 곱해서 한다(그냥 줄이면 투명 픽셀의 검정이
   경계로 번진다)
6. **검증표** — 파일 존재·정확한 픽셀 크기·알파 유무·모서리 색·adaptive 전경 bbox가 66dp 안전 영역 안인지·
   스플래시 bbox가 192dp 원 안인지·용량 상한·잔재 0개. 39행 전부 PASS여야 한다

## 생성 방법 (출처 기록)

- **도구**: Codex CLI 0.145 내장 `image_gen`(gpt-image-2). API 키·MCP 없이 기본 모드. 캐릭터(`assets/characters/README.md`)와 같은 경로.
- **참고 이미지**: 외부 이미지 없음. `-i`로 첨부한 것은 직전 라운드의 자체 산출물뿐(자기 참조).
- **실행**:
  ```
  codex exec --skip-git-repo-check --sandbox workspace-write -C <출력 폴더> -i ref.png - < prompt.md
  ```
  프롬프트는 반드시 stdin(`-`)으로 넘긴다(`-i`가 가변 인자라 인자로 주면 멈춘다).
- **색**: 앱 토큰 고정 — 종이 `#F3ECD9`, 그림자 `#CFC5AA`, 잉크 `#1C1A17`(`docs/wiki/design-tokens.md`).
- **폰트**: 피처 그래픽의 글자는 Jua(`app/src/main/res/font/jua_regular.ttf`, SIL OFL 1.1)다. 라이선스 문제 없음.
- **규격 요구(티켓)**: Android adaptive icon은 108dp 캔버스의 66dp 안전 영역 안에 도상이 들어가야 하고,
  iOS는 1024×1024 알파 없는 PNG 한 장이다. 그래서 원본은 도상이 캔버스 가운데 55~80%를 차지하는 정사각형으로 뽑고,
  여백·배경은 `build.py`가 플랫폼별로 다시 잡는다.
