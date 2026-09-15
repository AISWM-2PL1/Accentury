package app.accentury.backend.session;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;

/**
 * 응시자의 출신(모어 사투리) 지역 코드 (KAN-201, API 명세서 §3.1 {@code region}).
 * <p>
 * 웹 응시 흐름의 선택 화면(KAN-202, staging 빌드 한정)이 세션 생성 요청에 실어 보내고, staging의
 * 학습 데이터 S3 객체 키 첫 조각이 된다 - 지역별 데이터셋을 접두 나열 한 번으로 뽑기 위해서다.
 * 광역 단위 코드 하나라 개인을 좁히지 않는다 (KAN-9 AC).
 * <p>
 * {@link #UNKNOWN}은 요청값이 아니라 저장 쪽 기본값이다 - 앱 WebView 응시나 전송 오류로 값이 없는
 * 세션의 샘플이 여기로 모인다. 요청에 {@code UNKNOWN}을 보내면 코드 10개 밖이라 400이다.
 */
public enum Region {
    SEOUL, GYEONGGI, GANGWON, CHUNGBUK, CHUNGNAM, JEONBUK, JEONNAM, GYEONGBUK, GYEONGNAM, JEJU,

    /** 값이 없는 세션의 저장용 자리 - 요청으로는 받지 않는다. */
    UNKNOWN;

    /**
     * 세션 생성 요청의 {@code region}을 코드로 바꾼다. null과 빈 문자열은 "보내지 않음"이라 null이고(웹이
     * 미선택을 빈 값으로 보내도 세션 생성이 막히지 않는다 - PR #105 리뷰), 코드 10개 밖의 값(대소문자 다른 것
     * 포함, {@code UNKNOWN} 포함)은 400 {@code VALIDATION_FAILED}다 - campaignToken의 형식 검증과 같은 결과다.
     */
    public static @Nullable Region fromRequest(@Nullable String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (Region region : values()) {
            if (region != UNKNOWN && region.name().equals(code)) {
                return region;
            }
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED,
                "region은 SEOUL, GYEONGGI, GANGWON, CHUNGBUK, CHUNGNAM, JEONBUK, JEONNAM, GYEONGBUK, GYEONGNAM, JEJU 중 하나여야 합니다.");
    }

    /**
     * 저장된 컬럼 값(null 가능)을 학습 데이터 키의 첫 조각으로 - 없으면 {@link #UNKNOWN}이다. 코드 밖의 값
     * (손으로 고친 행, 나중에 지운 코드)도 {@link #UNKNOWN}으로 접는다 - 쓰기 검증({@link #fromRequest})이
     * 막고 있어 정상 경로에서는 없지만, 여기서 던지면 학습 샘플 저장이 분석 워커의 오류 경로로 샌다
     * (Claude 검증자 리뷰).
     */
    public static Region forStorage(@Nullable String stored) {
        if (stored == null) {
            return UNKNOWN;
        }
        for (Region region : values()) {
            if (region.name().equals(stored)) {
                return region;
            }
        }
        return UNKNOWN;
    }
}
