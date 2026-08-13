package app.accentury.backend.vocab;

import org.jspecify.annotations.Nullable;

/**
 * {@code POST .../answer}의 요청 본문 (API 명세서 §3.5).
 * <p>
 * 필드 누락 검증은 서비스가 맡아 공통 오류 봉투로 응답한다 - 그래서 {@code @Nullable}이다.
 *
 * @param choiceId 사용자가 고른 선택지 (예: {@code w1a}) - 세션 버전의 해당 문항 선택지여야 한다
 */
record VocabAnswerRequest(@Nullable String choiceId) {
}
