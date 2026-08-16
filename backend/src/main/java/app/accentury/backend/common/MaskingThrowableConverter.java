package app.accentury.backend.common;

import ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter;
import ch.qos.logback.classic.spi.IThrowableProxy;

/**
 * 예외 스택트레이스에 {@link LogMasking}을 적용하는 logback 컨버터 (§2.6, KAN-28 AC).
 * <p>
 * 메시지만 가리면 절반이다 - 토큰이 실릴 가능성이 가장 큰 곳이 오히려 예외 메시지다
 * (HTTP 클라이언트나 파서가 요청 헤더와 본문 일부를 메시지에 담는다).
 * <p>
 * {@code ThrowableHandlingConverter}를 상속하는 것이 중요하다 - logback은 패턴에
 * 예외 처리 컨버터가 없으면 스택트레이스를 자동으로 덧붙이는데, 그 자동 추가분은
 * 마스킹을 거치지 않는다. 이 컨버터가 패턴에 있으면 자동 추가가 일어나지 않는다.
 */
public class MaskingThrowableConverter extends ExtendedThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return LogMasking.mask(super.throwableProxyToString(tp));
    }
}
