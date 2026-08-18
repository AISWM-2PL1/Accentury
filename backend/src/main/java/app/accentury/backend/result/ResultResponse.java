package app.accentury.backend.result;

import app.accentury.backend.common.AccenturyProperties;

import java.time.Instant;

/**
 * {@code GET .../result}의 200 응답 (API 명세서 §3.7, KAN-25).
 * <p>
 * 점수 세 개(각 0~100)와 등급, 코멘트, 공유 자산을 전부 서버가 내려준다 - 클라이언트는
 * 재계산도 하드코딩도 하지 않는다 (§3.7, KAN-29 AC). 발음과 리듬 점수, 백분위는 범위
 * 제외라 없다 (2026-07-22 확정). 값의 정본은 확정 시점의 {@link TestResult} 행(점수, 등급)과
 * 설정({@link TierAssets} - 코멘트, 공유 자산)이다.
 *
 * @param status       READY 하나뿐 - 준비 전(409)과 만료(410)는 오류 봉투로 나간다 (§3.7).
 * @param scores       억양, 단어, 종합 점수 (KAN-21 집계값 그대로)
 * @param tier         5등급 캐릭터 (KAN-29 표와 1:1)
 * @param comment      등급별 진단 코멘트
 * @param share        카카오 공유 카드 자산 (KAN-30 소비)
 * @param testVersion  세션이 고정했던 테스트 정의 버전 (AC - 결과에 포함)
 * @param scoreVersion 집계에 쓴 점수 버전 (AC - 결과에 포함)
 * @param expiresAt    이 시각 이후 조회는 410 RESULT_EXPIRED (§5.5 - 생성 24시간 뒤)
 */
public record ResultResponse(Status status, Scores scores, Tier tier, String comment, Share share,
                             String testVersion, String scoreVersion, Instant expiresAt) {

    public enum Status {
        READY
    }

    /** 각 0~100 - intonation은 음성 5문항 20점 환산 합, vocabulary는 정답률 x 100, overall은 가중 평균 (§4.3) */
    public record Scores(int intonation, int vocabulary, int overall) {
    }

    /** §3.7 tier - code는 클라이언트 자산 키 계약(KAN-21), of는 전체 등급 수(5) */
    public record Tier(String code, String name, int rank, int of) {
    }

    /** §3.7 share - imageUrl과 text는 등급별, webTestUrl은 캠페인 파라미터 붙은 공통 완성 URL (KAN-30) */
    public record Share(String imageUrl, String text, String webTestUrl) {
    }

    /** 확정 결과 행 + 등급 자산 설정 → 응답. 자산의 빈 값 없음은 기동 검증이 보장한다 (TierAssets). */
    static ResultResponse of(TestResult result, AccenturyProperties.TierAsset asset, String webTestUrl) {
        return new ResultResponse(
                Status.READY,
                new Scores(result.intonation(), result.vocabulary(), result.overall()),
                new Tier(result.tierCode(), result.tierName(), result.tierRank(), result.tierCount()),
                asset.comment(),
                new Share(asset.imageUrl(), asset.shareText(), webTestUrl),
                result.testVersion(), result.scoreVersion(), result.expiresAt());
    }
}
