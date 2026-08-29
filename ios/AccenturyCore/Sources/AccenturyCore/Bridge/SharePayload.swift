import Foundation

/// 카드 문구 상한. 카카오 피드 템플릿 title 제한(200자) 그대로다 — 넘으면 어차피 잘려 나간다.
private let maxTextLength = 200

/// 공유 URL에 있으면 안 되는 문자. `java.net.URI`가 `URISyntaxException`을 던지는 자리와 맞춘다:
/// C0 제어문자(U+0000–U+001F), DEL(U+007F), C1 제어문자(U+0080–U+009F), 그리고 공백·개행 전체.
private let rejectedUrlCharacters = CharacterSet.controlCharacters.union(.whitespacesAndNewlines)

/// 결과 공유 카드 자산 — 웹이 네이티브에 건네는 값 (KAN-30).
///
/// 웹 쪽 정본은 `web/src/bridge/bridge.ts`의 `SharePayload`다. 카드를 무엇으로 채울지는 서버가
/// 정하고(등급별 이미지·문구) 웹은 그대로 나른다 — 네이티브는 공유 SDK 호출과 폴백만 맡는다.
///
/// **점수·세션 id는 없다** (KAN-30 요구). 수신자는 남의 결과를 열어 보는 게 아니라 자기 테스트를
/// 새로 응시하므로, 카드에 필요한 건 등급 문구와 캠페인 URL뿐이다. 필드를 늘리기 전에 그 값이
/// "받은 사람이 볼 것"인지 먼저 확인할 것.
///
/// - `imageUrl`: 등급별 카드 이미지
/// - `text`: 카드 문구. 등급명은 있지만 점수는 없다
/// - `webTestUrl`: 캠페인 파라미터가 붙은 웹 테스트 URL — 카드 링크와 버튼이 같은 값을 쓴다
public struct SharePayload: Codable, Equatable, Sendable {
    public let imageUrl: String
    public let text: String
    public let webTestUrl: String

    public init(imageUrl: String, text: String, webTestUrl: String) {
        self.imageUrl = imageUrl
        self.text = text
        self.webTestUrl = webTestUrl
    }
}

/// 웹이 보낸 JSON을 ``SharePayload``로 좁힌다. 신뢰할 수 없으면 nil이다.
///
/// WebView 페이지는 신뢰 경계 밖이라는 ``parseVoiceItemStart(_:)``와 같은 전제인데, 여기 값들은
/// 한 걸음 더 나간다 — **앱 밖으로 나가는 값**이다. imageUrl·webTestUrl은 카카오 템플릿과
/// 공유 시트에 그대로 실려 다른 앱과 남의 대화방에 도착한다. 그래서 스킴을 https로 못박는다:
/// `javascript:`·`intent:`·`file:` 같은 스킴이 공유 링크로 나가면 우리 앱이 받는 사람 기기에서
/// 임의 동작을 여는 통로가 되고, `http://`는 카카오가 이미지로 받지 않는 데다 우리가 평문 링크를
/// 퍼뜨릴 이유도 없다. 스킴만으로는 모자라 host까지 본다 — 규칙은 ``isShareableHttpsUrl(_:)``에 있다.
///
/// 검증 실패는 조용히 nil이다 — 문항 payload와 같은 규칙이다. 웹은 오류를 돌려줄 상대가 아니고,
/// 엉뚱한 링크가 실린 카드를 내보내는 것보다 아무 일도 안 하는 편이 안전하다.
public func parseSharePayload(_ payloadJson: String) -> SharePayload? {
    guard let data = payloadJson.data(using: .utf8),
          let payload = try? JSONDecoder().decode(SharePayload.self, from: data)
    else { return nil }

    if payload.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
    // 안드로이드의 `String.length`는 UTF-16 단위라 상한 비교도 같은 단위로 센다.
    if payload.text.utf16.count > maxTextLength { return nil }
    if !isShareableHttpsUrl(payload.imageUrl) { return nil }
    if !isShareableHttpsUrl(payload.webTestUrl) { return nil }

    return payload
}

/// 공유 카드에 실어도 되는 URL인가. 스킴과 host까지만 본다 — 도메인 화이트리스트는 캠페인 URL이
/// 바뀔 때마다 깨진다.
///
/// 문자열 접두사(`hasPrefix("https://")`) 대신 파싱하는 이유: 접두사만 맞고 실제로는 주소가 아닌
/// 값이 통과했다. `https://`(host 없음), `https:///t`(authority가 빈 값)처럼 붙일 데가 없는 링크가
/// 카드에 실리고, 공백이 섞인 값은 카카오 템플릿과 공유 시트에서 어떻게 해석될지가 받는 쪽
/// 구현에 달린다. 파싱에 실패하면 그대로 거부다.
///
/// scheme은 **정확히 소문자 `https`만** 받는다. 대소문자를 섞어 받아 주려면 정규화한 값을 돌려줘야
/// 하는데, 여기 값들은 정규화 없이 카카오 템플릿과 공유 시트에 **받은 그대로** 실려 나간다.
/// 검사한 값과 내보내는 값이 다르면 검증이 의미를 잃으므로, 받은 그대로가 곧 유효한 값이어야 한다.
/// 그래서 `URLComponents.scheme`(구현에 따라 정규화될 수 있다)이 아니라 **원문 문자열의 스킴 자리**를
/// 직접 잘라 비교한다 — 안드로이드 `java.net.URI`가 준 값을 그대로 비교하는 것과 같은 판정이다.
func isShareableHttpsUrl(_ url: String) -> Bool {
    // 공백·제어문자가 섞인 값은 URL이 아니다. 안드로이드는 `java.net.URI`가 여기서
    // `URISyntaxException`을 던져 저절로 걸리지만, 스위프트의 `URLComponents`는 경로에 든 공백을
    // 관대하게 통과시킨다(`https://accentury.app/t 1`이 파싱된다). 검사한 값과 내보내는 값이
    // 같아야 한다는 원칙은 그대로라, 그 차이만큼 여기서 직접 막는다.
    //
    // 거르는 집합을 C0(U+0000–U+001F)에서 넓힌 이유: `java.net.URI`는 0x7F(DEL)와
    // C1 제어문자(U+0080–U+009F)에도 예외를 던진다. `CharacterSet.controlCharacters`가 그 셋을
    // 한 번에 덮고, 개행 계열(U+0085 등)까지 확실히 잡으려고 공백 집합을 합친다.
    if url.unicodeScalars.contains(where: rejectedUrlCharacters.contains) {
        return false
    }
    guard let colon = url.firstIndex(of: ":") else { return false }
    guard String(url[url.startIndex..<colon]) == "https" else { return false }
    guard let components = URLComponents(string: url) else { return false }
    guard let host = components.host, !host.isEmpty else { return false }
    return true
}
