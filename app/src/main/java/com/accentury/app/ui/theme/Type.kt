package com.accentury.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 */
val Typography = Typography(
    // display 44sp - 결과 등급
    displayLarge = TextStyle(
        fontFamily = Jua,
        fontWeight = FontWeight.Black,
        fontSize = 44.sp,
        lineHeight = 51.sp,
    ),
    /**
     * headline 26sp - 대사 카드. `ux-ui.md` §5의 "대사 카드 24sp 이상"이 여기 걸린다.
     * 발화용 텍스트는 가독성이 곧 기능이라 이 값 아래로 내리면 안 된다.
     */
    headlineMedium = TextStyle(
        fontFamily = Jua,
        fontWeight = FontWeight.Black,
        fontSize = 26.sp,
        lineHeight = 30.sp,
    ),
    // title 22sp - 어휘 문항 질문
    titleLarge = TextStyle(
        fontFamily = Jua,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    ),
    // titleSmall 20sp - 화면 제목
    titleMedium = TextStyle(
        fontFamily = Jua,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
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
    // caption 12sp - 배지, 카드 보조 문구
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
)
