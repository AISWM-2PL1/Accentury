package com.accentury.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 간격·반경·터치 타겟·모션 토큰 (KAN-148). 정본은 `docs/wiki/design-tokens.md` §4·§5.
 */
object Spacing {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x6 = 24.dp
    val x8 = 32.dp
}

object Radius {
    val sm = 12.dp
    val md = 16.dp
    val lg = 18.dp
    val xl = 24.dp

    /** 완전한 원. 배지·원형 버튼처럼 모서리를 끝까지 굴리는 자리 */
    val full = 9999.dp
}

object Dimens {
    /**
     * 탭 가능한 요소의 최소 높이·너비 (`ux-ui.md` §5). 시안은 40dp(`h-10`)였는데
     * 최소선 미달이라 올렸다 - 정본 §7 참조.
     */
    val touchTargetMin = 48.dp

    /** 대사 카드 최소 높이. 문항 길이가 달라도 카드가 들썩이지 않게 고정한다 */
    val promptCardMinHeight = 152.dp

    /** 대사·질문 카드 안쪽 여백 */
    val promptCardPadding = 22.dp

    /** 곡선 레인 하나의 높이 (시안: h-[72px]) */
    val curveLaneHeight = 72.dp

    /** 원형 녹음 버튼 지름 (시안: w-20 h-20) */
    val recordButtonSize = 80.dp

    /** 화면을 여는 원형 히어로 아이콘 지름 (시안: w-28 h-28) */
    val heroIconSize = 112.dp

    /** 진행바 두께 (시안: h-3) */
    val progressBarHeight = 12.dp

    /** Chunky 3D 버튼의 기본 그림자 깊이. 눌리면 [buttonPressedDepth]로 줄어든다 */
    val buttonRestDepth = 4.dp
    val buttonPressedDepth = 1.dp
}

/**
 * 모션 토큰 (§5). easing은 전부 ease-out - `ux-ui.md` §5가 정한 100~300ms 범위와 맞는다.
 * 값은 밀리초다.
 */
object Motion {
    val easeOut: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    const val PRESS = 75
    const val FAST = 150
    const val BASE = 300

    /** 결과 리빌만 예외적으로 길다 - `ux-ui.md` §5가 명시한 절정 연출 */
    const val REVEAL = 600
}

/**
 * 사용자가 시스템에서 애니메이션을 껐는지 (설정 > 접근성 > 애니메이션 제거, 또는
 * 개발자 옵션의 애니메이션 배율 0). 웹의 `prefers-reduced-motion`에 대응하는 네이티브 신호다.
 *
 * 이 값이 true면 화면은 애니메이션을 건너뛰되 **최종 상태는 똑같이 만든다** - 전환을
 * 없애는 것이지 정보를 없애는 게 아니다. 진행 중 애니메이션으로만 알 수 있는 내용이 있으면
 * 축소 모드에서 그 내용이 사라진다.
 *
 * 조회가 실패하면(권한·기기 차이) 애니메이션을 켠 쪽으로 답한다 - 접근성 설정을 못 읽었다고
 * 모두에게서 모션을 뺏을 이유는 없다.
 */
@Composable
@ReadOnlyComposable
fun isReducedMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val scale = try {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: Exception) {
        1f
    }
    return scale == 0f
}

/**
 * 모션 축소가 켜져 있으면 0을, 아니면 [duration]을 돌려준다.
 * `animateFloatAsState(animationSpec = tween(motionDuration(Motion.BASE), easing = Motion.easeOut))`
 * 처럼 duration 자리에 그대로 끼워 쓴다.
 */
@Composable
@ReadOnlyComposable
fun motionDuration(duration: Int): Int = if (isReducedMotionEnabled()) 0 else duration
