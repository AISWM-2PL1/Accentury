package app.accentury.backend.feedback;

import app.accentury.backend.common.AccenturyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 후기 슬랙 알림의 조립 (KAN-211 2단계).
 * <p>
 * <b>{@code @EnableAsync}가 레포에 처음 들어온다.</b> 켜기 전에 다른 {@code @Async}가 없음을
 * 확인했으므로 이 설정이 기존 동작을 바꾸지 않는다. 앞으로도 그러려면 규칙 하나가 필요하다 -
 * <b>실행기 이름을 명시하지 않은 {@code @Async}는 만들지 않는다.</b> 이름 없는 {@code @Async}는
 * 컨텍스트의 기본 실행기로 떨어지는데, 이 앱에는 AI 전달 풀({@code analysisExecutor})처럼 용량이
 * 좁은 실행기가 이미 있어 후기 알림 하나가 분석 전달을 밀어낼 수 있다.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class FeedbackNotifyConfig {

    /**
     * 알림 전용 실행기 - 이름은 {@code @Async("feedbackNotifyExecutor")}가 가리키는 값이다.
     * <p>
     * 워커 1~2개는 알림이 후기 1건당 하나이고 호출이 3초 안에 끝나기 때문이다({@link RestSlackWebhookClient}) -
     * 후기가 몰려도 초당 수십 건이 될 리 없다. 큐가 넘치면 <b>버린다</b>
     * ({@link ThreadPoolExecutor.DiscardPolicy}) - 알림은 부수 기능이라 거절 예외를 던져 봐야 받을
     * 곳이 없고(커밋 뒤의 비동기 경로다), 무한 큐로 받으면 슬랙 장애가 backend 메모리 문제로
     * 번진다. 버려진 알림의 후기는 DB에 그대로 있다.
     * <p>
     * 종료 때는 큐에 든 것까지 마치되 5초만 기다린다 ({@code AnalysisDrainLifecycle}이 쓰는 종료
     * 예산과 달리 짧다 - 알림 하나 때문에 컨테이너 유예를 쓸 이유가 없다).
     */
    @Bean
    ThreadPoolTaskExecutor feedbackNotifyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("feedback-notify-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    /**
     * 웹훅 클라이언트 - 설정이 없어도 빈은 만든다.
     * <p>
     * 전송기({@link FeedbackSlackNotifier})가 항상 등록되고 꺼짐 판단을 스스로 하므로, 여기서
     * 조건부 등록을 겹치면 "꺼짐"의 정의가 두 곳으로 갈라진다. URL이 없을 때 이 빈은 만들어지되
     * 한 번도 호출되지 않는다.
     */
    @Bean
    SlackWebhookClient slackWebhookClient(AccenturyProperties properties, ObjectMapper objectMapper) {
        String url = properties.feedback().slackWebhookUrl();
        return new RestSlackWebhookClient(RestSlackWebhookClient.restClient(),
                url == null ? "" : url.strip(), objectMapper);
    }
}
