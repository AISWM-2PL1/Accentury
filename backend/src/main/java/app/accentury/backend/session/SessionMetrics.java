package app.accentury.backend.session;

import app.accentury.backend.observability.ServiceMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 동시 활성 세션 수 게이지 (KAN-38).
 * <p>
 * KAN-24 재검토 트리거의 "동시 응시자 만 명대"를 재는 값이다 - 익명 서비스라 사용자 수를 셀
 * 방법이 세션 행뿐이고, 만료되지 않은 행 수가 곧 지금 응시 중인 사람 수의 상한이다(테스트를
 * 끝내고 앱을 닫은 사람도 TTL 30분 동안은 남는다 - 위로 치우친 값이라는 뜻이라, 바이럴
 * 스파이크 감지라는 용도에는 맞다).
 * <p>
 * 값은 조회할 때 센다. {@code expires_at}에 인덱스가 없어 세션 테이블 전체 스캔이지만, 발행
 * 주기마다 한 번(배포에서 1분)이고 만료 정리가 도는 테이블이라 행 수가 동시 응시자 규모에
 * 머문다 - 목표 1,000명이면 수천 행이다. 폴링 경로에 얹히는 조회가 아니므로 캐시를 두지 않는다.
 * 이 전제가 깨지는 것(테이블이 커지고 게이지 조회가 발행을 지연시킴)은 인덱스를 더할 신호다.
 */
@Component
class SessionMetrics {

    SessionMetrics(MeterRegistry meterRegistry, TestSessionRepository repository) {
        Gauge.builder(ServiceMetrics.SESSIONS_ACTIVE,
                        repository, self -> self.countByExpiresAtAfter(Instant.now()))
                .description("만료되지 않은 세션 행 수 - KAN-24 트리거 \"동시 응시자\"의 측정값 (KAN-38)")
                .register(meterRegistry);
    }
}
