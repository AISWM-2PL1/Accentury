# 디자인 토큰 정본

Accentury 네이티브(Compose)와 웹(WebView)이 공유하는 색·타이포·간격·모션·반경의 **단일 정본**이다.
이 문서의 표가 원본이고, `Color.kt`·`Type.kt`·`tokens.css`는 그 값을 각 런타임 형태로 옮겨 적은 사본이다.

- 티켓: KAN-148(구조) · **KAN-161**(Papercut 팔레트 교체)
- 설계 근거: `ux-ui.md` §2(설계 원칙)·§5(비주얼·모션·접근성 최소선)
- 시안 출처
  - **Papercut** (현행) — Claude Code 아티팩트 「Accentury Papercut」, 정본은 그 안의 `build.mjs`
  - Figma Make 코드 번들 (KAN-148 당시, 로컬 `prototype/`) — 배치·간격·타이포는 아직 여기서 온다

## 1. 정본과 사본의 관계

| 위치 | 역할 |
|---|---|
| `docs/wiki/design-tokens.md` (이 문서) | **정본.** 값을 바꾸려면 여기부터 고친다 |
| `app/src/main/java/com/accentury/app/ui/theme/Color.kt` | 사본 — 색 |
| `app/src/main/java/com/accentury/app/ui/theme/Type.kt` | 사본 — 타이포 |
| `app/src/main/java/com/accentury/app/ui/theme/Dimens.kt` | 사본 — 간격·반경·터치 타겟·모션 |
| `web/src/tokens.css` | 사본 — 전체 (CSS 커스텀 프로퍼티) |
| `tools/check_tokens.py` | 정본과 두 사본의 색 값이 일치하는지 검사 |
| `tools/check_contrast.py` | §6 대비 표를 생성(`--write`)하고, 문서가 낡았는지 검사 |

### 갱신 절차

1. 이 문서의 값 표(§2 색, §3 타이포, §4 간격)를 고친다. §6 대비 표는 손대지 않는다 — 4번이 대신 쓴다.
2. 같은 커밋에서 `Color.kt`·`Type.kt`·`Dimens.kt`·`tokens.css`를 함께 고친다. **한쪽만 고친 커밋은 리뷰에서 반려한다** — 네이티브와 웹이 한 테스트 안에서 번갈아 나오므로 값이 갈라지면 화면 경계에서 색이 튄다.
3. `python3 tools/check_tokens.py`로 정본과 두 사본의 색 값이 일치하는지 확인한다. 한쪽만 고쳤으면 여기서 걸린다.
4. 색을 새로 추가하거나 바꿨으면 `tools/check_contrast.py`의 `PAIRS`를 같이 고치고 `python3 tools/check_contrast.py --write`를 돌린다. **§6 표는 손으로 고치지 않는다** — 스크립트가 쓰고, 인자 없이 돌리면 문서가 낡았는지까지 검사해 종료 코드 1로 떨어진다.
5. 팔레트를 다시 손봤으면 §7 "결정 기록"에 근거를 남긴다.

## 2. 색

**Papercut** — 크림 종이 위에 잉크 한 색으로 그린 그림이다. 색조는 하나도 없다.
쓰는 값이 넷뿐이라 semantic 토큰 서른다섯 개가 전부 이 넷 중 하나로 접힌다.

| 역할 | 값 | 하는 일 |
|---|---|---|
| INK | `#1c1a17` | 텍스트·선·버튼 면·곡선. 화면에서 "그려진 것"은 전부 이 색이다 |
| CREAM | `#f3ecd9` | 배경·카드·잉크 위 글자. 종이 자체 |
| PAPER_SHADOW | `#cfc5aa` | 오프셋 그림자(`3px 4px 0`)와 장식 면. 종이 그늘 |
| MUTED | `#6b6459` | 흐린 텍스트. 잉크를 옅게 쓴 것이지 회색이 아니다 |

일러스트는 테마와 무관하게 종이 `#f3ecd9` / 잉크 `#1c1a17` 고정값을 직접 쓴다 (§7의 `ILLO`).
모든 조합은 §6에서 확인했고, KAN-148에서 감수했던 여덟 자리는 이 팔레트로 전부 해소됐다.

### 라이트

| 토큰 | 값 | 용도 |
|---|---|---|
| `primary` | `#1c1a17` | 잉크. 주버튼, F0 곡선, 선택 상태 |
| `primary-foreground` | `#f3ecd9` | primary 위 텍스트 |
| `primary-dim` | `#cfc5aa` | 오프셋 종이 그림자 |
| `background` | `#f3ecd9` | 화면 배경 |
| `foreground` | `#1c1a17` | 본문 텍스트 |
| `card` | `#f3ecd9` | 카드·선택지 표면. 배경과 같은 색이고 테두리·그림자로 갈린다 |
| `card-foreground` | `#1c1a17` | 카드 위 텍스트 |
| `secondary` | `#f3ecd9` | 보조 면 |
| `secondary-foreground` | `#1c1a17` | 보조 면 위 텍스트 |
| `muted` | `#cfc5aa` | 장식·비활성 면 **전용**. 상태 표시에는 쓰지 않는다 — 크림 위 1.46:1 (§7) |
| `muted-foreground` | `#6b6459` | 보조 텍스트 |
| `accent` | `#1c1a17` | 강조 배지 면 |
| `accent-foreground` | `#f3ecd9` | accent 위 텍스트 |
| `success` | `#1c1a17` | 정답 표시. 색이 아니라 문구·아이콘이 정답을 알린다 (§7) |
| `success-foreground` | `#f3ecd9` | success 위 텍스트 |
| `success-surface` | `#f3ecd9` | 정답 선택지 배경 |
| `success-on-surface` | `#1c1a17` | 정답 선택지 텍스트 |
| `destructive` | `#1c1a17` | 오답·오류 |
| `destructive-foreground` | `#f3ecd9` | destructive 위 텍스트 |
| `destructive-surface` | `#f3ecd9` | 오답 선택지 배경 |
| `destructive-on-surface` | `#1c1a17` | 오답 선택지 텍스트 |
| `border` | `#1c1a17` | 구분선 |
| `control-border` | `#1c1a17` | 선택 가능한 컨트롤 경계 (선택지·입력) |
| `ring` | `#1c1a17` | 포커스 링 |
| `prompt-card-start` | `#f3ecd9` | 대사 카드 면. 그라디언트가 아니라 단색이라 start·end가 같다 |
| `prompt-card-end` | `#f3ecd9` | 대사 카드 면 |
| `prompt-card-foreground` | `#1c1a17` | 대사 본문 |
| `prompt-card-muted` | `#6b6459` | 대사 카드 보조 텍스트(뜻·안내) |
| `prompt-card-badge` | `#f3ecd9` | 대사 카드 위 배지 면 |
| `guide-curve` | `#1c1a17` | 가이드 F0 곡선 (점선) |
| `user-curve` | `#1c1a17` | 사용자 F0 곡선 (실선) |
| `curve-lane-surface` | `#f3ecd9` | 곡선 레인 안쪽 면 |
| `hero-start` | `#1c1a17` | 히어로 아이콘 면 |
| `hero-end` | `#1c1a17` | 히어로 아이콘 면 |
| `curve-lane-border` | `#1c1a17` | 곡선 레인 테두리 |

### 다크

**이번 티켓(KAN-161)은 다크 분기를 라이트로 고정했다. 다크 전용 팔레트는 후속 티켓이다** —
아래 표는 라이트와 값이 같고, 런타임에서 이 표를 고르는 코드는 없다 (§7).

| 토큰 | 값 |
|---|---|
| `primary` | `#1c1a17` |
| `primary-foreground` | `#f3ecd9` |
| `primary-dim` | `#cfc5aa` |
| `background` | `#f3ecd9` |
| `foreground` | `#1c1a17` |
| `card` | `#f3ecd9` |
| `card-foreground` | `#1c1a17` |
| `secondary` | `#f3ecd9` |
| `secondary-foreground` | `#1c1a17` |
| `muted` | `#cfc5aa` |
| `muted-foreground` | `#6b6459` |
| `accent` | `#1c1a17` |
| `accent-foreground` | `#f3ecd9` |
| `success` | `#1c1a17` |
| `success-foreground` | `#f3ecd9` |
| `success-surface` | `#f3ecd9` |
| `success-on-surface` | `#1c1a17` |
| `destructive` | `#1c1a17` |
| `destructive-foreground` | `#f3ecd9` |
| `destructive-surface` | `#f3ecd9` |
| `destructive-on-surface` | `#1c1a17` |
| `border` | `#1c1a17` |
| `control-border` | `#1c1a17` |
| `ring` | `#1c1a17` |
| `prompt-card-start` | `#f3ecd9` |
| `prompt-card-end` | `#f3ecd9` |
| `prompt-card-foreground` | `#1c1a17` |
| `prompt-card-muted` | `#6b6459` |
| `prompt-card-badge` | `#f3ecd9` |
| `guide-curve` | `#1c1a17` |
| `user-curve` | `#1c1a17` |
| `curve-lane-surface` | `#f3ecd9` |
| `hero-start` | `#1c1a17` |
| `hero-end` | `#1c1a17` |
| `curve-lane-border` | `#1c1a17` |

## 3. 타이포

두 벌을 쓴다.

- **Jua** — 제목·대사·등급. 번들 필수(어느 기기에도 없는 디스플레이 폰트). `app/src/main/res/font/jua_regular.ttf`(2.0MB), `web/public/fonts/Jua-Regular.woff2`(360KB). 라이선스 `LICENSES/Jua-OFL.txt`(SIL OFL 1.1).
- **본문 — 시스템 기본 산세리프.** Android 시스템 폰트가 이미 Noto Sans CJK KR이고 WebView의 `sans-serif`도 같은 폰트로 해석되므로, 네이티브와 웹이 저절로 같은 글꼴로 수렴한다. 9.9MB짜리 Noto Sans KR 가변폰트를 번들해도 타깃 기기에서 보이는 글자는 같아서 넣지 않는다.

| 토큰 | 크기 | 굵기 | 폰트 | 용도 |
|---|---|---|---|---|
| `display` | 44sp | 900 | Jua | 결과 등급 |
| `headline` | 26sp | 900 | Jua | **대사 카드** (§5 최소선 24sp 충족) |
| `title` | 22sp | 700 | Jua | 어휘 문항 질문 |
| `titleSmall` | 20sp | 700 | Jua | 화면 제목 |
| `body` | 16sp | 400 | 시스템 | 본문, 버튼 라벨 |
| `bodySmall` | 15sp | 400 | 시스템 | 선택지 |
| `label` | 14sp | 500 | 시스템 | 부가 설명 |
| `caption` | 12sp | 600 | 시스템 | 배지, 카드 보조 문구 |

행간은 대사·등급이 `1.15`, 나머지가 `1.5`.

## 4. 간격 · 반경 · 터치 타겟

4dp 배수. `space-1`=4 / `space-2`=8 / `space-3`=12 / `space-4`=16 / `space-6`=24 / `space-8`=32.

| 토큰 | 값 | 용도 |
|---|---|---|
| `radius-sm` | 12dp | 배지 |
| `radius-md` | 16dp | 버튼, 선택지 |
| `radius-lg` | 18dp | 기본값 |
| `radius-xl` | 24dp | 대사 카드 |
| `radius-full` | 9999 | 원형 |
| `touch-target-min` | **48dp** | 모든 탭 가능 요소의 최소 높이·너비 |
| `prompt-card-min-height` | 152dp | 대사 카드 (문항 길이가 달라도 카드 크기 고정) |
| `content-max-width` | 320dp | 본문 최대 폭. 넓은 화면에서 한 줄이 길어져 읽기 힘들어지는 것을 막는다 |
| `opacity-disabled` | 0.6 | 비활성·제출 중 요소 |
| `prompt-card-padding` | 22dp | 대사·질문 카드 안쪽 여백 |
| `choice-depth` | 3dp | 선택지 밑변 그림자 깊이 |
| `progress-bar-height` | 12dp | 진행바 두께 |
| `hero-icon-size` | 112dp | 인트로·권한 화면의 원형 아이콘 (w-28) |
| `curve-lane-height` | 120dp | F0 곡선 레인 하나. 시안(72px)보다 키웠다 — 실제 기기에서는 아래가 비어 곡선이 납작했다 |

## 5. 모션

`ux-ui.md` §5의 ease-out 100~300ms를 따른다. easing은 전부 `cubic-bezier(0, 0, 0.2, 1)`.

| 토큰 | 값 | 용도 |
|---|---|---|
| `duration-press` | 75ms | 버튼 눌림 |
| `duration-fast` | 150ms | 상태 색 전환 |
| `duration-base` | 300ms | 화면 전환 |
| `duration-reveal` | 600ms | 결과 리빌 (§5가 허용한 예외) |

**오프셋 종이 그림자** — 이 앱의 시각 정체성 (KAN-161). 기본 상태에서 오른쪽·아래로 `3dp 4dp` 어긋난 자리에 `primary-dim` 단색 면이 깔려 종이 한 장이 떠 있는 것처럼 보이고, 누르면 그림자가 사라지면서 본체가 그만큼 내려가 종이가 바닥에 닿는다. 전환은 `duration-press`. 컴포넌트 실구현은 KAN-161 2단계다 — 지금 코드는 아직 아래로만 떨어지는 KAN-148의 그림자를 그린다.

**모션 축소 대응**: 웹은 `@media (prefers-reduced-motion: reduce)`에서 모든 duration을 `0.01ms`로 덮는다. 네이티브는 `Settings.Global.ANIMATOR_DURATION_SCALE`이 0이면 애니메이션을 건너뛴다. 축소 상태에서도 **최종 상태는 동일** — 사라지는 정보가 없어야 한다.

### 그림자

번지지도, 흐려지지도, 비쳐 보이지도 않는다. 어긋난 자리에 `PAPER_SHADOW` 단색 면이
그대로 깔릴 뿐이다 — 오려 낸 종이가 바닥에 드리우는 그늘이다.

| 토큰 | 값 | 쓰는 곳 |
|---|---|---|
| `shadow-card` | `3px 4px 0 #cfc5aa` | 일반 카드 |
| `shadow-prompt` | `3px 4px 0 #cfc5aa` | 대사·질문 카드 |
| `shadow-hero` | `3px 4px 0 #cfc5aa` | 원형 히어로 아이콘 |
| `shadow-choice` | `3px 4px 0 #cfc5aa` | 선택지 |

넷이 같은 값인 것은 미처리가 아니라 규칙이다. 종이 한 장의 두께는 카드든 버튼이든 같고,
깊이로 위계를 만들려면 그림자가 아니라 크기·잉크 굵기를 쓴다.

## 6. 대비 검증 결과

<!-- check_contrast:begin -->
WCAG 2.1 AA 일반 텍스트 기준 4.5:1. `python3 tools/check_contrast.py --write`가 쓴 표이고,
34건 전부 기준을 넘는다 — 감수한 자리는 없다.

| 조합 | 비율 | |
|---|---|---|
| `foreground` / `background` (라이트) | 14.73 |  |
| `foreground` / `card` (라이트) | 14.73 |  |
| `muted-foreground` / `background` (라이트) | 4.96 |  |
| `muted-foreground` / `card` (라이트) | 4.96 |  |
| `primary-foreground` / `primary` (라이트) | 14.73 |  |
| `secondary-foreground` / `secondary` (라이트) | 14.73 |  |
| `accent-foreground` / `accent` (라이트) | 14.73 |  |
| `destructive-foreground` / `destructive` | 14.73 |  |
| `destructive-on-surface` / `destructive-surface` (라이트) | 14.73 |  |
| `success-foreground` / `success` | 14.73 |  |
| `success-on-surface` / `success-surface` (라이트) | 14.73 |  |
| `prompt-card-foreground` / `prompt-card-start` | 14.73 |  |
| `prompt-card-muted` / `prompt-card-start` | 4.96 |  |
| `prompt-card-foreground` / `prompt-card-end` | 14.73 |  |
| `prompt-card-muted` / `prompt-card-end` | 4.96 |  |
| `foreground` / `background` (다크) | 14.73 |  |
| `foreground` / `card` (다크) | 14.73 |  |
| `muted-foreground` / `background` (다크) | 4.96 |  |
| `muted-foreground` / `card` (다크) | 4.96 |  |
| `primary-foreground` / `primary` (다크) | 14.73 |  |
| `secondary-foreground` / `secondary` (다크) | 14.73 |  |
| `accent-foreground` / `accent` (다크) | 14.73 |  |
| `success-on-surface` / `success-surface` (다크) | 14.73 |  |
| `destructive-on-surface` / `destructive-surface` (다크) | 14.73 |  |
| `prompt-card-muted` / `prompt-card-start` (다크) | 4.96 |  |
| `prompt-card-muted` / `prompt-card-end` (다크) | 4.96 |  |

### 그래픽 오브젝트 (3:1)

F0 곡선·컨트롤 경계는 텍스트가 아니라 WCAG 2.1 **1.4.11 비텍스트 대비 3:1**이 기준이다.

| 조합 | 비율 | |
|---|---|---|
| `guide-curve` / `curve-lane-surface` (라이트) | 14.73 |  |
| `user-curve` / `curve-lane-surface` (라이트) | 14.73 |  |
| `guide-curve` / `curve-lane-surface` (다크) | 14.73 |  |
| `user-curve` / `curve-lane-surface` (다크) | 14.73 |  |
| `control-border` / `background` (라이트) | 14.73 |  |
| `control-border` / `card` (라이트) | 14.73 |  |
| `control-border` / `background` (다크) | 14.73 |  |
| `control-border` / `card` (다크) | 14.73 |  |

### 기준 대상이 아닌 장식 면

`muted`는 진척도의 남은 구간처럼 "없는 것"을 그리는 면이다. 여기에 상태를 실으면
아래 비율 그대로 안 보이게 되므로, 상태는 잉크로만 알린다 (정본 §7).

| 조합 | 비율 | |
|---|---|---|
| `muted` / `background` | 1.46 |  |
<!-- check_contrast:end -->

## 7. 결정 기록

### KAN-161 — Papercut 팔레트

시그니처 파랑(`#2563eb`)을 버리고 크림 종이 + 잉크 단색으로 갈았다. 값 넷은 §2에 있다.

**왜 넷인가.** KAN-148 팔레트는 파랑 계열 열 몇 개에 초록·빨강·노랑·주황을 더해 쓰고 있었고,
그중 여덟 조합이 최소선을 못 넘겨 "시안 채택으로 감수"라는 이름으로 남아 있었다. 잉크 하나로
접으면 그 여덟이 한꺼번에 사라진다 — 크림 위 잉크는 14.73:1이라 어디에 놓든 기준을 넘는다.
남는 문제는 "색이 하던 구분을 무엇이 대신하는가"뿐이고, 아래 셋이 그 답이다.

**일러스트 고정색 (`ILLO`).** 종이 `#f3ecd9` / 잉크 `#1c1a17`. 테마 토큰을 참조하지 않고
값을 직접 박는다. 일러스트는 그림 한 장이라 부분만 테마를 따라 바뀌면 선과 면이 어긋나고,
`ILLO`를 §2 표에 넣으면 화면 코드가 `--color-illo-paper` 같은 이름으로 끌어다 쓰기 시작해
"일러스트 전용"이라는 경계가 무너진다. 그래서 토큰이 아니라 상수다.

**다크 분기를 라이트로 고정.** `Theme.kt`는 `isSystemInDarkTheme()`을 보지 않고,
`tokens.css`에는 `prefers-color-scheme` 블록이 없다. 이유는 둘이다. 한 세션 안에서
네이티브와 WebView 화면이 번갈아 나오는데 한쪽만 뒤집히면 화면 경계에서 색이 튄다.
그리고 Papercut은 크림 종이에 잉크를 얹은 그림이라 명암을 뒤집으면 같은 디자인의 어두운
판이 아니라 다른 물건이 된다 — 검은 종이에 크림 잉크는 종이가 아니라 칠판이다.
정본 §2의 다크 표와 `Color.kt`의 `Dark*` 상수는 라이트와 같은 값으로 남겨 뒀다:
`tools/check_tokens.py`가 그 짝으로 대조하고, 다크 전용 팔레트를 만드는 후속 티켓이
채워 넣을 자리이기도 하다.

**`success`·`destructive`를 잉크로 접었다.** 정답 초록·오답 빨강이 사라졌다. 단일 잉크
팔레트에서 두 색만 되살리면 그 둘이 화면에서 유일한 색조가 되어 그림이 깨진다.
정오답과 오류는 색이 아니라 **문구와 아이콘**으로 가른다 — WCAG 1.4.1이 원래 요구하는 것도
"색만으로 알리지 않기"이므로 색을 빼는 쪽이 기준에는 오히려 가깝다. 정답 선택지는
✓ 표시와 문구가, 오류 블록은 `role="alert"`로 읽히는 문장이 상태를 나른다.
같은 이유로 곡선 둘도 잉크 하나이고, 가이드는 점선·사용자는 실선으로 갈린다.

**`#cfc5aa`(`muted`)는 상태를 나르지 않는다.** 크림 위 1.46:1이다. 진척도의 남은 구간,
비활성 면처럼 "아직 없는 것·지금 못 누르는 것"을 그리는 자리에만 쓴다. 비활성은 이 색과
`opacity-disabled`가 함께 알리고, 진척은 채워진 잉크 쪽이 알린다. 이 값으로 무언가를
"표시"하면 그 표시는 보이지 않는다.

### 시안에서 조정한 값

배치·간격·타이포는 아직 KAN-148 시안(`prototype/`)에서 오고, 아래 둘만 조정한 채로 유지된다.

| 항목 | 시안 | 정본 | 이유 |
|---|---|---|---|
| 주버튼 높이 | 40px (`h-10`, App.tsx:115·199·390·443·474·535) | **48dp** | §5 터치 타겟 최소선 미달 |
| 웹 음성 문항 대사 | 20px | 26px | §5 "대사 카드 24sp 이상" 미달. 네이티브 대사 카드와 같은 크기로 맞춘다 |

정본은 semantic 토큰 이름 한 벌로만 정의한다 — 그래야 "같은 의미의 색이 네이티브와 웹에서
같은 값"이 성립한다. 화면 코드가 원시 색값을 직접 적으면 그 순간 두 런타임이 갈라진다.

### 기준 미달을 감수한 것 — KAN-161로 전부 해소

KAN-148은 시안 색을 그대로 쓰기로 하고 여덟 자리에서 최소선을 감수했다.
Papercut 팔레트가 그 여덟을 전부 없앴다.

| 감수했던 항목 | KAN-148 값 | 당시 대비 | 지금 | 지금 대비 |
|---|---|---|---|---|
| 대사 카드 본문 | `#ffffff` on `#3b82f6` | 3.68 | `#1c1a17` on `#f3ecd9` | 14.73 |
| 대사 카드 보조·배지 | `#eff6ff` on `#3b82f6` | 3.38 | `#6b6459` on `#f3ecd9` | 4.96 |
| 가이드 곡선 | `#93c5fd` on `#ecf4ff` | 1.63 | `#1c1a17` on `#f3ecd9` | 14.73 |
| 사용자 곡선 | `#fb923c` on `#ecf4ff` | 2.04 | `#1c1a17` on `#f3ecd9` | 14.73 |
| 선택지 테두리(미선택) | `rgba(37,99,235,.13)` on `#ffffff` | 1.20 | `#1c1a17` on `#f3ecd9` | 14.73 |
| 컨트롤 경계 3종 (배경·카드·다크) | 같은 반투명 파랑 | 1.15~1.19 | `#1c1a17` on `#f3ecd9` | 14.73 |

`tools/check_contrast.py`의 `WAIVED`는 비어 있다. 비운 채로 유지한다 — 거기에 이름을 하나
넣는 것은 그 화면을 검사에서 빼는 것과 같고, 한 번 비어 본 목록은 다시 채우기 어렵다.
KAN-148의 AC "텍스트 대비 4.5:1"은 이제 예외 없이 지켜진다.

## 8. 공통 컴포넌트

화면들이 실제로 반복해서 쓰는 것만 둔다. 쓰지 않는 컴포넌트는 만들지 않는다.

| 컴포넌트 | 네이티브 | 웹 |
|---|---|---|
| 버튼 | `ui/components/AccenturyButton.kt` | `web/src/ui/Button.tsx` + `.btn` |
| 진척도 | `ui/components/ProgressIndicator.kt` | `web/src/ui/ProgressIndicator.tsx` |
| 대기·오류 블록 | `ui/components/StatusBlock.kt` | `web/src/ui/StatusBlock.tsx` |

**버튼** — 무게 세 가지(`Primary`·`Secondary`·`Text`). 현재 구현은 KAN-148의 아래로 떨어지는 두께감이고, KAN-161 2단계에서 §5의 오프셋 종이 그림자로 바꾼다:
밑변에 `primary-dim` 그림자를 깔고, 누르면 그림자가 줄면서 본체가 그만큼 내려간다.
본체가 내려간 만큼 그림자가 줄어 총 높이가 유지된다 — 높이가 같이 변하면 옆 요소가 밀려
화면이 들썩인다. Material `elevation`은 사방으로 번지는 그림자라 이 모양이 나오지 않는다.

**진척도** — 막대와 "3/10" 표기가 한 덩어리다. 둘이 떨어져 있으면 한쪽만 고쳐 어긋난다.
스크린 리더에는 막대만 읽히고 숫자는 의미론에서 뺀다(시각적으로는 남는다) — 같은 정보를
두 번 말하지 않게.

**대기·오류 블록** — 문구 + 부연 + 선택적 복구 동작. 오류일 때만 스스로 읽힌다
(웹 `role="alert"`, 네이티브 `liveRegion`). 대기 문구는 곧 바뀔 상태라 매번 읽으면 소음이 된다.

## 9. 접근성 최소선 검증

| 항목 | 어떻게 보장하나 |
|---|---|
| 터치 타겟 48dp | 버튼 컴포넌트의 최소 높이가 `touch-target-min`이다. 화면이 버튼을 직접 그리지 않으므로 개별 화면에서 빠뜨릴 수 없다 |
| 텍스트 대비 4.5:1 | `tools/check_contrast.py`가 팔레트 조합 전부를 검사한다. 감수(`WAIVED`) 항목은 KAN-161로 전부 사라져 목록이 비어 있다 (§7) |
| 그래픽 대비 3:1 | 같은 스크립트의 그래픽 그룹 — F0 곡선·컨트롤 경계 |
| 대사 24sp 이상 | `tools/check_tokens.py`가 정본 §3의 `headline` 값이 24 미만이면 실패한다 |
| 모션 축소 | 웹 `prefers-reduced-motion`, 네이티브 `isReducedMotionEnabled()`. 축소 상태에서도 최종 상태는 같다 |
| 색에 의존하지 않기 | 팔레트가 잉크 한 색이라 색으로는 아무것도 구분하지 않는다. 선택지는 ✓ 표시, 정오답·오류는 문구와 아이콘, 곡선 둘은 점선/실선으로 갈린다 (WCAG 1.4.1) |

## 10. 범위 밖

- 개별 화면의 픽셀 맞추기 — 시안이 확정된 화면부터 각 화면 티켓에서 이 토큰을 써서 붙인다.
- 시안의 Tailwind·shadcn 의존성 — 앱 레포 `web/`은 순수 CSS 커스텀 프로퍼티만 쓴다. 값만 옮기고 빌드 체인은 옮기지 않는다.
