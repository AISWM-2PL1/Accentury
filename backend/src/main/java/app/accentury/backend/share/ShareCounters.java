package app.accentury.backend.share;

import app.accentury.backend.common.AccenturyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 공유 전송 완료의 유일한 증가 진입점 (KAN-164).
 * <p>
 * 수신 기록과 카운터 증가가 <b>한 트랜잭션</b>이다. 둘을 가르면 기록만 남고 카운트가 빠지는
 * 틈(기록 뒤 장애)이 생기고, 그 틈은 같은 ID가 다시 와도 메워지지 않는다 - 기록이 "이미 셌다"고
 * 답하기 때문이다. 집계 카운터(KAN-106)와 달리 사용자 요청 뒤에 붙는 것이 아니라 이 요청 자체가
 * 집계라, 실패를 삼키지 않고 그대로 올린다 - 카카오는 2xx가 아니면 실패로 보고, 우리는 로그에서
 * 원인을 본다.
 */
@Service
public class ShareCounters {

    private static final Logger log = LoggerFactory.getLogger(ShareCounters.class);

    private final ShareWebhookReceiptRepository receipts;
    private final ShareDailyCounterRepository counters;
    private final ZoneId zone;

    ShareCounters(ShareWebhookReceiptRepository receipts, ShareDailyCounterRepository counters,
                  AccenturyProperties properties) {
        this.receipts = receipts;
        this.counters = counters;
        // 집계 카운터와 같은 일자 경계다 - 리포트가 두 표를 나란히 읽는다 (§6.1).
        this.zone = properties.analytics().zone();
    }

    /**
     * 카카오가 알려 온 전송 1건.
     *
     * @param resourceId 카카오가 웹훅마다 붙인 고유 ID - 멱등 키
     * @param campaign   앱이 실어 보낸 캠페인 상수 (형식 검증은 호출부)
     * @param at         받은 시각 - 일자 경계는 설정 타임존 기준이다.
     * @return true면 새로 셌다. false면 같은 ID를 이미 받은 중복 콜백이라 세지 않았다.
     */
    @Transactional
    public boolean recordSent(String resourceId, String campaign, Instant at) {
        if (receipts.insertIfAbsent(resourceId, at) == 0) {
            log.info("카카오 공유 웹훅 중복 콜백 - 세지 않는다 resourceId={}", resourceId);
            return false;
        }
        LocalDate date = LocalDate.ofInstant(at, zone);
        counters.countSent(ShareDailyCounter.idOf(date, campaign), date, campaign);
        return true;
    }
}
