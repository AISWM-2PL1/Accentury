package app.accentury.backend.scoring;

import java.util.List;

/**
 * 점수 버전 하나의 집계 정책 - seed JSON({@code score-versions/*.json})과 1:1 (KAN-21).
 * <p>
 * 가중치(억양 2 : 단어 1)와 등급 경계를 코드에 하드코딩하지 않기 위한 타입이다
 * (API 명세서 §4.3 보완). {@code scoreVersion} 하나로 집계식을 완전 재현하고 감사할 수
 * 있어야 하고(KAN-21 AC), AI 오프라인 재채점(KAN-47)이 같은 seed 파일을 읽는다.
 * <p>
 * 보정 로드맵(2026-07-27): sv-0.4는 이 파일의 경계값만 재보정하고, sv-1.0의 모델
 * 전환도 단조성 제약을 지킨다 - 어느 쪽이든 새 seed 파일 = 새 점수 버전이다.
 *
 * @param scoreVersion     점수 버전 (예: sv-0.3) - 발행 후 불변, 세션은 생성 시점 버전에 고정 (§5.4)
 * @param intonationWeight 억양 점수 가중치 (sv-0.3: 2 - 음성 가중치 2배)
 * @param vocabularyWeight 단어 점수 가중치 (sv-0.3: 1)
 * @param tiers            등급 5개 - rank 오름차순, {@code minScore}는 하한(포함).
 *                         경계값이 상위 등급에 포함되는 규칙이 이 표현에서 저절로 나온다.
 */
public record ScorePolicy(
        String scoreVersion,
        int intonationWeight,
        int vocabularyWeight,
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
