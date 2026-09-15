package app.accentury.backend.scoring;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 점수 버전 하나의 집계 정책 - seed JSON({@code score-versions/*.json})과 1:1 (KAN-21).
 * <p>
 * 가중치(억양 2 : 단어 1)와 등급 경계를 코드에 하드코딩하지 않기 위한 타입이다
 * (API 명세서 §4.3 보완). {@code scoreVersion} 하나로 집계식을 완전 재현하고 감사할 수
 * 있어야 하고(KAN-21 AC), AI 오프라인 재채점(KAN-47)이 같은 seed 파일을 읽는다.
 * <p>
 * 보정 로드맵(2026-07-27, KAN-200에서 조정): sv-0.4는 억양 입력의 구간별 계수 전처리를
 * 더하고({@link IntonationPreprocess}), 경계값 재보정은 sv-0.5로 미뤘다. sv-1.0의 모델
 * 전환도 단조성 제약을 지킨다 - 어느 쪽이든 새 seed 파일 = 새 점수 버전이다.
 *
 * @param scoreVersion          점수 버전 (예: sv-0.3) - 발행 후 불변, 세션은 생성 시점 버전에 고정 (§5.4)
 * @param intonationWeight      억양 점수 가중치 (sv-0.3: 2 - 음성 가중치 2배)
 * @param vocabularyWeight      단어 점수 가중치 (sv-0.3: 1)
 * @param intonationPreprocess  억양 입력 전처리 규칙 (sv-0.4, KAN-200). 없으면 전처리 없음 -
 *                              sv-0.3처럼 필드가 없는 seed는 원점수 평균을 그대로 쓴다.
 * @param tiers                 등급 5개 - rank 오름차순, {@code minScore}는 하한(포함).
 *                              경계값이 상위 등급에 포함되는 규칙이 이 표현에서 저절로 나온다.
 */
public record ScorePolicy(
        String scoreVersion,
        int intonationWeight,
        int vocabularyWeight,
        @Nullable IntonationPreprocess intonationPreprocess,
        List<Tier> tiers) {

    public ScorePolicy {
        // Jackson이 만드는 가변 리스트가 레지스트리 밖에서 변형되지 않게 불변 복사한다 -
        // 발행 후 불변이 결정성의 전제다 (Codex sol 리뷰 P2). null은 발행 검증이 크기로 거른다.
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
    }

    /**
     * 캐릭터형 등급 하나 (KAN-21 확정 표).
     *
     * @param code     클라이언트 계약 코드 (예: HONORARY) - §3.7 tier.code
     * @param name     표시 이름 (예: 명예주민) - 서버가 내려주는 값이라 앱 배포 없이 교체 가능 (§3.7)
     * @param rank     1(외지인)~5(경남 토박이) - §3.7 tier.rank
     * @param minScore 이 등급이 되는 최소 종합 점수 (포함) - 상한은 다음 등급의 하한이 정한다.
     */
    public record Tier(String code, String name, int rank, int minScore) {
    }

    /**
     * 억양 입력의 구간별 계수 전처리 (KAN-200, API 명세서 §4.3). 고정 수식 (억양 x 2 + 단어) / 3
     * 에 넣기 전에 음성 5문항 원점수의 <b>평균에 1회</b> 계수를 곱한다 - 문항별로 곱하지
     * 않는다 (2026-09-08 확정, 사용자에게 보이는 억양 점수 하나로 검산할 수 있게).
     * <p>
     * 계수는 평균이 속한 구간으로 정한다. 구간은 {@code bandWidth}점 폭이고 상한을 포함한다
     * (95는 91~95 구간). 최상위 구간(96~100)의 계수가 1이고 한 구간 내려갈 때마다
     * {@code coefficientStepPercent}/100 씩 줄어든다 - sv-0.4(폭 5, 감소 5%)에서는 계수가
     * ceil(평균 / 5) x 5 / 100 이라 전처리 결과(평균 x 계수)는 95 → 90.25, 90 → 81,
     * 5 → 0.25, 0 → 0 이다.
     * <p>
     * 값은 전부 정수다 - 계수를 0.05 같은 실수로 두면 부동소수점이 집계에 들어온다
     * (KAN-21 결정성). 감소 폭은 퍼센트 정수로 적고, 산술은 {@link #coefficientPercent}가
     * 원점수 합과 문항 수로만 한다.
     *
     * @param bandWidth              구간 폭 (평균 점수 단위, sv-0.4: 5). 100을 나누어떨어뜨려야 한다.
     * @param coefficientStepPercent 구간 하나 내려갈 때 계수가 줄어드는 폭 (퍼센트 정수, sv-0.4: 5)
     */
    public record IntonationPreprocess(int bandWidth, int coefficientStepPercent) {

        /** 구간 수 - 평균 0 초과 ~ 100 이하를 {@code bandWidth}로 나눈 것 (sv-0.4: 20). */
        public int bandCount() {
            return 100 / bandWidth;
        }

        /**
         * 원점수 합이 속한 구간 번호 - 1이 최하위(0 초과 ~ bandWidth 이하), {@link #bandCount()}가
         * 최상위다. 합 0은 어느 구간에도 들지 않아 0이다. 평균으로 나누지 않고 합으로 올림해
         * 평균이 반올림되기 전의 값으로 구간이 정해진다 (평균 95.2 → 96~100 구간).
         */
        public int bandOf(long intonationSum, int voiceCount) {
            long bandSum = (long) voiceCount * bandWidth;
            return (int) ((intonationSum + bandSum - 1) / bandSum);
        }

        /** 구간 번호의 계수 (퍼센트 정수) - 최상위 구간이 100이고 한 구간마다 감소 폭만큼 준다. */
        public int coefficientPercentOfBand(int band) {
            return 100 - (bandCount() - band) * coefficientStepPercent;
        }

        /**
         * 원점수 합에 곱할 계수 (퍼센트 정수). 전처리 억양 점수 = 합 x 계수 / (문항 수 x 100)
         * 이고 {@link ScoreAggregator}가 그 분수를 정수 반올림한다.
         */
        public int coefficientPercent(long intonationSum, int voiceCount) {
            return coefficientPercentOfBand(bandOf(intonationSum, voiceCount));
        }
    }

    /**
     * 억양 원점수 합에 곱할 계수 (퍼센트 정수). 전처리 규칙이 없는 정책(sv-0.3)은 100 -
     * 합 x 100 / (문항 수 x 100)은 합 / 문항 수와 같은 정수 반올림 결과를 내므로 기존
     * 세션의 점수가 바이트 단위로 같다 (KAN-200 AC).
     */
    public int intonationCoefficientPercent(long intonationSum, int voiceCount) {
        return intonationPreprocess == null ? 100
                : intonationPreprocess.coefficientPercent(intonationSum, voiceCount);
    }

    /**
     * 종합 점수가 속하는 등급 - {@code minScore}가 점수 이하인 가장 높은 등급이다.
     * 경계값(20, 40, 60, 80)은 상위 등급에 포함된다 (KAN-21 AC).
     */
    public Tier tierFor(int overallScore) {
        for (int i = tiers.size() - 1; i >= 0; i--) {
            Tier tier = tiers.get(i);
            if (overallScore >= tier.minScore()) {
                return tier;
            }
        }
        // 발행 검증이 첫 등급 minScore=0을 강제하므로 0~100 입력에서는 도달 불가
        throw new IllegalArgumentException(
                "등급을 판정할 수 없는 종합 점수다: " + overallScore + " (" + scoreVersion + ")");
    }
}
