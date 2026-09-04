/**
 * 테스트 완료와 최종 결과 (KAN-16/25, API 명세서 §3.6, §3.7).
 * <p>
 * {@code /complete}가 완주를 검증하고 집계(KAN-21)를 1회 수행해 결과를 확정하며,
 * {@code /result}(KAN-25)는 확정된 결과를 읽기만 한다. 결과는 세션과 함께
 * 24시간 뒤 파기된다 (§5.5).
 */
@NullMarked
package app.accentury.backend.result;

import org.jspecify.annotations.NullMarked;
