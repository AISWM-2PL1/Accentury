package com.accentury.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 색 토큰 (KAN-148). 정본은 `docs/wiki/design-tokens.md` §2이고 이 파일은 사본이다 —
 * 값을 바꾸려면 정본부터 고치고 같은 커밋에서 여기와 `web/src/tokens.css`를 함께 고친다.
 * 한쪽만 고치면 네이티브와 WebView가 번갈아 나오는 화면 경계에서 색이 튄다.
 *
 * 이름은 정본의 semantic 토큰 이름을 그대로 옮겼다 (`primary` → [LightPrimary]).
 * 대비는 `tools/check_contrast.py`가 검증한다.
 */

// ─── 라이트 ─────────────────────────────────────────────────

val LightPrimary = Color(0xFF2563EB)
val LightPrimaryForeground = Color(0xFFFFFFFF)

/** 주버튼 3D 그림자와 대사 카드 그라디언트 끝. primary보다 한 단 어둡다 */
val LightPrimaryDim = Color(0xFF1D4ED8)

val LightBackground = Color(0xFFEFF6FF)
val LightForeground = Color(0xFF1E3A5F)
val LightCard = Color(0xFFFFFFFF)
val LightCardForeground = Color(0xFF1E3A5F)
val LightSecondary = Color(0xFFDBEAFE)
val LightSecondaryForeground = Color(0xFF1D4ED8)
val LightMuted = Color(0xFFBFDBFE)
val LightMutedForeground = Color(0xFF4D6F96)
val LightAccent = Color(0xFFFCD34D)
val LightAccentForeground = Color(0xFF78350F)

val LightSuccess = Color(0xFF047857)
val LightSuccessForeground = Color(0xFFFFFFFF)
val LightSuccessSurface = Color(0xFFECFDF5)
val LightSuccessOnSurface = Color(0xFF047857)

val LightDestructive = Color(0xFFDC2626)
val LightDestructiveForeground = Color(0xFFFFFFFF)
val LightDestructiveSurface = Color(0xFFFEF2F2)
val LightDestructiveOnSurface = Color(0xFFB91C1C)

val LightBorder = Color(0x212563EB)

/**
 * 선택 가능한 컨트롤의 경계 (선택지·입력). 시안 값 그대로이고 카드 위 1.20:1이라
 * 비텍스트 3:1(WCAG 1.4.11)에는 못 미친다 - 감수한 자리다 (정본 §7).
 * 선택 여부는 테두리 색뿐 아니라 배경·그림자·✓ 표시가 함께 알린다.
 */
val LightControlBorder = Color(0x212563EB)
val LightRing = Color(0xFF3B82F6)

val LightPromptCardStart = Color(0xFF3B82F6)
val LightPromptCardEnd = Color(0xFF1D4ED8)
val LightPromptCardForeground = Color(0xFFFFFFFF)
val LightPromptCardMuted = Color(0xFFEFF6FF)
val LightPromptCardBadge = Color(0x26FFFFFF)

val LightGuideCurve = Color(0xFF93C5FD)

/**
 * 사용자 곡선은 주황이다 - 파랑 일색인 UI에서 곡선만 다른 색조를 갖게 해 "곡선이 주인공"을
 * 색으로 만든다 (ux-ui.md §5). 시안 값 그대로이고, 레인 위 2.04:1로 비텍스트 3:1에는
 * 못 미친다 - 감수한 자리다 (정본 §7, tools/check_contrast.py의 WAIVED).
 */
val LightUserCurve = Color(0xFFFB923C)
val LightCurveLaneSurface = Color(0xFFECF4FF)
val LightHeroStart = Color(0xFF60A5FA)
val LightHeroEnd = Color(0xFF2563EB)
val LightCurveLaneBorder = Color(0x212563EB)

// ─── 다크 ───────────────────────────────────────────────────

val DarkPrimary = Color(0xFF3B82F6)

/**
 * 다크에서만 전경이 뒤집힌다. 밝은 파랑 위의 흰 글씨는 3.68:1로 4.5:1에 못 미치는데,
 * 파랑을 어둡게 낮추면 다크 배경에서 버튼이 묻힌다 - 파랑을 밝게 두고 글씨를 어둡게
 * 가져가는 쪽이 두 요구를 다 만족한다 (4.85:1).
 */
val DarkPrimaryForeground = Color(0xFF0F172A)
val DarkPrimaryDim = Color(0xFF1D4ED8)

val DarkBackground = Color(0xFF0F172A)
val DarkForeground = Color(0xFFE2F0FF)
val DarkCard = Color(0xFF1E293B)
val DarkCardForeground = Color(0xFFE2F0FF)
val DarkSecondary = Color(0xFF1E3A5F)
val DarkSecondaryForeground = Color(0xFF93C5FD)
val DarkMuted = Color(0xFF1E293B)
val DarkMutedForeground = Color(0xFF7EA8D0)
val DarkAccent = Color(0xFFF59E0B)
val DarkAccentForeground = Color(0xFF451A03)

val DarkSuccess = Color(0xFF047857)
val DarkSuccessForeground = Color(0xFFFFFFFF)
val DarkSuccessSurface = Color(0xFF052E23)
val DarkSuccessOnSurface = Color(0xFF6EE7B7)

val DarkDestructive = Color(0xFFDC2626)
val DarkDestructiveForeground = Color(0xFFFFFFFF)
val DarkDestructiveSurface = Color(0xFF3F1414)
val DarkDestructiveOnSurface = Color(0xFFFCA5A5)

val DarkBorder = Color(0x1F93C5FD)
val DarkControlBorder = Color(0x1F93C5FD)
val DarkRing = Color(0xFF60A5FA)

val DarkPromptCardStart = Color(0xFF2563EB)
val DarkPromptCardEnd = Color(0xFF1E3A8A)
val DarkPromptCardForeground = Color(0xFFFFFFFF)
val DarkPromptCardMuted = Color(0xFFEFF6FF)
val DarkPromptCardBadge = Color(0x26FFFFFF)

val DarkGuideCurve = Color(0xFF93C5FD)
val DarkUserCurve = Color(0xFFFB923C)
val DarkCurveLaneSurface = Color(0xFF182338)
val DarkHeroStart = Color(0xFF3B82F6)
val DarkHeroEnd = Color(0xFF1D4ED8)
val DarkCurveLaneBorder = Color(0x1F93C5FD)
