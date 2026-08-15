package app.accentury.backend.common;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 서비스 전역 설정 - 활성 테스트와 점수 버전, 세션 정책.
 * <p>
 * 세션은 생성 시점의 {@code testVersion}과 {@code scoreVersion}에 고정된다 (API 명세서 §5.4).
 * 프로토타입에서는 활성 버전을 이 설정 파일로 관리하고,
 * 버전 발행과 활성 전환(KAN-26)이 들어오면 DB 관리로 옮긴다.
 *
 * @param testVersion  활성 테스트 정의 버전 (예: gn-2026.08.1)
 * @param scoreVersion 활성 점수 버전 - 집계식과 등급 경계의 기준 (sv-0.3, KAN-21)
 * @param session      익명 세션 정책 (KAN-9)
 * @param analysis     분석 상태 폴링 정책 (KAN-23, KAN-24)
 * @param upload       음성 업로드 요청 제한 (KAN-23)
 * @param completion   완료 API 요청 제한 (KAN-16)
 * @param cors         웹 테스트 CORS allowlist (KAN-23, KAN-31)
 * @param result       결과 응답의 등급별 자산과 공유 URL (KAN-25)
 */
@ConfigurationProperties(prefix = "accentury")
public record AccenturyProperties(String testVersion, String scoreVersion, Session session,
                                  @DefaultValue Analysis analysis, @DefaultValue Upload upload,
                                  @DefaultValue Completion completion, @DefaultValue Cors cors,
                                  @DefaultValue Result result) {

    /**
     * @param ttl 세션 토큰 수명 - 테스트 소요 5분의 여유 배수인 30분 (§2.1, §7)
     */
    public record Session(Duration ttl) {
    }

    /**
     * @param pollAfterMs          다음 상태 조회까지 클라이언트가 기다릴 시간 - 서버가 통제하고,
     *                             부하 상승 시 값을 올려 폴링 압력을 줄인다 (§5.3)
     * @param congestedPollAfterMs 혼잡 판정 시의 폴링 간격 - 분석이 밀리면 폴링이 부하를
     *                             증폭하므로(§5.3 - 대기 체류 20배) 서버가 스스로 간격을 올린다 (KAN-24)
     * @param congestionThreshold  혼잡 판정 기준 - 진행 중(in-flight) 분석 전달 건수가
     *                             이 값 이상이면 {@code congestedPollAfterMs}를 반환한다
     * @param retention            분석 작업 보존 기간 - 세션, 결과와 같은 24시간 (§5.5)
     * @param processingTimeout    실행 잔류 한도 - 워커가 실행을 시작하고도(startedAt) 종결을
     *                             못 남긴 작업을 이 시간 뒤 RETRYABLE_FAILED로 정리한다 (KAN-24).
     *                             AI 호출 재시도 전체 소요(aiTimeout x 시도 횟수 + 대기)보다 길어야 한다.
     *                             큐 대기 시간은 세지 않는다 (Codex sol 리뷰 P1)
     * @param queuedTimeout        큐 유실 한도 - 실행을 시작하지 못한 채 이 시간이 지난 작업의
     *                             정리. 접수와 실행 사이 프로세스 사망 대비다. 정상 큐 소진
     *                             시간보다 길게, 복구가 사용자에게 보이도록 세션 TTL보다는
     *                             짧게 잡는다 (Codex sol 리뷰 P2)
     * @param aiBaseUrl            AI 분석 서버(FastAPI) 주소 (§4.1). 미설정이면 분석을 전달하지
     *                             않는 개발 모드다 - 작업은 PROCESSING으로 남다가 타임아웃 처리된다
     * @param aiTimeout            AI 호출의 연결과 읽기 타임아웃 - 추론 P95 3초의 여유 배수
     * @param aiRetries            AI 일시 장애(연결 실패, 5xx)의 재전송 횟수 - 오디오가 메모리에
     *                             살아 있는 전달 시점에만 가능하다 (FR-DP-01, KAN-24 재큐잉)
     * @param dispatchConcurrency  분석 전달 워커 수 - GPU 동시 슬롯(§5.3)을 넘지 않게 잡는다
     */
    public record Analysis(@DefaultValue("800") long pollAfterMs,
                           @DefaultValue("3000") long congestedPollAfterMs,
                           @DefaultValue("30") int congestionThreshold,
                           @DefaultValue("24h") Duration retention,
                           @DefaultValue("60s") Duration processingTimeout,
                           @DefaultValue("5m") Duration queuedTimeout,
                           @Nullable String aiBaseUrl,
                           @DefaultValue("10s") Duration aiTimeout,
                           @DefaultValue("2") int aiRetries,
                           @DefaultValue("4") int dispatchConcurrency) {
    }

    /**
     * @param rateLimitPerMinute IP당 분당 업로드 허용 횟수 (§2.5, NFR-SC-04).
     *                           임계치는 부하 테스트 후 확정한다 (§7, KAN-28)
     */
    public record Upload(@DefaultValue("30") int rateLimitPerMinute) {
    }

    /**
     * @param rateLimitPerMinute 세션당 분당 {@code /complete} 허용 횟수 (§2.5, KAN-16 AC).
     *                           폴링 대상 엔드포인트라(§3.6) IP가 아니라 세션 단위다 - NAT 뒤의
     *                           여러 정상 세션이 서로의 한도를 깎지 않는다. 서버가 내려주는
     *                           {@code pollAfterMs} 기준값 800ms를 그대로 따르는 클라이언트가
     *                           분당 약 75회이므로(§5.3 규칙 1 - 정상 트래픽), 60초 실행 잔류
     *                           한도(§3.4)까지 이어지는 합법 폴링이 걸리지 않게 그 위로 잡는다
     *                           (Codex sol 리뷰 P2). 임계치는 부하 테스트 후 확정한다 (§7, KAN-28)
     */
    public record Completion(@DefaultValue("120") int rateLimitPerMinute) {
    }

    /**
     * @param allowedOrigins 스탠드얼론 웹 테스트(KAN-31) 오리진 allowlist (§2.5).
     *                       비어 있으면 교차 출처 요청을 허용하지 않는다
     */
    public record Cors(@DefaultValue List<String> allowedOrigins) {
    }

    /**
     * {@code /result} 응답의 등급별 자산 (§3.7, KAN-25) - 서버가 내려주므로 앱 배포 없이
     * 설정 변경만으로 문구와 이미지를 교체할 수 있다 (KAN-29, 30 소비).
     * 완결성(5개 등급 전부, 빈 값 없음)은 기동 시 {@code TierAssets}가 강제한다.
     *
     * @param webTestUrl 공유 카드가 여는 웹 테스트 URL - 공유 유입 계측용 캠페인 파라미터가
     *                   붙은 완성 URL을 설정값 그대로 반환한다 (2026-08-14 확정 - 전 등급
     *                   공통 고정값 하나, KAN-30). 개인 식별 요소를 넣지 않는다
     * @param tiers      등급 code(소문자 키) → 자산. 키는 {@code ScorePolicyRegistry.TIER_CODES}와
     *                   대소문자 무시 1:1이어야 한다
     */
    public record Result(@Nullable String webTestUrl, @DefaultValue Map<String, TierAsset> tiers) {
    }

    /**
     * 등급 하나의 결과 화면과 공유 자산 (§3.7 - comment, share.imageUrl, share.text).
     *
     * @param comment   등급별 진단 코멘트 - 결과 화면에 그대로 표시된다 (KAN-29)
     * @param imageUrl  등급별 정적 공유 이미지 - 사전 제작 자산, 개인 점수 미포함 (KAN-30)
     * @param shareText 공유 카드 문구 - 이름 없는 1인칭 (KAN-30)
     */
    public record TierAsset(@Nullable String comment, @Nullable String imageUrl,
                            @Nullable String shareText) {
    }
}
