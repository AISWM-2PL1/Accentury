package app.accentury.backend.analytics;

import java.time.LocalDate;

/**
 * 집계 행 쓰기의 계약 (KAN-106) - 증가 두 갈래가 전부다.
 * <p>
 * {@link AnalyticsCounters}가 순서(UPDATE → 없으면 INSERT → 지면 UPDATE)를 정하고,
 * 여기 구현({@link DailyCounterStore})은 각 갈래를 <b>자기 트랜잭션 하나</b>로 끝낸다.
 * 둘을 갈라 둔 덕에 "쓰기가 실패해도 사용자 요청은 성공한다"는 티켓 제약을 실패하는
 * 구현으로 직접 검증할 수 있다.
 */
interface CounterStore {

    /**
     * 있는 행에 증가분을 원자적으로 더한다 (read-modify-write 금지 - 티켓 제약).
     *
     * @return false면 그 키의 행이 아직 없다 - {@link #insert}로 만들어야 한다.
     */
    boolean increment(String id, CounterDelta delta);

    /**
     * 그 날의 첫 행을 증가분을 담은 채로 만든다.
     *
     * @throws RuntimeException 같은 키를 다른 요청이 먼저 만든 경우 (유니크 제약 위반) -
     *                          호출부가 UPDATE로 되돌아가는 신호다.
     */
    void insert(LocalDate statDate, String testVersion, String scoreVersion, CounterDelta delta);
}
