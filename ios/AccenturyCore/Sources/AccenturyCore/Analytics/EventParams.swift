import Foundation

/// SDK가 자기 몫으로 예약한 접두사. 우리가 보내면 SDK 단에서 거부된다.
private let reservedPrefixes = ["firebase_", "google_", "ga_"]

/// 이벤트 하나에 실을 수 있는 파라미터 수 (GA4 상한)
private let maxParams = 25

/// 이름 길이 상한 (GA4 상한). 이벤트명·파라미터명에 같이 걸린다.
private let maxNameLength = 40

/// 파라미터 값의 길이 상한 (GA4 상한). 넘으면 **자르지 않고 버린다** — 잘린 값은 원래 값과 다른
/// 집계 축이 되는데, 그 사실이 대시보드에는 드러나지 않는다. 우리 스키마의 값은 전부 짧은
/// 코드라(등급 코드·채널·사유) 100자를 넘는 값은 애초에 우리 것이 아니다.
private let maxValueLength = 100

/// 이벤트명·파라미터명으로 쓸 수 있는 이름인가 — 영문 소문자로 시작하는 snake_case, 40자 이내.
///
/// GA4 스펙은 대문자도 허용하지만(대소문자를 구분한다) **소문자만 받는다.** 우리 스키마의 이름은
/// 전부 소문자라(`web/src/analytics/events.ts`), 대문자가 섞여 들어온다는 것은 오타이거나 다른
/// 규약을 쓰는 코드가 붙었다는 뜻이다. 그대로 통과시키면 `Item_Shown`이 `item_shown`과 별개의
/// 축으로 쌓여 두 지표가 조용히 갈린다.
///
/// 안드로이드는 같은 판정을 정규식 하나로 적는다(`analytics/EventParams.kt`). 여기서 손으로 푸는
/// 이유는 `NSRegularExpression`이 이 한 줄을 위해 Foundation 객체를 하나 더 세우기 때문이다 —
/// 규칙이 ASCII 세 종류뿐이라 스칼라를 직접 보는 편이 짧다.
public func isAnalyticsName(_ name: String) -> Bool {
    let scalars = Array(name.unicodeScalars)
    guard (1...maxNameLength).contains(scalars.count) else { return false }
    guard isLowercaseLetter(scalars[0]) else { return false }
    for scalar in scalars.dropFirst() {
        guard isLowercaseLetter(scalar) || isDigit(scalar) || scalar == "_" else { return false }
    }
    return !reservedPrefixes.contains(where: name.hasPrefix)
}

private func isLowercaseLetter(_ scalar: Unicode.Scalar) -> Bool {
    ("a"..."z").contains(scalar)
}

private func isDigit(_ scalar: Unicode.Scalar) -> Bool {
    ("0"..."9").contains(scalar)
}

/// 웹이 보낸 파라미터 JSON을 타입이 살아 있는 맵으로 옮긴다. 객체가 아니면 nil이다.
///
/// 웹이 보내는 값은 신뢰 경계 밖이다 — ``parseVoiceItemStart(_:)``·``parseSharePayload(_:)``와 같은
/// 전제인데, 여기서 걸러야 하는 것은 안전이 아니라 **집계 축의 위생**이다. 규격을 벗어난 이름이
/// 그대로 흘러가면 GA4에 지울 수 없는 축이 하나 생기고(이벤트·파라미터 정의는 사후 삭제가 안 된다),
/// 그 축은 사람이 다시 읽어야 하는 대시보드가 된다.
///
/// **JSON 원본의 타입을 그대로 살린다**는 것이 이 함수의 존재 이유다 (``EventParam`` 주석). 웹은
/// `duration_ms`·`item_seq`를 숫자로 보내는데, 문자열로 뭉개 넘기면 GA4에서 평균·P95를 낼 수 없다.
/// 그래서 `Codable`이 아니라 `JSONSerialization`을 쓴다 — 이쪽은 정수와 실수를 `NSNumber` 안의
/// 표현으로 구분해 주지만, `JSONDecoder`로 같은 판정을 하려면 값마다 디코드를 세 번 시도해야 하고
/// 그 순서가 곧 규칙이 된다.
///
/// 이벤트째 버리는 경우와 값 하나만 버리는 경우를 나눈다. JSON이 깨졌거나 객체가 아니면 실을 것이
/// 무엇인지 알 수 없어 이벤트를 버리지만, 값 하나가 규격 밖인 경우에는 **나머지를 실어 보낸다** —
/// 파라미터 하나 때문에 사건 자체를 잃으면 퍼널 카운트가 줄어들고, 그 손실은 대시보드에서
/// "일어나지 않은 일"과 구분되지 않는다.
///
/// null은 값 없이 지나간다. 웹 스키마의 `campaign`이 null일 수 있는데(공유 링크로 들어오지 않은
/// 실행), Firebase 파라미터에는 "값이 없다"를 담을 자리가 없고 GA4는 빠진 파라미터를 `(not set)`으로
/// 센다 — 빈 문자열을 넣으면 그 자리가 값 하나로 세어져 유입 없는 실행과 빈 캠페인이 섞인다.
public func parseEventParams(_ paramsJson: String) -> [String: EventParam]? {
    guard let data = paramsJson.data(using: .utf8),
          let root = try? JSONSerialization.jsonObject(with: data),
          let object = root as? [String: Any]
    else { return nil }

    var params: [String: EventParam] = [:]
    /*
     * 상한을 넘은 뒤의 값은 어차피 SDK가 버린다. 안드로이드는 웹이 보낸 순서로 앞의 25개를
     * 남기지만(`JsonObject`가 문서 순서를 지킨다) Swift 딕셔너리에는 그 순서가 없다 —
     * 그래서 이름순으로 자른다. 어느 쪽이든 요점은 같다: 무엇이 실릴지가 실행마다 달라지면
     * 안 된다. 우리 스키마의 가장 큰 이벤트도 파라미터가 셋이라 실제로 걸릴 일은 없다.
     */
    for key in object.keys.sorted() {
        if params.count >= maxParams { break }
        guard isAnalyticsName(key), let param = eventParam(object[key]) else { continue }
        params[key] = param
    }
    return params
}

/// JSON 값 하나를 ``EventParam``으로. 실을 수 없는 값이면 nil이다.
///
/// 불리언을 먼저 보는 이유는 `JSONSerialization`이 `true`를 `NSNumber`로 준다는 사실 때문이다 —
/// 순서를 뒤집으면 `true`가 정수 1로 실려 축이 통째로 뒤바뀐다. 지금 스키마에 불리언은 없지만
/// 문자열로 옮겨 둔다: 나중에 하나 생겼을 때 조용히 사라지는 것보다 `true`/`false`라는 두 값의
/// 축으로 보이는 편이 낫다.
///
/// 배열·객체는 버린다. Firebase의 이벤트 파라미터는 평평해서 실을 자리가 없다 (`bridge.ts`의
/// `logAnalyticsEvent` 주석).
private func eventParam(_ value: Any?) -> EventParam? {
    if let string = value as? String {
        return string.count > maxValueLength ? nil : .text(string)
    }
    // null(`NSNull`)·배열·객체는 `NSNumber`가 아니라 여기서 함께 걸린다.
    guard let number = value as? NSNumber else { return nil }
    if CFGetTypeID(number) == CFBooleanGetTypeID() {
        return .text(number.boolValue ? "true" : "false")
    }
    return CFNumberIsFloatType(number) ? .amount(number.doubleValue) : .count(number.int64Value)
}
