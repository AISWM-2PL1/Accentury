/**
 * 운영 관측성 - 지표 계측과 그 이름의 정본 (KAN-38).
 * <p>
 * 값이 나는 자리에서 등록하는 미터(회로 상태 {@code AnalysisDispatchConfig}, 임시파일 잔존
 * {@code VoiceTempSweeper}, 429 {@code RateLimits})는 각자의 패키지에 남아 있고, 여기에는
 * 어느 한 도메인의 것이 아닌 계측(HTTP 전 구간, 세션 총계, 분석 파이프라인 집계)과
 * 이름 목록({@link app.accentury.backend.observability.ServiceMetrics})이 있다.
 * <p>
 * 로그 쪽 절반은 {@code common} 패키지다 - correlation ID는 {@code CorrelationIdFilter},
 * 마스킹은 {@code LogMasking}이다. 규약 전체는 {@code docs/wiki/observability.md}에 있다.
 */
@NullMarked
package app.accentury.backend.observability;

import org.jspecify.annotations.NullMarked;
