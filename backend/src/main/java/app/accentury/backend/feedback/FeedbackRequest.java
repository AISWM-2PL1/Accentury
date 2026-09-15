package app.accentury.backend.feedback;

import org.jspecify.annotations.Nullable;

/**
 * {@code POST /v0/sessions/{sessionId}/feedback}의 요청 본문 (KAN-211).
 * <p>
 * 필드 검증은 서비스가 맡아 공통 오류 봉투로 응답한다 - 그래서 전부 {@code @Nullable}이다
 * ({@code VocabAnswerRequest}와 같은 이유).
 *
 * @param rating       선택 - 별점 1~5. 없으면 별점 없이 서술만 남긴 후기다.
 * @param contactEmail 선택 - 회신용 이메일. 빈 문자열은 미입력과 같게 본다 (폼이 빈 칸을
 *                     빈 문자열로 보내는 것을 400으로 돌려줄 이유가 없다).
 * @param body         필수 - 후기 본문. trim 후 1~500자.
 */
record FeedbackRequest(@Nullable Integer rating,
                       @Nullable String body,
                       @Nullable String contactEmail) {
}
