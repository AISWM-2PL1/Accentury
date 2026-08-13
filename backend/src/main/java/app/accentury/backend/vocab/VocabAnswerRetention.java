package app.accentury.backend.vocab;

import app.accentury.backend.common.AccenturyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 어휘 답안 보존 기간 정리 (API 명세서 §5.5 - 세션과 결과는 24시간 후 파기).
 * <p>
 * 분석 작업과 같은 보존 설정({@code accentury.analysis.retention})을 쓴다 - 답안과
 * 억양 점수는 결과 조합(KAN-16·25)의 두 입력이라 수명이 갈라지면 한쪽만 사라진 채
 * 집계될 수 있다. 세션 삭제(30분 TTL)와 연동하지 않는 이유도 분석 작업과 같다 -
 * 답안은 세션 만료 후에도 결과 보존 기간 동안 남아야 한다.
 */
@Component
public class VocabAnswerRetention {

    private static final Logger log = LoggerFactory.getLogger(VocabAnswerRetention.class);

    private final VocabAnswerRepository repository;
    private final AccenturyProperties properties;

    public VocabAnswerRetention(VocabAnswerRepository repository, AccenturyProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 분석 작업 정리(15분 지연)와 시작 시점만 어긋나게 둔다 - 같은 순간의 삭제 몰림 방지 */
    @Scheduled(initialDelay = 25, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(properties.analysis().retention());
        long removed = repository.deleteByCreatedAtBefore(cutoff);
        if (removed > 0) {
            log.info("보존 기간이 지난 어휘 답안 {}건 삭제", removed);
        }
    }
}
