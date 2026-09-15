package app.accentury.backend.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;

/**
 * 분석 작업의 종결 전이 (KAN-24, API 명세서 §3.4).
 * <p>
 * AI 응답 워커({@link HttpAnalysisDispatcher})와 타임아웃 스위퍼({@link AnalysisJobTimeout})가
 * 같은 작업을 동시에 종결하려 할 수 있다. 모든 전이는 "PROCESSING일 때만"의 조건부 UPDATE
 * 한 문장이라 늦게 도착한 쪽은 그냥 무시된다 - 종결된 작업은 어떤 경로로도 되살아나지 않는다.
 * 무시된 결과는 유실이 아니다: 사용자는 이미 재녹음(새 시도)으로 안내된 상태다 (§5.1).
 */
@Service
public class AnalysisJobTransitions {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobTransitions.class);

    private final AnalysisJobRepository repository;

    public AnalysisJobTransitions(AnalysisJobRepository repository) {
        this.repository = repository;
    }

    /**
     * 실행 시작 선점 - 워커가 AI를 호출하기 직전에 부른다.
     *
     * @return false면 이미 종결됐거나 다른 워커가 선점한 작업이다 - AI(GPU)를 호출하면
     *         안 된다 (Codex sol 리뷰 P1 - 타임아웃 종결 후의 유령 호출 차단).
     */
    @Transactional
    public boolean start(String jobId) {
        return repository.markStartedIfProcessing(jobId, Instant.now()) > 0;
    }

    /**
     * 분석 성공 - COMPLETED로 전이하고 결과(점수, 품질, 모델과 점수 버전)를 채운다.
     * 점수는 이 시점에 세션 저장소에 누적되고(§4.3), 합산은 /complete가 1회 한다 (KAN-25).
     *
     * @return 실제로 전이했으면 true. false면 늦게 도착한 결과라 버려졌다는 뜻이므로 이 건을
     *         성공으로 세는 계측(지연 분포, KAN-38)에 넣으면 안 된다 - 사용자는 이미 재녹음
     *         안내를 받은 뒤이고, 그 건의 소요 시간은 정의상 타임아웃보다 길어 P95를 위로 민다.
     */
    @Transactional
    public boolean complete(String jobId, int intonationScore, String qualityCode,
                            String modelVersion, String scoreVersion) {
        int updated = repository.completeIfProcessing(
                jobId, intonationScore, qualityCode, modelVersion, scoreVersion, Instant.now());
        if (updated == 0) {
            // 0행은 두 경우다 - 이미 종결된 작업이거나, 재응시 폐기(KAN-107)로 행 자체가
            // 지워진 작업이다. 여기서는 구분할 수 없으므로 로그가 두 경우를 모두 말해야 한다.
            log.warn("늦은 분석 결과를 버린다 - 이미 종결됐거나 재응시 폐기로 삭제된 작업이다 jobId={}", jobId);
            return false;
        }
        // 점수는 로그에 남기지 않는다 - 결과 공개는 /result 한 곳이다 (§3.4, KAN-12).
        log.info("분석 완료 jobId={} modelVersion={}", jobId, modelVersion);
        return true;
    }

    /**
     * 분석 실패 - 재녹음이 도움이 되는 실패는 RETRYABLE_FAILED, 아닌 것은 FAILED다 (§3.4).
     * 상태 응답의 {@code error.retryable}은 이 구분에서 파생된다.
     */
    @Transactional
    public void fail(String jobId, AnalysisJobStatus failedStatus, String errorCode) {
        if (failedStatus != AnalysisJobStatus.RETRYABLE_FAILED && failedStatus != AnalysisJobStatus.FAILED) {
            throw new IllegalArgumentException("실패 전이가 아니다: " + failedStatus);
        }
        int updated = repository.failIfProcessing(jobId, failedStatus, errorCode, Instant.now());
        if (updated == 0) {
            // complete()의 0행 경우와 같다 - 종결과 폐기 삭제를 구분할 수 없다.
            log.warn("늦은 실패 통지를 버린다 - 이미 종결됐거나 재응시 폐기로 삭제된 작업이다 jobId={} errorCode={}",
                    jobId, errorCode);
        } else {
            log.info("분석 실패 jobId={} status={} errorCode={}", jobId, failedStatus, errorCode);
        }
    }

    /**
     * 여러 작업의 실패 종결을 한 문장으로 (KAN-166 종료 경로). 규칙은 {@link #fail}과 같고,
     * 이미 종결됐거나 폐기로 지워진 작업은 건너뛴다.
     *
     * @return 실제로 전이된 건수
     */
    @Transactional
    public int failAll(Collection<String> jobIds, AnalysisJobStatus failedStatus, String errorCode) {
        if (failedStatus != AnalysisJobStatus.RETRYABLE_FAILED && failedStatus != AnalysisJobStatus.FAILED) {
            throw new IllegalArgumentException("실패 전이가 아니다: " + failedStatus);
        }
        if (jobIds.isEmpty()) {
            return 0;
        }
        int updated = repository.failAllIfProcessing(jobIds, failedStatus, errorCode, Instant.now());
        log.info("분석 실패 일괄 종결 {}건 (요청 {}건) status={} errorCode={}",
                updated, jobIds.size(), failedStatus, errorCode);
        return updated;
    }
}
