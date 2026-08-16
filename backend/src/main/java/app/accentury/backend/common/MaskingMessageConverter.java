package app.accentury.backend.common;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

/**
 * 로그 메시지에 {@link LogMasking}을 적용하는 logback 컨버터 (§2.6, KAN-28 AC).
 * <p>
 * {@code logback-spring.xml}이 {@code mask}라는 변환어로 등록하고, 패턴이
 * {@code %mask(%m)}으로 감싸 쓴다 - 어떤 로거가 무엇을 찍든 출력 직전에 한 번 걸러진다.
 * 예외 스택트레이스는 {@link MaskingThrowableConverter}가 맡는다.
 */
public class MaskingMessageConverter extends CompositeConverter<ILoggingEvent> {

    @Override
    protected String transform(ILoggingEvent event, String in) {
        return LogMasking.mask(in);
    }
}
