package app.accentury.backend.analysis;

import app.accentury.backend.common.AccenturyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 분석 전달 조립의 설정 검증 (KAN-24).
 * <p>
 * processing-timeout이 AI 재전송 최악 소요보다 짧으면 살아 있는 워커의 작업을 스위퍼가
 * 먼저 종결한다 - 관계가 설정 두 곳에 갈라져 있어 기동 시점 검증으로 고정한다.
 */
class AnalysisDispatchConfigTest {

    @Test
    void 실행_잔류_한도가_재전송_최악_소요_이하면_기동을_거부한다() {
        // ai-timeout 10s x (재시도 2 + 1) + 백오프 0.9s = 30.9s > processing-timeout 30s
        AnalysisDispatchConfig config = new AnalysisDispatchConfig();
        // 검증이 조립보다 먼저 실행되므로 협력자는 쓰이지 않는다
        assertThrows(IllegalStateException.class, () -> config.analysisDispatcher(
                props(Duration.ofSeconds(30)), null, null, null, null));
    }

    @Test
    void 기본_설정_조합은_검증을_통과해_실제_디스패처를_조립한다() {
        // 기본값 60s > 30.9s - 기본 설정이 스스로 어긋나면 여기서 잡힌다
        AnalysisDispatcher dispatcher = new AnalysisDispatchConfig().analysisDispatcher(
                props(Duration.ofSeconds(60)), new ThreadPoolTaskExecutor(), null,
                new AnalysisBacklog(), new ObjectMapper());

        assertInstanceOf(HttpAnalysisDispatcher.class, dispatcher);
    }

    /** 기본값 조합에 ai-base-url만 지정한 설정 - processing-timeout만 시나리오별로 바꾼다 */
    private static AccenturyProperties props(Duration processingTimeout) {
        return new AccenturyProperties("gn-2026.08.1", "sv-0.3",
                new AccenturyProperties.Session(Duration.ofMinutes(30)),
                new AccenturyProperties.Analysis(800, 3000, 30, Duration.ofHours(24),
                        processingTimeout, Duration.ofMinutes(5), "http://ai.test",
                        Duration.ofSeconds(10), 2, 4),
                new AccenturyProperties.Upload(30),
                new AccenturyProperties.Completion(60),
                new AccenturyProperties.Cors(List.of()),
                new AccenturyProperties.Result(null, Map.of()));
    }
}
