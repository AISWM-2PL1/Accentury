package com.accentury.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.accentury.app.R

/**
 * 타이포 토큰 (KAN-148). 정본은 `docs/wiki/design-tokens.md` §3.
 *
 * 두 벌을 쓴다.
 * - [Jua] - 제목·대사·등급. 어느 기기에도 없는 디스플레이 폰트라 번들이 필수다.
 * - [FontFamily.Default] - 본문. Android 시스템 산세리프가 이미 Noto Sans CJK KR이고
 *   WebView의 `sans-serif`도 같은 폰트로 풀리므로, 양쪽이 저절로 같은 글꼴로 수렴한다.
 *   9.9MB짜리 Noto Sans KR 가변폰트를 번들해도 타깃 기기에서 보이는 글자는 같다.
 */
val Jua = FontFamily(Font(R.font.jua_regular, FontWeight.Normal))

/**
 * Material3 슬롯에 정본 §3의 스케일을 얹었다. 슬롯 이름과 토큰 이름의 대응은 아래 주석이
 * 정본이다 - 화면 코드는 `MaterialTheme.typography.headlineMedium` 식으로만 쓰고
 * 크기를 직접 적지 않는다.
 *
 * 행간은 대사·등급이 1.15, 나머지가 1.5다. 큰 글자에 1.5를 그대로 주면 줄 사이가 벌어져
 * 한 덩어리로 안 읽힌다.
 *
 * **Jua 슬롯은 전부 [FontWeight.Normal]이다** (KAN-161 2단계). 번들한 Jua에는 굵기가
 * 400 하나뿐이라 Bold·Black을 요청하면 Compose가 획을 부풀린 합성 볼드를 만든다 -
 * 곡선이 뭉개져 손으로 오린 글씨가 아니라 두껍게 인쇄한 글씨로 보인다.
 * `tools/check_tokens.py`가 정본 §3의 굵기 열과 이 값들을 대조한다.
 */
val Typography = Typography(
    // display 40sp - 결과 등급
    displayLarge = TextStyle(
        fontFamily = Jua,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    ),
    /**
     * headline 26sp - 대사 카드. `ux-ui.md` §5의 "대사 카드 24sp 이상"이 여기 걸린다.
     * 발화용 텍스트는 가독성이 곧 기능이라 이 값 아래로 내리면 안 된다.
     */
    headlineMedium = TextStyle(
        fontFamily = Jua,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 30.sp,
    ),
    // title 30sp - 단어 문항·인트로 제목
    titleLarge = TextStyle(
        fontFamily = Jua,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 35.sp,
    ),
    // titleSmall 20sp - 주 CTA 라벨·화면 제목
    titleMedium = TextStyle(
        fontFamily = Jua,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 23.sp,
    ),
    /**
     * timer 16sp - 녹음 타이머·8초 경고 캡슐 (KAN-161 4단계).
     *
     * M3에 "타이머" 슬롯이 없어 남아 있던 `titleSmall`에 얹었다. 이름은 M3 것이고 뜻은
     * 정본 §3의 `timer`다 - `tools/check_tokens.py`의 `TYPE_SLOTS`가 그 대응을 들고 있다.
     * 슬롯을 새로 만들지 않은 이유는 [Typography]를 감싸는 자기 타입을 하나 더 두면
     * 화면 코드가 `MaterialTheme.typography`와 그 타입 둘을 번갈아 읽게 되기 때문이다.
     *
     * 왜 Jua 16인가: 숫자 두 자리가 초마다 바뀌는 자리라 본문 글꼴로 적으면 "지금 몇 초"가
     * 문단의 한 낱말처럼 읽힌다. 대사와 같은 글꼴로 두되 크기를 본문에 맞춰 낮췄다.
     */
    titleSmall = TextStyle(
        fontFamily = Jua,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 18.sp,
        // 자리가 고정된 숫자라 자간을 벌리지 않으면 `00:04`의 두 자리가 서로 붙어 보인다.
        // 자간을 주는 슬롯은 여기 하나다 (웹 `.type-timer`와 같은 값).
        letterSpacing = 0.04.em,
    ),
    // body 16sp - 본문, 버튼 라벨
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    // bodySmall 15sp - 선택지
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    // label 14sp - 부가 설명
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    // caption 13sp - 카드 위 캡션, 레인 라벨, 진행 표기
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
)
