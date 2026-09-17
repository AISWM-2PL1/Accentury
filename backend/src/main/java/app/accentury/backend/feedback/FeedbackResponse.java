package app.accentury.backend.feedback;

/**
 * 후기 제출의 응답 (KAN-211) - 필드는 {@code accepted} 하나다.
 * <p>
 * 후기 id도, 저장 시각도 돌려주지 않는다. 클라이언트가 할 수 있는 일이 "보냈다"를 보여 주는
 * 것뿐이라(조회도 수정도 없다) 그 밖의 값은 계약만 넓힌다.
 *
 * @param accepted 후기 수락 여부 - 항상 true다 (실패는 오류 봉투로 나간다).
 *                 최초 저장은 201, 같은 키의 재전송은 200이고 본문은 같다.
 */
record FeedbackResponse(boolean accepted) {
}
