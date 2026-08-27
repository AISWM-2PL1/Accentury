package com.accentury.app.ui.theme

import androidx.compose.material3.MaterialTheme
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
 * 앱 테마 (KAN-148, Papercut 팔레트 KAN-161).
 *
 * **dynamic color는 쓰지 않는다.** Android 12+에서 켜면 사용자 배경화면 색이 앱 색을
 * 덮어써서 기기마다 앱 색이 달라진다. 이 앱은 F0 곡선이 정보 자체를 나르고
 * (`ux-ui.md` §5 "곡선이 주인공"), 웹 화면과 색을 맞춰야 하는데 - 배경화면에 따라
 * 색이 변하면 두 요구가 다 무너진다. 그래서 매개변수도 남기지 않았다: 껐다 켰다 할 수 있게
 * 두면 언젠가 누군가 켠다.
 *
 * **다크 분기도 같은 이유로 없다** (KAN-161). `isSystemInDarkTheme()`을 보지 않으므로
 * 시스템 설정과 무관하게 크림 화면 하나로 간다. 이유는 둘이다.
 *
 * 하나 - 한 세션 안에서 네이티브와 WebView 화면이 번갈아 나오는데, 웹 쪽
 * `tokens.css`에도 `prefers-color-scheme` 분기가 없다. 한쪽만 뒤집히면 화면 경계에서
 * 색이 튄다. 둘 - Papercut은 크림 종이에 잉크를 얹은 그림이라 명암을 뒤집으면
 * 같은 디자인의 어두운 판이 아니라 다른 물건이 된다.
 *
 * 다크 전용 팔레트는 후속 티켓이다. 그때까지 [DarkPrimary] 같은 상수는 정본 §2의 다크 표와
 * 대조하는 `tools/check_tokens.py`를 위해 남아 있고, 값은 라이트와 같다.
 */
@Composable
fun AccenturyTheme(
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAccenturyColors provides LightAccenturyColors) {
        MaterialTheme(
            colorScheme = LightColorScheme,
            typography = Typography,
            content = content,
        )
    }
}
