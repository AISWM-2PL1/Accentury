package app.accentury.backend.feedback;

import app.accentury.backend.common.AccenturyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 후기 보존 기간 정리 (KAN-211).
 * <p>
 * 보존 기간은 {@code accentury.feedback.retention}(기본 1년)이다 - 세션과 결과의 24시간보다
 * 훨씬 긴 것은 후기가 세션의 부속물이 아니라 제품 개선의 입력이기 때문이고, 그래서 세션
 * 정리와 독립으로 돈다 ({@code session_feedback}에 FK가 없는 것과 같은 이유,
 * {@link SessionFeedback}). 무한 보존이 아닌 것은 회신용 이메일이 개인 식별 정보여서다 -
 * 언젠가는 지워져야 한다.
 */
@Component
public class FeedbackRetention {

    private static final Logger log = LoggerFactory.getLogger(FeedbackRetention.class);

    private final SessionFeedbackRepository repository;
    private final AccenturyProperties properties;
    private final Clock clock;

    @Autowired
    public FeedbackRetention(SessionFeedbackRepository repository, AccenturyProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    /**
     * 테스트가 시각을 고정하는 자리 (Codex 리뷰 P2, {@code RateLimits}와 같은 모양).
     * <p>
     * 경계가 보존 기간의 양 끝에 있어({@code deleteByCreatedAtBefore}는 {@code <}다) 실제 시계로는
     * "정확히 365일 전"을 만들 수 없다 - 행을 넣고 삭제를 부르는 사이에 시간이 흐르면 그 행은
     * 경계 위가 아니라 경계 밖이 된다. 고정 시계여야 경계 자체를 단언할 수 있다.
     */
    FeedbackRetention(SessionFeedbackRepository repository, AccenturyProperties properties,
                      Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 다른 정리 잡(분석 15분, 어휘 25분, 결과 35분, 웹훅 45분 지연)과 시작 시점만 어긋나게 둔다 - 같은 순간의 삭제 몰림 방지 */
    @Scheduled(initialDelay = 55, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void purgeExpired() {
        // 경계는 cutoff 미만이다 - 정확히 보존 기간 전에 들어온 행은 아직 남는다.
        Instant cutoff = clock.instant().minus(properties.feedback().retention());
        int removed = repository.deleteByCreatedAtBefore(cutoff);
        if (removed > 0) {
            log.info("보존 기간이 지난 후기 {}건 삭제", removed);
        }
    }
}
