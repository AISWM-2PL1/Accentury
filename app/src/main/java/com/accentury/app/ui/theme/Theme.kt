package com.accentury.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightPrimaryForeground,
    primaryContainer = LightSecondary,
    onPrimaryContainer = LightSecondaryForeground,
    secondary = LightSecondaryForeground,
    onSecondary = LightPrimaryForeground,
    secondaryContainer = LightSecondary,
    onSecondaryContainer = LightSecondaryForeground,
    tertiary = LightAccent,
    onTertiary = LightAccentForeground,
    background = LightBackground,
    onBackground = LightForeground,
    surface = LightCard,
    onSurface = LightCardForeground,
    surfaceVariant = LightMuted,
    onSurfaceVariant = LightMutedForeground,
    error = LightDestructive,
    onError = LightDestructiveForeground,
    errorContainer = LightDestructiveSurface,
    onErrorContainer = LightDestructiveOnSurface,
    outline = LightMutedForeground,
    outlineVariant = LightBorder,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkPrimaryForeground,
    primaryContainer = DarkSecondary,
    onPrimaryContainer = DarkSecondaryForeground,
    secondary = DarkSecondaryForeground,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSecondary,
    onSecondaryContainer = DarkSecondaryForeground,
    tertiary = DarkAccent,
    onTertiary = DarkAccentForeground,
    background = DarkBackground,
    onBackground = DarkForeground,
    surface = DarkCard,
    onSurface = DarkCardForeground,
    surfaceVariant = DarkMuted,
    onSurfaceVariant = DarkMutedForeground,
    error = DarkDestructive,
    onError = DarkDestructiveForeground,
    errorContainer = DarkDestructiveSurface,
    onErrorContainer = DarkDestructiveOnSurface,
    outline = DarkMutedForeground,
    outlineVariant = DarkBorder,
)

/**
 * Material3 ColorScheme에 슬롯이 없는 토큰들 (정본 §2). 정답·오답 표시, 대사 카드,
 * F0 곡선은 M3 어휘에 대응하는 자리가 없어서 여기로 뺐다.
 *
 * error 슬롯에 success를 욱여넣는 식으로 맞추면 의미가 뒤집혀 나중에 읽는 사람이 속는다.
 */
@Immutable
data class AccenturyColors(
    val primaryDim: Color,
    val controlBorder: Color,
    val success: Color,
    val onSuccess: Color,
    val successSurface: Color,
    val onSuccessSurface: Color,
    val destructiveSurface: Color,
    val onDestructiveSurface: Color,
    val promptCardStart: Color,
    val promptCardEnd: Color,
    val onPromptCard: Color,
    val onPromptCardMuted: Color,
    val promptCardBadge: Color,
    val guideCurve: Color,
    val userCurve: Color,
    val curveLaneSurface: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val curveLaneBorder: Color,
)

private val LightAccenturyColors = AccenturyColors(
    primaryDim = LightPrimaryDim,
    controlBorder = LightControlBorder,
    success = LightSuccess,
    onSuccess = LightSuccessForeground,
    successSurface = LightSuccessSurface,
    onSuccessSurface = LightSuccessOnSurface,
    destructiveSurface = LightDestructiveSurface,
    onDestructiveSurface = LightDestructiveOnSurface,
    promptCardStart = LightPromptCardStart,
    promptCardEnd = LightPromptCardEnd,
    onPromptCard = LightPromptCardForeground,
    onPromptCardMuted = LightPromptCardMuted,
    promptCardBadge = LightPromptCardBadge,
    guideCurve = LightGuideCurve,
    userCurve = LightUserCurve,
    curveLaneSurface = LightCurveLaneSurface,
    heroStart = LightHeroStart,
    heroEnd = LightHeroEnd,
    curveLaneBorder = LightCurveLaneBorder,
)

private val DarkAccenturyColors = AccenturyColors(
    primaryDim = DarkPrimaryDim,
    controlBorder = DarkControlBorder,
    success = DarkSuccess,
    onSuccess = DarkSuccessForeground,
    successSurface = DarkSuccessSurface,
    onSuccessSurface = DarkSuccessOnSurface,
    destructiveSurface = DarkDestructiveSurface,
    onDestructiveSurface = DarkDestructiveOnSurface,
    promptCardStart = DarkPromptCardStart,
    promptCardEnd = DarkPromptCardEnd,
    onPromptCard = DarkPromptCardForeground,
    onPromptCardMuted = DarkPromptCardMuted,
    promptCardBadge = DarkPromptCardBadge,
    guideCurve = DarkGuideCurve,
    userCurve = DarkUserCurve,
    curveLaneSurface = DarkCurveLaneSurface,
    heroStart = DarkHeroStart,
    heroEnd = DarkHeroEnd,
    curveLaneBorder = DarkCurveLaneBorder,
)

/**
 * 테마 밖에서 읽으면 라이트 값이 나온다 - 프리뷰나 테스트가 테마 없이 컴포저블을 그릴 때
 * 예외 대신 그럴듯한 기본값을 주는 편이 낫다.
 */
private val LocalAccenturyColors = staticCompositionLocalOf { LightAccenturyColors }

/**
 * `MaterialTheme.colorScheme`과 나란히 쓰는 확장 팔레트.
 * `MaterialTheme.accenturyColors.userCurve` 처럼 접근한다.
 */
val MaterialTheme.accenturyColors: AccenturyColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAccenturyColors.current

/**
 * 앱 테마 (KAN-148).
 *
 * **dynamic color는 쓰지 않는다.** Android 12+에서 켜면 사용자 배경화면 색이 앱 색을
 * 덮어써서 기기마다 앱 색이 달라진다. 이 앱은 F0 곡선의 시그니처 파랑이 정보 자체를
 * 나르고(`ux-ui.md` §5 "곡선이 주인공"), 웹 화면과 색을 맞춰야 하는데 - 배경화면에 따라
 * 색이 변하면 두 요구가 다 무너진다. 그래서 매개변수도 남기지 않았다: 껐다 켰다 할 수 있게
 * 두면 언젠가 누군가 켠다.
 */
@Composable
fun AccenturyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) DarkAccenturyColors else LightAccenturyColors

    CompositionLocalProvider(LocalAccenturyColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
