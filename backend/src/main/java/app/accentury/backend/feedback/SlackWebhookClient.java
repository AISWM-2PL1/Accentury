package app.accentury.backend.feedback;

/**
 * 슬랙 Incoming Webhook 한 번 호출 (KAN-211 2단계).
 * <p>
 * 인터페이스를 두는 것은 테스트가 실제 웹훅을 때리지 않게 하려는 것이 전부다 - 구현은
 * {@link RestSlackWebhookClient} 하나이고, 설정이 없을 때를 위한 no-op 구현은 두지 않는다.
 * 껐다 켜는 판단은 전송기({@link FeedbackSlackNotifier})가 한 곳에서 하고, 꺼져 있으면 이
 * 인터페이스는 아예 호출되지 않는다.
 */
interface SlackWebhookClient {

    /**
     * 채널에 메시지 한 줄을 올린다.
     *
     * @param text 슬랙 mrkdwn 본문 - 이스케이프는 호출자가 이미 끝낸 상태로 넘긴다.
     * @throws RuntimeException 전송이 실패했을 때. 재시도도 무시도 여기서 정하지 않는다 -
     *                          후기 원본은 DB에 있으므로 호출자가 로그만 남기고 넘어간다.
     */
    void post(String text);
}
