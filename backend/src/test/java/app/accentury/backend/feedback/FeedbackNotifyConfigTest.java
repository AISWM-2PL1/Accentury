package app.accentury.backend.feedback;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import app.accentury.backend.observability.ServiceMetrics;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 큐가 넘칠 때 알림을 버리되 흔적을 남긴다 (KAN-211, Codex 리뷰 P1).
 * <p>
 * 스프링 컨텍스트 없이 돈다 - 보는 것은 거절 처리 하나이고, 그것은 실행기 용량을 좁히면 실제
 * 알림 없이 재현된다. main의 용량(큐 200)으로는 이 경로에 닿게 할 방법이 사실상 없어, 여기서만
 * core 1 / max 1 / queue 1로 좁힌 실행기를 따로 만든다.
 */
class FeedbackNotifyConfigTest {

    @Test
    void 큐가_꽉_차면_알림을_버리고_카운터를_올린다() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = narrowExecutor(registry);
        // 워커 하나를 붙들어 둔다 - 이 래치가 풀리기 전까지 뒤 작업은 큐에서 기다린다.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS), "첫 작업이 워커를 잡아야 한다");

            executor.execute(() -> await(release));   // 큐 한 자리를 채운다
            executor.execute(() -> await(release));   // 자리가 없다 - 여기서 버려진다

            assertEquals(1.0, registry.get(ServiceMetrics.FEEDBACK_NOTIFY_DROPPED).counter().count(),
                    "버린 알림은 카운터에 남아야 한다 - 이 지표가 폐기를 아는 유일한 경로다");
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void 아무것도_버리지_않아도_카운터는_등록돼_있다() {
        // "0"과 "모름"을 구분하기 위한 사전 등록이다 - 첫 폐기 때 만들면 폐기가 없는 동안
        // 대시보드에 지표가 아예 없다 (RateLimits의 429 카운터와 같은 이유).
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = narrowExecutor(registry);
        try {
            assertEquals(0.0, registry.get(ServiceMetrics.FEEDBACK_NOTIFY_DROPPED).counter().count());
        } finally {
            executor.shutdown();
        }
    }

    /** main의 조립(FeedbackNotifyConfig)과 같은 거절 처리를, 용량만 좁혀 재현한다. */
    private static ThreadPoolTaskExecutor narrowExecutor(SimpleMeterRegistry registry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("feedback-notify-test-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setRejectedExecutionHandler(
                FeedbackNotifyConfig.discardAndCount(FeedbackNotifyConfig.droppedCounter(registry)));
        executor.initialize();
        return executor;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
