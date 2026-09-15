package app.accentury.backend.feedback;

import app.accentury.backend.common.AccenturyProperties;
import app.accentury.backend.common.SsmPlaceholder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * 저장된 후기를 슬랙 채널로 올린다 (KAN-211 2단계, 2026-09-15 결정).
 * <p>
 * 개발팀이 후기를 읽는 곳은 슬랙 채널 하나({@code #feedback})다. 무료 플랜이라 수단은 Incoming
 * Webhook 하나이고, staging과 prod가 채널을 나누지 않는다 - 구분은 메시지 머리의 환경 라벨이
 * 한다.
 * <p>
 * <b>슬랙은 부수 기능이다.</b> 본체는 후기 저장(1단계)이고, 슬랙이 죽어도 URL이 없어도 API는
 * 정상이어야 한다. 그래서 이 빈은 <b>설정과 무관하게 항상 등록되고</b>({@code admin.token}이나
 * {@code kakao-admin-key}처럼 경로를 없애는 조건부 등록이 아니다) 설정이 없으면 스스로 조용히
 * 꺼진다. 전송 실패도 던지지 않고 로그 한 줄로 끝낸다 - 후기 원본은 DB에 있으므로 재시도로
 * 얻을 것보다 커밋된 요청 뒤에서 다시 실패할 위험이 크다.
 */
@Component
public class FeedbackSlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(FeedbackSlackNotifier.class);

    /** 환경 라벨을 읽을 수 없을 때 - 로컬 개발과 테스트가 이쪽이다. */
    static final String LOCAL_LABEL = "local";

    /** 세션 id에서 메시지에 싣는 길이. 같은 후기를 로그와 맞춰 볼 수 있으면 되고, 전체는 필요 없다. */
    static final int SESSION_ID_PREFIX_LENGTH = 8;

    private final SlackWebhookClient client;
    private final SessionFeedbackRepository repository;
    private final String label;
    private final boolean enabled;

    @Autowired
    FeedbackSlackNotifier(SlackWebhookClient client, SessionFeedbackRepository repository,
                          AccenturyProperties properties) {
        this(client, repository, properties.feedback().slackWebhookUrl(),
                properties.result().webTestUrl());
    }

    /** 테스트가 설정값 두 개만으로 만드는 자리. */
    FeedbackSlackNotifier(SlackWebhookClient client, SessionFeedbackRepository repository,
                          @Nullable String slackWebhookUrl, @Nullable String webTestUrl) {
        this.client = client;
        this.repository = repository;
        this.label = label(webTestUrl);
        this.enabled = configured(slackWebhookUrl);
        if (this.enabled) {
            // URL은 절대 로그에 넣지 않는다 - 아는 사람은 누구나 그 채널에 글을 쓸 수 있는 시크릿이다.
            log.info("후기 슬랙 알림 켜짐 - 채널 라벨 {}", this.label);
        } else {
            log.info("후기 슬랙 알림 꺼짐 - accentury.feedback.slack-webhook-url 미설정 또는 자리 표시 값");
        }
    }

    /** 알림이 켜졌는가 - 값이 없거나 Terraform 자리 표시 값이면 false다. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 후기 저장이 <b>커밋된 뒤</b> 슬랙으로 올린다.
     * <p>
     * {@link TransactionPhase#AFTER_COMMIT}인 이유: 커밋 전에 보내면 그 뒤 롤백된 후기가 채널에
     * 남는다 - 읽는 사람에게는 DB에 없는 후기가 된다. 그래서 {@code FeedbackService}는 이벤트를
     * <b>트랜잭션 콜백 안에서</b> 발행한다 - AFTER_COMMIT 리스너는 활성 트랜잭션 안에서 발행된
     * 이벤트만 커밋 뒤에 받고, 트랜잭션 밖에서 발행하면 기본 설정({@code fallbackExecution = false})에서
     * 조용히 버려진다. 같은 키의 재전송 경로는 발행 자체를 하지 않으므로 같은 후기가 두 번
     * 올라가지 않는다.
     * <p>
     * {@code @Async}인 이유: 이 메서드가 끝날 때까지 요청 스레드가 잡혀 있으면 슬랙이 느린 날에
     * 후기 API의 응답도 함께 느려진다 - 저장은 이미 끝났는데 부수 기능이 사용자를 기다리게 하는
     * 셈이다. 전용 실행기는 {@link FeedbackNotifyConfig}가 만든다.
     * <p>
     * 예외를 전부 삼킨다 - 커밋 뒤라 되돌릴 것이 없고, 후기 원본은 DB에 있다. 예외 메시지에
     * 웹훅 URL이 섞여 나올 수 있으므로 클래스 이름과 메시지만 찍고, 마지막 관문은 로그 마스킹이다
     * ({@code LogMasking}의 {@code SLACK_WEBHOOK}).
     */
    @Async("feedbackNotifyExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFeedbackSubmitted(FeedbackSubmitted event) {
        if (!enabled) {
            return;
        }
        try {
            // id로 다시 읽는다 - 이벤트가 엔티티를 싣지 않는 이유는 FeedbackSubmitted의 javadoc이다.
            Optional<SessionFeedback> feedback = repository.findById(event.feedbackId());
            if (feedback.isEmpty()) {
                return;
            }
            client.post(message(feedback.get(), label));
        } catch (Exception e) {
            log.warn("후기 슬랙 전송 실패 feedbackId={} cause={}", event.feedbackId(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 채널에 올릴 메시지 - 머리 한 줄과 인용문으로 감싼 본문이다.
     * <p>
     * <b>회신 이메일 값은 싣지 않는다.</b> 유무만 적는다 - 채널은 개발팀 전원이 보고 슬랙 무료
     * 플랜은 지난 메시지를 지우지 않으므로, 개인 식별 정보를 흘리면 되돌릴 방법이 없다. 답장할
     * 후기를 만나면 DB에서 세션 id로 찾는다 (그래서 세션 id 앞자리를 싣는다).
     * <p>
     * 머리 줄의 나머지는 후기를 읽을 때 맥락이 되는 스냅샷이다 ({@link SessionFeedback}) -
     * 어떤 등급을 본 사람의 말인지, 어느 문항/점수 버전인지, 어느 화면(플랫폼)에서 썼는지,
     * 그리고 합성 트래픽(E2E 스모크)이 남긴 것은 아닌지.
     */
    static String message(SessionFeedback feedback, String label) {
        StringBuilder message = new StringBuilder()
                .append('[').append(label).append("] 새 이용 후기 ")
                .append(feedback.rating() == null ? "별점 없음" : "★" + feedback.rating())
                .append(" · 등급 ").append(feedback.tierCode())
                .append(" · ").append(feedback.testVersion())
                .append('/').append(feedback.scoreVersion())
                // 플랫폼은 세션 생성 때 안 보내면 없다 - 그 경로가 웹이라 빈 자리를 web으로 읽는다.
                .append(" · ").append(feedback.platform() == null ? "web" : feedback.platform())
                .append(" · ").append(feedback.traffic())
                .append(" · 세션 ").append(sessionIdPrefix(feedback.sessionId()))
                .append(" · 연락처 ").append(feedback.contactEmail() == null ? "없음" : "있음");
        // 본문은 줄마다 인용문 접두를 붙인다 - 여러 줄 후기가 머리 줄과 섞이지 않는다.
        for (String line : escape(feedback.body()).split("\\R", -1)) {
            message.append("\n> ").append(line);
        }
        return message.toString();
    }

    /**
     * 슬랙 mrkdwn의 필수 이스케이프 세 가지 (https://api.slack.com/reference/surfaces/formatting).
     * {@code &}를 먼저 바꾼다 - 뒤로 미루면 바로 앞에서 만든 {@code &lt;}의 앰퍼샌드까지 다시 바꿔
     * {@code &amp;lt;}가 된다.
     * <p>
     * 후기 본문은 사용자가 쓴 자유 서술이라 {@code <http://...|링크>}나 {@code <!channel>} 같은
     * 슬랙 문법이 그대로 들어올 수 있다 - 이스케이프하지 않으면 후기가 채널 전체 멘션이 된다.
     */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 메시지 머리에 붙는 환경 라벨 - 결과 URL({@code accentury.result.web-test-url})의 호스트다.
     * <p>
     * staging과 prod가 같은 {@code deploy} 프로파일을 쓰므로(KAN-140) 프로파일로는 구분이 안 되고,
     * 결과 URL은 이미 환경마다 다른 값이 SSM으로 주입된다 - 환경 이름 파라미터를 하나 더 만들면
     * 그것과 도메인이 어긋날 자리만 생긴다. 값이 없거나 호스트를 못 읽으면 로컬로 본다.
     */
    static String label(@Nullable String webTestUrl) {
        if (webTestUrl == null || webTestUrl.isBlank()) {
            return LOCAL_LABEL;
        }
        try {
            String host = new URI(webTestUrl.strip()).getHost();
            return host == null || host.isBlank() ? LOCAL_LABEL : host;
        } catch (URISyntaxException e) {
            return LOCAL_LABEL;
        }
    }

    /** 설정된 웹훅 URL이 진짜 값인가 - 비었거나 Terraform 자리 표시 값이면 아니다. */
    private static boolean configured(@Nullable String slackWebhookUrl) {
        return slackWebhookUrl != null && !slackWebhookUrl.isBlank()
                && !SsmPlaceholder.UNSET.equals(slackWebhookUrl.strip());
    }

    private static String sessionIdPrefix(String sessionId) {
        return sessionId.substring(0, Math.min(SESSION_ID_PREFIX_LENGTH, sessionId.length()));
    }
}
