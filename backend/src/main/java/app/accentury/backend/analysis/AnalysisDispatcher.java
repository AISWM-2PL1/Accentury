package app.accentury.backend.analysis;

import java.util.Arrays;

/**
 * 검증이 끝난 업로드를 AI 분석으로 넘기는 경계 (KAN-23 정의, KAN-24 구현).
 * <p>
 * 오디오 바이트는 이 경계를 지나 즉시 소멸해야 한다 - 구현은 AI 전달(§4.1) 외에
 * 어떤 저장소에도 오디오를 남기지 않는다 (FR-DP-01, §5.5).
 * <p>
 * <b>오디오 버퍼의 소유권은 {@code dispatch()} 호출과 함께 구현으로 넘어간다 (KAN-27).</b>
 * 구현은 그 건이 종결되는 즉시 - 성공, 판정 실패, 재전송 예산 소진, 예외, 큐 제출 거절
 * 어느 쪽이든 - {@link AnalysisRequest#wipeAudio()}로 버퍼를 지워야 하고, 호출부는
 * {@code dispatch()} 이후 그 배열을 다시 읽지 않는다.
 */
public interface AnalysisDispatcher {

    void dispatch(AnalysisRequest request);

    /**
     * 이 작업으로 분석을 받을 수 있는가 (KAN-28) - 업로드는 false면 작업을 만들지 않고
     * 503 {@code ANALYSIS_UNAVAILABLE}로 끊는다. 오디오를 저장하지 않아(FR-DP-01) 나중에
     * 다시 보낼 수 없으므로, 받아 두고 실패시키는 것보다 받지 않는 쪽이 정직하다.
     * <p>
     * 작업 ID를 받는 이유는 복구 시험 자리를 그 작업에 묶기 위해서다 - 자리를 잡은
     * 업로드와 실제로 전달되는 작업이 같아야 한다 ({@code AiCircuitBreaker}).
     * <p>
     * 기본은 true다 - 판정 수단이 없는 구현(개발 모드)까지 막지 않는다.
     */
    default boolean accepts(String analysisJobId) {
        return true;
    }

    /**
     * {@link #accepts(String)}로 자리를 받은 작업이 결국 전달되지 못했다 (KAN-28).
     * <p>
     * 반열림에서 {@code accepts()}는 복구 시험 자리를 그 작업 앞으로 잡아 둔다. 작업 저장이
     * 롤백되거나 큐 제출이 거절되면 그 작업은 AI에 닿지 못하는데, 자리는 잡힌 채로 남아
     * 나머지 업로드가 시험 한도만큼 막힌다. 자리를 잡았던 호출부가 그 사실을 알려주는 자리다.
     * <p>
     * {@code accepts()}가 true를 준 뒤 전달에 실패한 모든 경로에서 부르면 된다 - 이미
     * 판정이 났거나 자리를 잡지 않은 작업이면 아무 일도 일어나지 않는다.
     */
    default void abandon(String analysisJobId) {
    }

    /**
     * 막혔던 경로가 살아났는지 확인한다 (KAN-28) - {@link AnalysisAvailabilityProbe}가
     * 주기적으로 부른다. 확인할 것이 없는 구현에는 아무 일도 일어나지 않는다.
     */
    default void probeAvailability() {
    }

    /**
     * AI 분석 1건에 필요한 전부 (§4.1 meta 파트와 대응).
     *
     * @param audio WAV 원본 - 클라이언트 업로드를 그대로 패스스루한다 (§4.1).
     *              소유권은 {@code dispatch()}로 넘어간다 (위 계약 참조).
     */
    record AnalysisRequest(
            String analysisJobId,
            String sessionId,
            String itemId,
            String testVersion,
            String scoreVersion,
            long durationMs,
            byte[] audio) {

        /**
         * 오디오 버퍼를 0으로 덮어쓴다 - 분석이 종결되는 즉시 호출한다 (KAN-27, NFR-PR-03).
         * <p>
         * 참조만 끊으면 수거될 때까지 원본 음성이 힙에 그대로 남아 힙 덤프와 스왑에 실린다.
         * 파기 시점을 GC에 맡기지 않고 종결 시점으로 못박는 것이 "분석 응답 수신 즉시 삭제"
         * (KAN-27 Requirements)의 메모리 쪽 절반이다 - 디스크 쪽은 임시파일을 아예 만들지
         * 않는 설정 불변식({@code VoiceTempDirectory})이 맡는다.
         * <p>
         * 여러 번 불러도 안전하다. 다만 지운 뒤에는 재전송할 수 없으므로, 재전송 예산을 쓰는
         * 구현은 마지막 시도까지 끝난 뒤에 부른다.
         */
        public void wipeAudio() {
            wipe(audio);
        }

        /**
         * 같은 파기를 요청 객체를 만들기 전에도 쓸 수 있게 꺼내 둔다 - 업로드 서비스는
         * 검증 도중 끊긴 경로에서 아직 요청이 없는 버퍼를 지워야 한다. 한 계약의 두 쪽이
         * 각자 {@code Arrays.fill}을 재구현하면 따로 놀 수 있다 (Codex 리뷰).
         */
        public static void wipe(byte[] audio) {
            Arrays.fill(audio, (byte) 0);
        }
    }
}
