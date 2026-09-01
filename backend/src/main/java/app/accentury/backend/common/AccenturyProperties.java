package app.accentury.backend.common;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 서비스 전역 설정 - 세션과 업로드, 분석, 결과 정책.
 * <p>
 * <b>활성 테스트 버전과 점수 버전은 여기 없다</b> (KAN-26). 발행 입력이 DB로 확정되면서
 * (2026-08-09, §6) 활성 버전은 {@code active_test_version} 한 행이 정본이 됐고, 점수 버전은
 * 그 정의가 선언한 값을 따른다 - 세션은 생성 시점의 두 버전에 고정된다 (§5.4). 설정 파일과
 * DB에 같은 값이 둘 있으면 어긋날 수 있는데, 어긋난 배포를 기동 검사로 막느니 정본을 하나로
 * 줄이는 편이 낫다는 판단이다.
 *
 * @param session        익명 세션 정책 (KAN-9)과 세션 생성 요청 제한 (KAN-28)
 * @param analysis       분석 상태 폴링 정책 (KAN-23, KAN-24)과 AI 회로 차단 정책 (KAN-28)
 * @param upload         음성 업로드 요청 제한 (KAN-23, KAN-28)과 임시파일 정책 (KAN-27)
 * @param vocab          어휘 답안 요청 제한 (KAN-28)
 * @param completion     완료 API 요청 제한 (KAN-16)
 * @param cors           웹 테스트 CORS allowlist (KAN-23, KAN-31)
 * @param result         결과 응답의 등급별 자산과 공유 URL (KAN-25)
 * @param analytics      익명 집계 카운터의 일자 경계와 조회 상한 (KAN-106)
 * @param admin          운영자 전용 API(§6)의 공유 시크릿 (KAN-106, KAN-26)
 * @param trustedProxies 요청 제한의 기준 IP를 정할 때 신뢰하는 프록시 대역 (KAN-28, §2.5).
 *                       CIDR 또는 단일 IP 목록이고, 직접 접속한 상대가 이 목록에 들어야만
 *                       {@code X-Forwarded-For}를 읽는다. 비어 있으면 헤더를 무시하고 접속 IP만
 *                       쓴다 - 프록시 없는 배포에서 헤더 위조로 제한을 우회하지 못하게 하는
 *                       안전한 기본값이다. <b>ALB 뒤에 배포할 때는 반드시 지정해야 한다</b> -
 *                       지정하지 않으면 모든 사용자가 ALB IP 하나를 공유해 서로의 한도를 깎는다.
 */
@ConfigurationProperties(prefix = "accentury")
public record AccenturyProperties(Session session,
                                  @DefaultValue Analysis analysis, @DefaultValue Upload upload,
                                  @DefaultValue Vocab vocab,
                                  @DefaultValue Completion completion, @DefaultValue Cors cors,
                                  @DefaultValue Result result, @DefaultValue Analytics analytics,
                                  @DefaultValue Admin admin,
                                  @DefaultValue List<String> trustedProxies) {

    /**
     * @param ttl                세션 토큰 수명 - 테스트 소요 5분의 여유 배수인 30분 (§2.1, §7)
     * @param rateLimitPerMinute IP당 분당 세션 생성 허용 횟수 (§2.5, KAN-28). 인증이 없는
     *                           엔드포인트라(§2.1) IP가 유일한 키다. 정상 응시자는 응시 1회당
     *                           1건이고 재응시해도 분당 1~2건이므로, 공유 IP(학교, 카페 NAT) 뒤의
     *                           동시 시작을 덮는 여유 배수로 잡는다. 임계치는 부하 테스트 후
     *                           확정한다 (§7, KAN-40).
     */
    public record Session(Duration ttl, @DefaultValue("30") int rateLimitPerMinute) {
    }

    /**
     * @param pollAfterMs          다음 상태 조회까지 클라이언트가 기다릴 시간 - 서버가 통제하고,
     *                             부하 상승 시 값을 올려 폴링 압력을 줄인다 (§5.3).
     * @param congestedPollAfterMs 혼잡 판정 시의 폴링 간격 - 분석이 밀리면 폴링이 부하를
     *                             증폭하므로(§5.3 - 대기 체류 20배) 서버가 스스로 간격을 올린다 (KAN-24).
     * @param congestionThreshold  혼잡 판정 기준 - 진행 중(in-flight) 분석 전달 건수가
     *                             이 값 이상이면 {@code congestedPollAfterMs}를 반환한다.
     * @param retention            분석 작업 보존 기간 - 세션, 결과와 같은 24시간 (§5.5)
     * @param processingTimeout    실행 잔류 한도 - 워커가 실행을 시작하고도(startedAt) 종결을
     *                             못 남긴 작업을 이 시간 뒤 RETRYABLE_FAILED로 정리한다 (KAN-24).
     *                             AI 호출 재시도 전체 소요(aiTimeout x 시도 횟수 + 대기)보다 길어야 한다.
     *                             큐 대기 시간은 세지 않는다 (Codex sol 리뷰 P1).
     * @param queuedTimeout        큐 유실 한도 - 실행을 시작하지 못한 채 이 시간이 지난 작업의
     *                             정리. 접수와 실행 사이 프로세스 사망 대비다. 정상 큐 소진
     *                             시간보다 길게, 복구가 사용자에게 보이도록 세션 TTL보다는
     *                             짧게 잡는다 (Codex sol 리뷰 P2).
     * @param aiBaseUrl            AI 분석 서버(FastAPI) 주소 (§4.1). 미설정이면 분석을 전달하지
     *                             않는 개발 모드다 - 작업은 PROCESSING으로 남다가 타임아웃 처리된다.
     * @param aiTimeout            AI 호출의 연결과 읽기 타임아웃 - 추론 P95 3초의 여유 배수
     * @param aiRetries            AI 일시 장애(연결 실패, 5xx)의 재전송 횟수 - 오디오가 메모리에
     *                             살아 있는 전달 시점에만 가능하다 (FR-DP-01, KAN-24 재큐잉).
     * @param dispatchConcurrency  분석 전달 워커 수 - GPU 동시 슬롯(§5.3)을 넘지 않게 잡는다.
     * @param aiHealthTimeout      회로 복구 프로브({@code GET /internal/v0/health}, §4.2)의 연결과
     *                             읽기 타임아웃 (KAN-28). 추론 없이 즉답하는 엔드포인트라 분석
     *                             호출보다 짧게 잡는다 - 프로브가 스케줄러 스레드를 오래 붙들면
     *                             같은 풀의 다른 잡(타임아웃 정리, 임시파일 청소)이 밀린다.
     * @param circuitFailureThreshold AI 장애가 이만큼 연속되면 회로를 연다 (KAN-28, §4.2).
     *                             회로가 열린 동안 업로드는 GPU를 소모하지 않는 503
     *                             {@code ANALYSIS_UNAVAILABLE}로 즉시 끊기고, 큐에 남아 있던
     *                             작업도 AI를 부르지 않고 종결한다. 1건짜리 순간 장애로 열리지
     *                             않을 만큼 크고, 장애가 지속될 때 워커가 타임아웃을 반복하며
     *                             묶이지 않을 만큼 작아야 한다.
     * @param circuitProbeInterval 회로가 열린 뒤 health 프로브를 다시 던지는 간격 (KAN-28).
     *                             첫 프로브까지의 대기(쿨다운)도 같은 값이다. 사용자 요청을
     *                             프로브로 쓰지 않는다 - 오디오는 재전송할 수 없어(FR-DP-01)
     *                             프로브로 뽑힌 사용자만 실패를 떠안기 때문이다.
     * @param aiToken              AI 서버와 나눠 갖는 내부 호출 시크릿 (KAN-36). AI가 전용 호스트로
     *                             갈라지면서 "같은 compose 네트워크라 backend만 부른다"는 전제가 사라져,
     *                             보안 그룹 한 겹 뒤에 요청마다 {@code X-Accentury-Internal-Token} 헤더로
     *                             대조한다. 미설정이면 헤더를 붙이지 않는다 - 로컬 개발 편의이고, 배포
     *                             프로파일은 값을 요구한다 ({@code DeploymentConfigGuard}).
     * @param shutdownBudget       종료 신호 뒤 실행 중인 분석의 완료를 기다리는 상한 (KAN-166).
     *                             대기 중(미시작) 작업은 기다리지 않고 즉시 실패로 정리하므로,
     *                             이 값은 "AI 호출 1회가 끝나는 데 걸리는 최악 시간"만 덮으면
     *                             된다 - aiTimeout보다 길어야 한다 (기동 시 검증). 웹 요청
     *                             유예(spring.lifecycle.timeout-per-shutdown-phase)와 합쳐
     *                             컨테이너 강제 종료 상한(compose stop_grace_period, ECS
     *                             stopTimeout 120초) 안에 들어야 한다.
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
                           @DefaultValue("4") int dispatchConcurrency,
                           @DefaultValue("2s") Duration aiHealthTimeout,
                           @DefaultValue("5") int circuitFailureThreshold,
                           @DefaultValue("5s") Duration circuitProbeInterval,
                           @DefaultValue("90s") Duration shutdownBudget,
                           @Nullable String aiToken) {
    }

    /**
     * @param rateLimitPerMinute        IP당 분당 업로드 허용 횟수 (§2.5, NFR-SC-04).
     *                                  임계치는 부하 테스트 후 확정한다 (§7, KAN-40).
     * @param sessionRateLimitPerMinute 세션당 분당 업로드 허용 횟수 (§2.5 - IP와 세션 이중 제한,
     *                                  KAN-28). IP 제한은 NAT 뒤 다수 사용자를 고려해 느슨하므로,
     *                                  세션 하나가 그 여유를 혼자 쓰지 못하게 막는 두 번째 축이다.
     *                                  정상 응시는 음성 5문항 x 1회 = 5건이고 최악이 문항당 시도
     *                                  상한 5회 x 5문항 = 25건이라(§5.1), 멱등 재전송까지 덮는
     *                                  여유 배수로 잡는다.
     * @param tempDir                   업로드 임시파일 전용 디렉터리 (KAN-27). 정상 경로에서는 파일이
     *                                  생기지 않지만(메모리 전용 불변식), 생긴다면 반드시 이 한 곳에
     *                                  모여야 청소 잡과 권한 제한이 닿는다.
     *                                  {@code spring.servlet.multipart.location}과 같은 값이어야 하고,
     *                                  기동 시 {@code VoiceTempDirectory}가 둘의 일치를 강제한다.
     * @param tempRetention             잔존 임시파일 삭제 기준 - 수정 시각이 이보다 오래된 파일만
     *                                  지운다 (KAN-27 - 30분). 업로드 1건은 초 단위로 끝나므로 살아
     *                                  있는 요청의 파일을 앞질러 지우지 않는 여유 배수다.
     */
    public record Upload(@DefaultValue("30") int rateLimitPerMinute,
                         @DefaultValue("60") int sessionRateLimitPerMinute,
                         @Nullable String tempDir,
                         @DefaultValue("30m") Duration tempRetention) {
    }

    /**
     * @param rateLimitPerMinute 세션당 분당 어휘 답안 제출 허용 횟수 (§2.5, KAN-28).
     *                           인증 뒤에만 닿는 경로라 세션이 키다. 정상 응시는 어휘 5문항
     *                           x 1회 = 5건이고 문항당 답안은 하나뿐이라(§3.5 - 새 키 재제출은
     *                           409) 그 이상은 재전송이거나 남용이다.
     */
    public record Vocab(@DefaultValue("60") int rateLimitPerMinute) {
    }

    /**
     * @param rateLimitPerMinute 세션당 분당 {@code /complete} 허용 횟수 (§2.5, KAN-16 AC).
     *                           폴링 대상 엔드포인트라(§3.6) IP가 아니라 세션 단위다 - NAT 뒤의
     *                           여러 정상 세션이 서로의 한도를 깎지 않는다. 서버가 내려주는
     *                           {@code pollAfterMs} 기준값 800ms를 그대로 따르는 클라이언트가
     *                           분당 약 75회이므로(§5.3 규칙 1 - 정상 트래픽), 60초 실행 잔류
     *                           한도(§3.4)까지 이어지는 합법 폴링이 걸리지 않게 그 위로 잡는다
     *                           (Codex sol 리뷰 P2). 임계치는 부하 테스트 후 확정한다 (§7, KAN-40).
     */
    public record Completion(@DefaultValue("120") int rateLimitPerMinute) {
    }

    /**
     * @param allowedOrigins 스탠드얼론 웹 테스트(KAN-31) 오리진 allowlist (§2.5).
     *                       비어 있으면 교차 출처 요청을 허용하지 않는다.
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
     *                   공통 고정값 하나, KAN-30). 개인 식별 요소를 넣지 않는다.
     * @param tiers      등급 code(소문자 키) → 자산. 키는 {@code ScorePolicyRegistry.TIER_CODES}와
     *                   대소문자 무시 1:1이어야 한다.
     */
    public record Result(@Nullable String webTestUrl, @DefaultValue Map<String, TierAsset> tiers) {
    }

    /**
     * 익명 집계 카운터 (KAN-106, SRS FR-AN-10) - 영속 데이터로 허용된 유일한 것이다 (NFR-PR-03).
     *
     * @param zone          집계 행의 일자 경계를 정하는 타임존 (2026-08-17 확정 - Asia/Seoul).
     *                      리포트를 읽는 사람의 하루(KAN-20 등급 분포)와 행의 하루가 같아야
     *                      한다 - UTC로 자르면 KST 09:00에 날짜가 바뀌어 "8월 17일 응시 수"가
     *                      한국의 8월 17일과 어긋난다. 저장 시각 체계(Instant)와는 별개다.
     * @param maxQueryDays  조회 한 번의 최대 기간(일). 실수로 전 기간을 훑는 질의가 운영 DB를
     *                      붙잡지 않게 막는 상한이다 - 넘으면 400이다.
     */
    public record Analytics(@DefaultValue("Asia/Seoul") ZoneId zone,
                            @DefaultValue("366") int maxQueryDays) {
    }

    /**
     * 운영자 전용 API(§6)의 인증 (KAN-106, KAN-26).
     *
     * @param token 관리자 엔드포인트 셋({@code GET /admin/v0/analytics},
     *              {@code PUT /admin/v0/active-version}, {@code GET /admin/v0/test-definitions})이
     *              공유하는 시크릿 - 세션 토큰이 아니라 §6이 규정한 별도 관리자 인증이다.
     *              <b>미설정이 기본값이고, 그러면 엔드포인트 자체가 등록되지 않는다</b> (404) -
     *              설정을 빼먹어도 열려 있는 경로가 생기지 않게 하는 안전한 기본값이다
     *              (trustedProxies와 같은 계열). 검사는 {@code AdminAuth}가 한 곳에서 한다.
     *              <p>
     *              KAN-106 시절 이름은 {@code accentury.analytics.admin-token}이었다. 관리자
     *              API가 셋으로 늘면서 집계 네임스페이스 아래 두는 것이 맞지 않게 되어 옮겼다
     *              (2026-08-19, KAN-26). 환경 변수 이름도 {@code ACCENTURY_ADMIN_TOKEN}으로
     *              바뀐다 - 아직 배포 전이라 옮길 설정이 없다.
     */
    public record Admin(@Nullable String token) {
    }

    /**
     * 등급 하나의 결과 화면과 공유 자산 (§3.7 - comment, share.imageUrl, share.text).
     *
     * @param comment   등급별 진단 코멘트 - 결과 화면에 그대로 표시된다 (KAN-29).
     * @param imageUrl  등급별 정적 공유 이미지 - 사전 제작 자산, 개인 점수 미포함 (KAN-30)
     * @param shareText 공유 카드 문구 - 이름 없는 1인칭 (KAN-30)
     */
    public record TierAsset(@Nullable String comment, @Nullable String imageUrl,
                            @Nullable String shareText) {
    }
}
