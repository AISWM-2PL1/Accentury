package app.accentury.backend.feedback;

/**
 * 후기 한 건이 새로 저장됐다 (KAN-211 2단계).
 * <p>
 * 싣는 것은 id 하나다. 저장된 행 전체를 이벤트에 담으면 커밋 뒤에 도는 리스너가 트랜잭션이
 * 닫힌 엔티티를 들고 있게 되고, 그 사이 보존 기간 정리가 지운 행을 살아 있는 것처럼 읽는다 -
 * 리스너가 id로 다시 조회하면 "그때 있었나"까지 함께 확인된다 ({@link FeedbackSlackNotifier}).
 * <p>
 * 발행은 {@code FeedbackService.submit}의 트랜잭션 콜백 안이고, 같은 키의 재전송 경로에서는
 * 발행하지 않는다 - 같은 후기가 채널에 두 번 올라가지 않는다.
 *
 * @param feedbackId {@code session_feedback.id}
 */
record FeedbackSubmitted(String feedbackId) {
}
