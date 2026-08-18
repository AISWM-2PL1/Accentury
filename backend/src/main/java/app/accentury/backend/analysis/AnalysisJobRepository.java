package app.accentury.backend.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, String> {

    /**
     * 세션의 전체 시도 이력 - 상태 일괄 조회(§3.4)와 채점 대상 선정(§5.1)의 입력이다.
     * (session_id, item_id) 인덱스를 타는 단건 조회라 폴링 경로에 둘 수 있다 (§5.3 규칙 6).
     * 시도 순서는 createdAt 기준이고(attempt는 표시용), 같은 밀리초의 동시 업로드는 attempt로 가른다.
     */
    List<AnalysisJob> findBySessionIdOrderByCreatedAtAscAttemptAsc(String sessionId);

    /** 멱등 재전송 판별 (§5.2) - 유니크 제약과 같은 키 조합의 단건 조회 */
    Optional<AnalysisJob> findBySessionIdAndItemIdAndIdempotencyKey(
            String sessionId, String itemId, String idempotencyKey);

    /**
     * 세션과 문항의 전체 시도 행 수 - 테스트 검증용. attempt 번호와 시도 상한 판정은
     * {@link #countAiConsumingAttempts}가 정본이다 (§5.1 - 전달 실패는 세지 않는다).
     */
    long countBySessionIdAndItemId(String sessionId, String itemId);

    /**
     * 업로드가 1건이라도 있었던 음성 문항 수 - 답안 응답의 진행도(answeredCount) 입력이다
     * (KAN-15, §3.5). §3.4 대표 상태의 "NOT_SUBMITTED 아님"과 같은 기준이라 시도의 성공
     * 여부는 따지지 않는다 - 제출됨 표시가 목적이고 채점 대상 선정(§5.1)과는 무관하다
     * (2026-08-11 확정).
     */
    @Query("select count(distinct j.itemId) from AnalysisJob j where j.sessionId = :sessionId")
    long countDistinctSubmittedItems(@Param("sessionId") String sessionId);

    /**
     * 시도 상한 판정용 (§2.5, §5.1) - AI 자원을 쓰지 않은 시도만 예산에서 뺀다.
     * 전달 실패(errorCode = ANALYSIS_UNAVAILABLE)는 AI에 도달하지 못한 것이라 제외하고,
     * AI가 실제로 분석한 뒤의 판정 실패(AUDIO_TOO_QUIET 등)는 GPU를 썼으므로 센다 -
     * 상태만 보고 RETRYABLE_FAILED 전부를 빼면 불량 녹음 반복이 상한을 우회한다
     * (Codex sol 리뷰 P1). 타임아웃(ANALYSIS_TIMEOUT)은 도달 여부가 불확실하므로 센다.
     * errorCode가 null인 행은 예산에 포함한다(coalesce) - 3치 논리로는 null 비교가
     * UNKNOWN이 되어 행이 조용히 환급되는 쪽으로 샌다 (Codex 리뷰).
     */
    @Query("""
            select count(j) from AnalysisJob j
             where j.sessionId = :sessionId and j.itemId = :itemId
               and not (j.status = app.accentury.backend.analysis.AnalysisJobStatus.RETRYABLE_FAILED
                        and coalesce(j.errorCode, '') = :undeliveredErrorCode)
            """)
    long countAiConsumingAttempts(@Param("sessionId") String sessionId,
                                  @Param("itemId") String itemId,
                                  @Param("undeliveredErrorCode") String undeliveredErrorCode);

    /**
     * 실행 시작 선점 (Codex sol 리뷰 P1) - PROCESSING이면서 아직 시작 전인 작업만 잡는다.
     * 0이면 이미 종결됐거나(타임아웃 등 - AI를 호출하면 안 된다) 다른 워커가 잡은 것이다.
     * 단독 호출도 가능하게 자체 트랜잭션을 연다 (기존 트랜잭션에는 합류).
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AnalysisJob j
               set j.startedAt = :startedAt
             where j.id = :id and j.startedAt is null
                   and j.status = app.accentury.backend.analysis.AnalysisJobStatus.PROCESSING
            """)
    int markStartedIfProcessing(@Param("id") String id, @Param("startedAt") Instant startedAt);

    /**
     * 보존 기간 지난 작업 정리 (§5.5). 호출부에 트랜잭션 필요.
     * 파생 delete의 건별 삭제 대신 벌크 한 문장이다 - 세션 정리와 같은 방식
     */
    @Modifying
    @Query("delete from AnalysisJob j where j.createdAt < :cutoff")
    long deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);

    /**
     * 재응시 시 이전 세션의 시도와 점수 누적분 즉시 폐기 (KAN-107). 호출부에 트랜잭션 필요.
     * 잠금 규칙과 안전 논증(PROCESSING 행 삭제 포함)은
     * {@link app.accentury.backend.session.SessionService}의 purgeForRetake javadoc이 정본이다.
     */
    @Modifying
    @Query("delete from AnalysisJob j where j.sessionId = :sessionId")
    long deleteBySessionId(@Param("sessionId") String sessionId);

    // === KAN-24 상태 전이 - 전부 "PROCESSING일 때만"의 조건부 UPDATE다 ===
    // AI 응답 워커와 타임아웃 스위퍼가 같은 작업을 두고 경합할 수 있어, 조회 후 저장이
    // 아니라 DB 한 문장으로 원자적으로 전이한다. 반환값 0 = 이미 종결된 작업(늦은 결과).

    /** 분석 성공 종결 (§3.4 COMPLETED). 결과 필드를 함께 채운다 - 점수 누적 시점 규칙 (§4.3) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AnalysisJob j
               set j.status = app.accentury.backend.analysis.AnalysisJobStatus.COMPLETED,
                   j.intonationScore = :intonationScore, j.qualityCode = :qualityCode,
                   j.modelVersion = :modelVersion, j.scoreVersion = :scoreVersion,
                   j.finishedAt = :finishedAt
             where j.id = :id and j.status = app.accentury.backend.analysis.AnalysisJobStatus.PROCESSING
            """)
    int completeIfProcessing(@Param("id") String id,
                             @Param("intonationScore") int intonationScore,
                             @Param("qualityCode") String qualityCode,
                             @Param("modelVersion") String modelVersion,
                             @Param("scoreVersion") String scoreVersion,
                             @Param("finishedAt") Instant finishedAt);

    /** 분석 실패 종결 (§3.4 RETRYABLE_FAILED 또는 FAILED) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AnalysisJob j
               set j.status = :failedStatus, j.errorCode = :errorCode, j.finishedAt = :finishedAt
             where j.id = :id and j.status = app.accentury.backend.analysis.AnalysisJobStatus.PROCESSING
            """)
    int failIfProcessing(@Param("id") String id,
                         @Param("failedStatus") AnalysisJobStatus failedStatus,
                         @Param("errorCode") String errorCode,
                         @Param("finishedAt") Instant finishedAt);

    /**
     * 실행 잔류 일괄 정리 (KAN-24 타임아웃) - 실행을 시작하고도 종결을 못 남긴 작업
     * (실행 중 프로세스 사망 등)을 재녹음 유도 상태로 넘긴다. 큐에서 기다리는 중인
     * 작업(startedAt null)은 정상이므로 건드리지 않는다 (Codex sol 리뷰 P1).
     * 문장 하나가 <b>반드시</b> 자체 트랜잭션을 연다 (REQUIRES_NEW) - 외부 트랜잭션에
     * 합류하면 두 벌크 문장이 잠금을 겹쳐 쥐어 재응시 폐기와의 데드락 창이 되살아난다.
     * 이유는 {@link AnalysisJobTimeout#failStuckJobs} 참고 (2026-08-17 리뷰).
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AnalysisJob j
               set j.status = app.accentury.backend.analysis.AnalysisJobStatus.RETRYABLE_FAILED,
                   j.errorCode = :errorCode, j.finishedAt = :finishedAt
             where j.status = app.accentury.backend.analysis.AnalysisJobStatus.PROCESSING
                   and j.startedAt is not null and j.startedAt < :cutoff
            """)
    int failStartedBefore(@Param("cutoff") Instant cutoff,
                          @Param("errorCode") String errorCode,
                          @Param("finishedAt") Instant finishedAt);

    /**
     * 실행을 영영 시작하지 못한 작업의 정리 - 접수와 실행 사이에 프로세스가 죽어 큐가
     * 유실된 경우다. 정상 큐 대기와 구분할 수 없으므로 한도는 최악의 큐 소진 시간보다
     * 훨씬 길게 잡는다. AI에 도달하지 않았으므로 시도 예산에서 빠지는
     * ANALYSIS_UNAVAILABLE로 종결해야 한다. 문장 하나가 <b>반드시</b> 자체 트랜잭션을
     * 연다 (REQUIRES_NEW) - 이유는 {@link #failStartedBefore}와
     * {@link AnalysisJobTimeout#failStuckJobs} 참고 (2026-08-17 리뷰).
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AnalysisJob j
               set j.status = app.accentury.backend.analysis.AnalysisJobStatus.RETRYABLE_FAILED,
                   j.errorCode = :errorCode, j.finishedAt = :finishedAt
             where j.status = app.accentury.backend.analysis.AnalysisJobStatus.PROCESSING
                   and j.startedAt is null and j.createdAt < :cutoff
            """)
    int failUnstartedBefore(@Param("cutoff") Instant cutoff,
                            @Param("errorCode") String errorCode,
                            @Param("finishedAt") Instant finishedAt);
}
