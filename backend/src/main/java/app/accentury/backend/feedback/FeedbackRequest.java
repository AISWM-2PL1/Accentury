package app.accentury.backend.feedback;

import org.jspecify.annotations.Nullable;

/**
 * {@code POST /v0/sessions/{sessionId}/feedback}의 요청 본문 (KAN-211).
 * <p>
 * 필드 검증은 서비스가 맡아 공통 오류 봉투로 응답한다 - 그래서 전부 {@code @Nullable}이다
 * ({@code VocabAnswerRequest}와 같은 이유).
 *
 * @param rating       선택 - 별점 1~5. 없으면 별점 없이 서술만 남긴 후기다. <b>{@code Integer}가 아니라
 *                     {@code Number}로 받는다</b> (Codex 리뷰 P1): {@code Integer}였을 때
 *                     {@code {"rating": 2.5}}가 400이 아니라 <b>2로 잘려 201로 저장됐다</b>
 *                     (2026-09-15 실측 - 이 레포의 Jackson 설정은 float를 int로 강제 변환한다).
 *                     별점은 사람이 고른 칸이라 2.5는 "2를 의도했다"가 아니라 계약 위반이므로,
 *                     원본 수를 그대로 받아 {@code FeedbackService}가 정수인지 판정해 400을 낸다.
 *                     전역 ObjectMapper 설정을 바꾸지 않은 것은 다른 엔드포인트의 역직렬화까지
 *                     함께 바뀌기 때문이다.
 * @param contactEmail 선택 - 회신용 이메일. 빈 문자열은 미입력과 같게 본다 (폼이 빈 칸을
 *                     빈 문자열로 보내는 것을 400으로 돌려줄 이유가 없다).
 * @param body         필수 - 후기 본문. trim 후 1~500자.
 */
record FeedbackRequest(@Nullable Number rating,
                       @Nullable String body,
                       @Nullable String contactEmail) {
}
