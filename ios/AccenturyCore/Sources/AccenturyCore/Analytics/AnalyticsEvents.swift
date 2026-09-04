import Foundation

/// 파라미터 값 하나. GA4가 받는 세 가지(문자열·정수·실수)를 그대로 옮긴 타입이다.
/// 안드로이드 `analytics/AppEvents.kt`의 `EventParam`과 같은 자리이고 같은 세 케이스다.
///
/// 문자열 하나로 뭉치지 않는 이유가 이 티켓의 AC를 직접 건드린다. 숫자를 문자열로 실으면 GA4가
/// 그 값을 **차원(dimension)** 으로 잡아 평균·백분위를 낼 수 없다 — "대기 화면 체류 시간의
/// 평균·P95를 대시보드에서 바로 확인한다"가 그 자리에서 깨진다. 숫자로 실어야 측정항목(metric)이
/// 되고, `duration_ms`·`elapsed_ms`·`count`가 전부 그 대상이다 (`web/src/analytics/events.ts`).
///
/// ``count``와 ``amount``를 나누는 것은 Firebase에 `NSNumber`를 정수로 넘길지 실수로 넘길지의
/// 문제다. 전부 실수로 보내면 `item_seq`·`pending_item_count` 같은 개수가 대시보드에 3.0으로 찍힌다.
public enum EventParam: Equatable, Sendable {

    /// 집계 축이 되는 코드값 (`channel`, `reason`, `tier_code`)
    case text(String)

    /// 정수 — 개수·순번·밀리초
    case count(Int64)

    /// 실수 — 지금 스키마에는 없지만 웹이 보낸 JSON에 소수가 오면 여기로 온다
    case amount(Double)
}

/// 결과 공유 이벤트 (FR-SH-06). 이름은 안드로이드 `ShareEvents`와 **같은 문자열**이어야 한다 —
/// 두 플랫폼이 같은 GA4 속성에 쌓이므로 한 글자만 달라도 같은 사건이 두 축으로 갈린다.
///
/// ## 탭은 네이티브가 세지 않는다
///
/// [친구에게 공유하기] 탭을 세는 이름은 웹의 `share_clicked` 하나다. 웹은 실행을 가리지 않고 그
/// 탭을 세고, 앱 안에서는 그 이벤트가 브리지 `logEvent`를 타고 네이티브 sink로 들어온다 —
/// 네이티브가 같은 탭에 이름을 하나 더 붙이면 같은 사람의 같은 탭이 앱과 웹에서 다른 축으로
/// 갈려, 공유 퍼널을 보려면 두 이름을 합집합으로 세야 한다. 그래서 네이티브가 세는 것은
/// **통로가 실제로 열렸다는 결과 사건(``launched``)뿐**이다.
///
/// 알 수 있는 것은 그대로다 — `share_clicked` 수와 ``launched`` 수의 차이가 곧 "눌렀는데 아무
/// 통로도 열리지 않은 비율"이고, 이름만 셋에서 둘로 줄었다.
///
/// ## 여기서 세는 것의 한계 — "전송 완료"는 셀 수 없다
///
/// ``launched``는 **카톡(또는 공유 시트)을 띄우는 데 성공했다**까지다. 카카오 SDK는 카톡으로
/// 넘긴 뒤 사용자가 실제로 보냈는지를 돌려주지 않고, `UIActivityViewController`도 마찬가지다 —
/// 우리 앱은 전환 시점에 뒤로 내려가므로 그 뒤를 관측할 방법이 클라이언트에 없다.
///
/// 실제 전송 수는 카카오 개발자 콘솔의 공유 웹훅(서버 콜백)으로만 알 수 있고 그건 BE 후속
/// 작업이다.
public enum ShareEvents {

    /// 공유 화면을 실제로 띄웠다. 위 한계 참고 — 보냈다는 뜻이 아니고, 못 열었으면 부르지 않는다
    public static let launched = "share_launched"

    /// ``launched``의 유일한 파라미터. 어느 통로가 얼마나 쓰이는지가 카카오 경로의 값을 판단할 근거다
    public static let paramChannel = "channel"
}

/// 네이티브 녹음 화면이 세는 이벤트 (KAN-33).
///
/// 이름·파라미터·값 표기는 `web/src/analytics/events.ts`의 `recording_retake`와 **정확히** 같다.
/// 웹 녹음기(브라우저 단독 실행)와 네이티브 녹음 화면은 사람이 하는 같은 일이라 한 지표로 쌓여야
/// 하고, 어긋나면 같은 사건이 이름이 다른 두 축으로 갈린다.
///
/// 사유는 ``reasonUser`` 하나뿐이다. 나머지 둘(QUALITY·FAILED)은 서버가 되돌려보낸 문항을 다시
/// 녹음하는 경우인데, 그 자리는 웹(분석 대기 화면)이 소유하고 이미 거기서 센다 — 사유를 아는
/// 유일한 곳이 거기라 네이티브가 다시 셀 이유도 방법도 없다.
public enum RecordingEvents {

    /// 녹음을 마친 뒤 [재녹음]으로 되돌아갔다
    public static let retake = "recording_retake"

    /// 사람이 읽는 1-기반 문항 번호 (정의의 `seq`가 아니다 — ``VoiceItemStart/itemNumber``)
    public static let paramItemSeq = "item_seq"

    /// 재녹음 사유. 값 표기는 이벤트명과 달리 대문자다 (웹 `RetakeReason`이 그렇다)
    public static let paramReason = "reason"

    /// 아무 문제 없이 사용자가 다시 읽기로 한 것
    public static let reasonUser = "USER"
}

/// 공유 통로를 계측 파라미터 값으로 바꾼다. 순수 함수라 조합을 테스트로 못박는다.
///
/// enum 케이스 이름(`kakao`·`systemSheet`)을 문자열로 뽑아 쓰지 않는 이유: 계측 값은 집계 축이라
/// 한 번 쌓이면 이름을 바꿀 수 없는데, enum 케이스는 코드 사정으로 언제든 바뀔 수 있는 이름이다.
/// 둘을 여기서 끊어 두면 리팩터링이 지난 집계와 새 집계를 갈라놓지 않는다. 값 표기는 이벤트명과
/// 같은 snake_case이고, 안드로이드 `channelParam`이 내는 두 문자열과 같아야 한다.
public func channelParam(_ channel: ShareChannel) -> String {
    switch channel {
    case .kakao: return "kakao"
    case .systemSheet: return "system_sheet"
    }
}
