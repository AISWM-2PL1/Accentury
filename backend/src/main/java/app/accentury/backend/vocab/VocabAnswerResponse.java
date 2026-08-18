package app.accentury.backend.vocab;

/**
 * {@code POST .../answer}의 200 응답 (API 명세서 §3.5) - 정오 여부를 반환하지 않는다.
 * <p>
 * 정오는 물론, 정오를 유추할 수 있는 어떤 필드도 싣지 않는다 (KAN-13 - 중간 점수 미노출).
 * 점수는 {@code /result}(KAN-25)에서 한 번에 공개된다.
 *
 * @param accepted      답안 수락 여부 - 항상 true다 (실패는 오류 봉투로 나간다).
 *                      §3.5 응답 형태 계약이라 필드로 유지한다.
 * @param answeredCount 제출된 문항 수 - 어휘는 답안 저장, 음성은 업로드 시도 1건 이상 기준
 *                      (§3.4 대표 상태의 "NOT_SUBMITTED 아님"과 동일, 2026-08-11 확정)
 * @param totalCount    전체 문항 수 - 음성 5 + 어휘 5 = 10
 */
record VocabAnswerResponse(boolean accepted, int answeredCount, int totalCount) {
}
