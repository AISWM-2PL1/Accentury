# 디자인 토큰 정본

Accentury 네이티브(Compose)와 웹(WebView)이 공유하는 색·타이포·간격·모션·반경의 **단일 정본**이다.
이 문서의 표가 원본이고, `Color.kt`·`Type.kt`·`tokens.css`는 그 값을 각 런타임 형태로 옮겨 적은 사본이다.

- 티켓: KAN-148
- 설계 근거: `ux-ui.md` §2(설계 원칙)·§5(비주얼·모션·접근성 최소선)
- 시안 출처: Figma Make 코드 번들 (로컬 `prototype/`, `src/styles/theme.css` + `src/app/App.tsx`)

## 1. 정본과 사본의 관계

| 위치 | 역할 |
|---|---|
| `docs/wiki/design-tokens.md` (이 문서) | **정본.** 값을 바꾸려면 여기부터 고친다 |
| `app/src/main/java/com/accentury/app/ui/theme/Color.kt` | 사본 — 색 |
| `app/src/main/java/com/accentury/app/ui/theme/Type.kt` | 사본 — 타이포 |
| `app/src/main/java/com/accentury/app/ui/theme/Dimens.kt` | 사본 — 간격·반경·터치 타겟·모션 |
| `web/src/tokens.css` | 사본 — 전체 (CSS 커스텀 프로퍼티) |

### 갱신 절차

1. 이 문서의 표를 고친다.
2. 같은 커밋에서 `Color.kt`·`Type.kt`·`Dimens.kt`·`tokens.css`를 함께 고친다. **한쪽만 고친 커밋은 리뷰에서 반려한다** — 네이티브와 웹이 한 테스트 안에서 번갈아 나오므로 값이 갈라지면 화면 경계에서 색이 튄다.
3. 색을 새로 추가하거나 바꿨으면 `tools/check_contrast.py`의 `PAIRS`를 같이 고치고 `python3 tools/check_contrast.py`를 돌린다. 출력이 곧 §6 표다 — 미달이 하나라도 있으면 종료 코드 1로 떨어진다.
4. 시안(`prototype/`)과 값이 달라졌으면 §7 "시안과 다른 값" 표에 이유를 남긴다.

## 2. 색

시그니처 색은 파랑 하나(`primary`)이고 나머지는 중립이다 — `ux-ui.md` §5의 "곡선이 주인공" 원칙.
모든 조합은 §6에서 WCAG 4.5:1 이상을 확인했다.

### 라이트

| 토큰 | 값 | 용도 |
|---|---|---|
| `primary` | `#2563eb` | 시그니처 파랑. 주버튼, F0 곡선, 선택 상태 |
| `primary-foreground` | `#ffffff` | primary 위 텍스트 |
| `primary-dim` | `#1d4ed8` | 주버튼 3D 그림자, 대사 카드 그라디언트 끝 |
| `background` | `#eff6ff` | 화면 배경 |
| `foreground` | `#1e3a5f` | 본문 텍스트 |
| `card` | `#ffffff` | 카드·선택지 표면 |
| `card-foreground` | `#1e3a5f` | 카드 위 텍스트 |
| `secondary` | `#dbeafe` | 보조 면 |
| `secondary-foreground` | `#1d4ed8` | 보조 면 위 텍스트 |
| `muted` | `#bfdbfe` | 비활성 면, 진척도 트랙 |
| `muted-foreground` | `#4d6f96` | 보조 텍스트 |
| `accent` | `#fcd34d` | 강조 노랑 (보상·강조 배지) |
| `accent-foreground` | `#78350f` | accent 위 텍스트 |
| `success` | `#047857` | 정답 표시 |
| `success-foreground` | `#ffffff` | success 위 텍스트 |
| `success-surface` | `#ecfdf5` | 정답 선택지 배경 |
| `success-on-surface` | `#047857` | 정답 선택지 텍스트 |
| `destructive` | `#dc2626` | 오답·오류 |
| `destructive-foreground` | `#ffffff` | destructive 위 텍스트 |
| `destructive-surface` | `#fef2f2` | 오답 선택지 배경 |
| `destructive-on-surface` | `#b91c1c` | 오답 선택지 텍스트 |
| `border` | `rgba(37, 99, 235, 0.13)` | 테두리 |
| `ring` | `#3b82f6` | 포커스 링 |
| `prompt-card-start` | `#2563eb` | 대사 카드 그라디언트 시작 |
| `prompt-card-end` | `#1d4ed8` | 대사 카드 그라디언트 끝 |
| `prompt-card-foreground` | `#ffffff` | 대사 본문 |
| `prompt-card-muted` | `#eff6ff` | 대사 카드 보조 텍스트(뜻·안내) |

### 다크

| 토큰 | 값 |
|---|---|
| `primary` | `#3b82f6` |
| `primary-foreground` | `#0f172a` |
| `primary-dim` | `#1d4ed8` |
| `background` | `#0f172a` |
| `foreground` | `#e2f0ff` |
| `card` | `#1e293b` |
| `card-foreground` | `#e2f0ff` |
| `secondary` | `#1e3a5f` |
| `secondary-foreground` | `#93c5fd` |
| `muted` | `#1e293b` |
| `muted-foreground` | `#7ea8d0` |
| `accent` | `#f59e0b` |
| `accent-foreground` | `#451a03` |
| `success` | `#047857` |
| `success-foreground` | `#ffffff` |
| `success-surface` | `#052e23` |
| `success-on-surface` | `#6ee7b7` |
| `destructive` | `#dc2626` |
| `destructive-foreground` | `#ffffff` |
| `destructive-surface` | `#3f1414` |
| `destructive-on-surface` | `#fca5a5` |
| `border` | `rgba(147, 197, 253, 0.12)` |
| `ring` | `#60a5fa` |
| `prompt-card-start` | `#2563eb` |
| `prompt-card-end` | `#1e3a8a` |
| `prompt-card-foreground` | `#ffffff` |
| `prompt-card-muted` | `#eff6ff` |

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

## 5. 모션

`ux-ui.md` §5의 ease-out 100~300ms를 따른다. easing은 전부 `cubic-bezier(0, 0, 0.2, 1)`.

| 토큰 | 값 | 용도 |
|---|---|---|
| `duration-press` | 75ms | 버튼 눌림 |
| `duration-fast` | 150ms | 상태 색 전환 |
| `duration-base` | 300ms | 화면 전환 |
| `duration-reveal` | 600ms | 결과 리빌 (§5가 허용한 예외) |

**Chunky 3D 버튼** — 이 앱의 시각 정체성. 기본 상태에서 `0 4dp 0 <primary-dim>` 그림자, 눌리면 그림자 `0 1dp 0`으로 줄고 본체가 3dp 내려간다. 전환은 `duration-press`.

**모션 축소 대응**: 웹은 `@media (prefers-reduced-motion: reduce)`에서 모든 duration을 `0.01ms`로 덮는다. 네이티브는 `Settings.Global.ANIMATOR_DURATION_SCALE`이 0이면 애니메이션을 건너뛴다. 축소 상태에서도 **최종 상태는 동일** — 사라지는 정보가 없어야 한다.

## 6. 대비 검증 결과

WCAG 2.1 AA 일반 텍스트 기준 4.5:1. `python3 tools/check_contrast.py`가 생성한 표이고, 24건 전부 통과한다.

| 조합 | 비율 |
|---|---|
| `foreground` / `background` (라이트) | 10.57 |
| `foreground` / `card` (라이트) | 11.50 |
| `muted-foreground` / `background` (라이트) | 4.79 |
| `muted-foreground` / `card` (라이트) | 5.21 |
| `primary-foreground` / `primary` (라이트) | 5.17 |
| `secondary-foreground` / `secondary` (라이트) | 5.49 |
| `accent-foreground` / `accent` (라이트) | 6.29 |
| `destructive-foreground` / `destructive` | 4.83 |
| `destructive-on-surface` / `destructive-surface` (라이트) | 5.91 |
| `success-foreground` / `success` | 5.48 |
| `success-on-surface` / `success-surface` (라이트) | 5.21 |
| `prompt-card-muted` / `prompt-card-start` | 4.75 |
| `prompt-card-muted` / `prompt-card-end` | 6.16 |
| `foreground` / `background` (다크) | 15.42 |
| `foreground` / `card` (다크) | 12.64 |
| `muted-foreground` / `background` (다크) | 7.14 |
| `muted-foreground` / `card` (다크) | 5.85 |
| `primary-foreground` / `primary` (다크) | 4.85 |
| `secondary-foreground` / `secondary` (다크) | 6.38 |
| `accent-foreground` / `accent` (다크) | 6.97 |
| `success-on-surface` / `success-surface` (다크) | 9.69 |
| `destructive-on-surface` / `destructive-surface` (다크) | 8.37 |
| `prompt-card-muted` / `prompt-card-start` (다크) | 4.75 |
| `prompt-card-muted` / `prompt-card-end` (다크) | 9.52 |

## 7. 시안과 다른 값

시안(`prototype/`)을 그대로 옮기면 `ux-ui.md` §5 최소선을 못 넘기는 지점이 있어 아래만 조정했다. 나머지는 시안 값 그대로다.

| 항목 | 시안 | 정본 | 이유 |
|---|---|---|---|
| 주버튼 높이 | 40px (`h-10`, App.tsx:115·199·390·443·474·535) | **48dp** | §5 터치 타겟 최소선 미달 |
| `muted-foreground` | `#5b7fa8` | `#4d6f96` | 배경 위 3.82:1 → 4.79:1 |
| `destructive` | `#f87171` | `#dc2626` | 흰 텍스트 대비 2.77:1 → 4.83:1 |
| `success` | `#34d399` (emerald-400) | `#047857` | 흰 텍스트 대비 1.92:1 → 5.48:1 |
| 대사 카드 그라디언트 시작 | `#3b82f6` (blue-500) | `#2563eb` | 카드 보조 텍스트 대비 확보 |
| 대사 카드 보조 텍스트 | `#bfdbfe` (blue-200) | `#eff6ff` | 그라디언트 시작색 위 2.59:1 → 4.75:1 |
| 다크 `primary-foreground` | `#ffffff` | `#0f172a` | 흰 텍스트 대비 3.68:1 → 4.85:1 (파랑은 밝게 유지) |
| 다크 `accent-foreground` | `#fffbeb` | `#451a03` | 대비 2.07:1 → 6.97:1 |

시안은 semantic 토큰(`bg-primary`)과 Tailwind 원시색(`bg-blue-500`, `emerald-400`, `red-400`)을 섞어 쓴다. 정본은 semantic 이름 한 벌로만 정의한다 — 그래야 "같은 의미의 색이 네이티브와 웹에서 같은 값"이 성립한다.

## 8. 범위 밖

- 개별 화면의 픽셀 맞추기 — 시안이 확정된 화면부터 각 화면 티켓에서 이 토큰을 써서 붙인다.
- 시안의 Tailwind·shadcn 의존성 — 앱 레포 `web/`은 순수 CSS 커스텀 프로퍼티만 쓴다. 값만 옮기고 빌드 체인은 옮기지 않는다.
