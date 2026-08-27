package com.accentury.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 색 토큰 (KAN-148, Papercut 팔레트 KAN-161). 정본은 `docs/wiki/design-tokens.md` §2이고
 * 이 파일은 사본이다 — 값을 바꾸려면 정본부터 고치고 같은 커밋에서 여기와
 * `web/src/tokens.css`를 함께 고친다. 한쪽만 고치면 네이티브와 WebView가 번갈아 나오는
 * 화면 경계에서 색이 튄다.
 *
 * 이름은 정본의 semantic 토큰 이름을 그대로 옮겼다 (`primary` → [LightPrimary]).
 * 대비는 `tools/check_contrast.py`가 검증한다 — 감수(WAIVED) 항목은 KAN-161로 전부 사라졌다.
 *
 * **팔레트는 넷뿐이다** — 잉크 `#1c1a17`, 크림 `#f3ecd9`, 종이 그림자 `#cfc5aa`,
 * 흐린 잉크 `#6b6459`. 반투명(알파) 값은 하나도 없다: 종이를 오려 붙인 그림에는
 * 비쳐 보이는 면이 없다.
 *
 * **Dark*는 Light*와 값이 같다** (KAN-161). 이름을 남겨 둔 것은 정본 §2의 다크 표와
 * 대조하는 `tools/check_tokens.py`가 짝을 찾기 때문이고, 다크 전용 팔레트는 후속 티켓이다.
 */

// ─── 라이트 ─────────────────────────────────────────────────

/** 잉크. 텍스트·선·버튼 면·F0 곡선이 전부 이 한 색이다 */
val LightPrimary = Color(0xFF1C1A17)
val LightPrimaryForeground = Color(0xFFF3ECD9)

/** 오프셋 종이 그림자(`3dp 4dp 0`). 번지지 않는 단색 면이라 알파를 주지 않는다 */
val LightPrimaryDim = Color(0xFFCFC5AA)

val LightBackground = Color(0xFFF3ECD9)
val LightForeground = Color(0xFF1C1A17)
val LightCard = Color(0xFFF3ECD9)
val LightCardForeground = Color(0xFF1C1A17)
val LightSecondary = Color(0xFFF3ECD9)
val LightSecondaryForeground = Color(0xFF1C1A17)

/**
 * 장식·비활성 면 전용이다. 크림 위 1.46:1이라 **상태를 이 색으로 알리면 안 된다** —
 * 진척도의 남은 구간처럼 "없는 것"을 그리는 자리에만 쓴다 (정본 §7).
 */
val LightMuted = Color(0xFFCFC5AA)
val LightMutedForeground = Color(0xFF6B6459)
val LightAccent = Color(0xFF1C1A17)
val LightAccentForeground = Color(0xFFF3ECD9)

/**
 * 정답도 잉크다. 단일 잉크 팔레트에서 초록·빨강을 되살리면 종이 그림이 깨지므로,
 * 정오답은 색이 아니라 문구·아이콘으로 가른다 (WCAG 1.4.1, 정본 §7).
 */
val LightSuccess = Color(0xFF1C1A17)
val LightSuccessForeground = Color(0xFFF3ECD9)
val LightSuccessSurface = Color(0xFFF3ECD9)
val LightSuccessOnSurface = Color(0xFF1C1A17)

val LightDestructive = Color(0xFF1C1A17)
val LightDestructiveForeground = Color(0xFFF3ECD9)
val LightDestructiveSurface = Color(0xFFF3ECD9)
val LightDestructiveOnSurface = Color(0xFF1C1A17)

val LightBorder = Color(0xFF1C1A17)

/**
 * 선택 가능한 컨트롤의 경계 (선택지·입력). 잉크 실선이라 크림 위 14.73:1이고
 * 비텍스트 3:1(WCAG 1.4.11)을 넉넉히 넘는다 — KAN-148에서 감수했던 자리다.
 */
val LightControlBorder = Color(0xFF1C1A17)
val LightRing = Color(0xFF1C1A17)

val LightPromptCardStart = Color(0xFFF3ECD9)
val LightPromptCardEnd = Color(0xFFF3ECD9)
val LightPromptCardForeground = Color(0xFF1C1A17)
val LightPromptCardMuted = Color(0xFF6B6459)
val LightPromptCardBadge = Color(0xFFF3ECD9)

val LightGuideCurve = Color(0xFF1C1A17)

/**
 * 곡선 둘은 같은 잉크다. 색조로 가르던 것을 선 모양으로 넘겼다 — 가이드는 점선,
 * 사용자는 실선. 색맹 사용자에게는 원래도 선 모양이 유일한 단서였다 (WCAG 1.4.1).
 */
val LightUserCurve = Color(0xFF1C1A17)
val LightCurveLaneSurface = Color(0xFFF3ECD9)

/**
 * 히어로 아이콘 면 (KAN-161 2단계). 잉크로 꽉 찬 원이었는데 크림으로 뒤집었다 —
 * 오려 낸 종이 동그라미에 잉크 테두리를 두른 모양이 시안의 컷아웃 규칙이고, 잉크 원은
 * 화면에서 주 버튼과 무게가 같아져 어느 쪽을 눌러야 하는지가 흐려졌다.
 * 그라디언트가 아니라 단색이라 start·end가 같다.
 */
val LightHeroStart = Color(0xFFF3ECD9)
val LightHeroEnd = Color(0xFFF3ECD9)
val LightCurveLaneBorder = Color(0xFF1C1A17)

// ─── 다크 ───────────────────────────────────────────────────
//
// KAN-161: 라이트와 값이 같다. 시스템 다크에서도 크림 화면이 그대로 나온다 —
// Theme.kt가 다크 스킴을 고르지 않으므로 이 상수들은 정본 §2 다크 표와의 대조에만 쓰인다.

val DarkPrimary = Color(0xFF1C1A17)
val DarkPrimaryForeground = Color(0xFFF3ECD9)
val DarkPrimaryDim = Color(0xFFCFC5AA)

val DarkBackground = Color(0xFFF3ECD9)
val DarkForeground = Color(0xFF1C1A17)
val DarkCard = Color(0xFFF3ECD9)
val DarkCardForeground = Color(0xFF1C1A17)
val DarkSecondary = Color(0xFFF3ECD9)
val DarkSecondaryForeground = Color(0xFF1C1A17)
val DarkMuted = Color(0xFFCFC5AA)
val DarkMutedForeground = Color(0xFF6B6459)
val DarkAccent = Color(0xFF1C1A17)
val DarkAccentForeground = Color(0xFFF3ECD9)

val DarkSuccess = Color(0xFF1C1A17)
val DarkSuccessForeground = Color(0xFFF3ECD9)
val DarkSuccessSurface = Color(0xFFF3ECD9)
val DarkSuccessOnSurface = Color(0xFF1C1A17)

val DarkDestructive = Color(0xFF1C1A17)
val DarkDestructiveForeground = Color(0xFFF3ECD9)
val DarkDestructiveSurface = Color(0xFFF3ECD9)
val DarkDestructiveOnSurface = Color(0xFF1C1A17)

val DarkBorder = Color(0xFF1C1A17)
val DarkControlBorder = Color(0xFF1C1A17)
val DarkRing = Color(0xFF1C1A17)

val DarkPromptCardStart = Color(0xFFF3ECD9)
val DarkPromptCardEnd = Color(0xFFF3ECD9)
val DarkPromptCardForeground = Color(0xFF1C1A17)
val DarkPromptCardMuted = Color(0xFF6B6459)
val DarkPromptCardBadge = Color(0xFFF3ECD9)

val DarkGuideCurve = Color(0xFF1C1A17)
val DarkUserCurve = Color(0xFF1C1A17)
val DarkCurveLaneSurface = Color(0xFFF3ECD9)
val DarkHeroStart = Color(0xFFF3ECD9)
val DarkHeroEnd = Color(0xFFF3ECD9)
val DarkCurveLaneBorder = Color(0xFF1C1A17)
