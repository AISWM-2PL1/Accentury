import Foundation

/// 공유 유입 계측 코드(`c`)가 서버 계약에 맞는지 보는 검사식 (KAN-31·KAN-32).
/// 웹 `web/src/session/campaign.ts`의 `CAMPAIGN_TOKEN_PATTERN`, 백엔드
/// `CreateSessionRequest.campaignToken`의 `@Pattern`과 같은 규칙이다 — 셋 중 하나만 느슨하면
/// 앱이 통과시킨 값을 서버가 400으로 돌려준다.
///
/// 웹·백엔드가 쓰는 `^…$`와 뜻은 같되 끝을 `\z`로 적는다. ICU의 `$`는 마지막 개행 하나를
/// 눈감아 주는데, 안드로이드의 `Regex.matches()`는 입력 전체가 맞아떨어질 때만 참이라
/// `"kko_share\n"`에서 두 플랫폼의 판정이 갈린다 — 그 한 글자를 여기서 막는다.
let campaignTokenPattern = #"\A[A-Za-z0-9._-]{1,64}\z"#

/// App Link(Universal Link)로 들어온 진입 (KAN-32). 링크에서 앱이 읽어 가는 것은 계측 코드
/// 하나뿐이라 필드도 하나다 — 이 자료형이 곧 "링크로 넘어올 수 있는 것의 전부"라는 선언이다.
public struct AppLinkEntry: Equatable, Sendable {
    /// `?c=` 값. 없거나 서버 계약에 어긋나면 nil이고, 그래도 진입 자체는 성립한다.
    public let campaignToken: String?

    public init(campaignToken: String?) {
        self.campaignToken = campaignToken
    }
}

/// 공유 링크를 앱 진입으로 해석한다 (KAN-32 1단계).
/// `app/src/main/java/com/accentury/app/web/AppLink.kt`의 1:1 이식본이다 — 같은 링크를 두
/// 플랫폼이 다르게 읽으면 유입 계측이 OS별로 갈린다.
///
/// `https://accentury.app/t?c=kko_share` 꼴만 받아들이고, 그 밖의 URL은 nil — 호출자는 nil을
/// "이 링크는 우리 진입점이 아니다"로 읽으면 된다.
///
/// nil을 돌려주는 경우: url이 없거나 파싱이 안 될 때, ``webOrigin(_:)``이 없거나 `allowedOrigins`
/// 밖일 때(§7의 보안 경계를 App Link 입구에도 그대로 적용한다), 경로가 `/t`·`/t/`가 아닐 때.
/// 경로를 정확히 맞추는 이유는 `/t/무엇이든`·`/privacy` 같은 링크가 테스트 진입으로 둔갑하지
/// 않게 하기 위해서다.
///
/// **`c` 말고는 어떤 쿼리도 읽지 않는다.** `sessionId`·`screen`·`testVersion`·`bridge`·`app`이
/// 붙어 와도 전부 버린다 — AC "링크가 개인 결과 또는 세션 토큰을 포함하지 않는다"를 지키는 자리가
/// 여기다. 링크는 누구나 손으로 지어낼 수 있으므로, 읽지 않는 것이 곧 남의 세션을 주입당하거나
/// 결과 화면으로 건너뛰는 링크가 성립하지 않는다는 보증이다. 진입 URL은 앱이 ``buildWebUrl(base:appVersionName:testEntry:bridgeVersion:campaignToken:)``으로
/// 직접 조립하고, 링크는 계측 코드 한 개만 거기에 실어 보낸다.
///
/// 코드가 계약에 어긋나면 진입을 막는 대신 코드만 버린다 — campaign.ts `sanitizeCampaignToken`과
/// 같은 판단이다. 공유 링크는 메신저를 여러 번 거치며 잘리거나 트래킹 파라미터가 덧붙는 경로라
/// 코드가 망가진 채 도착하는 일이 실제로 생기는데, 계측은 실패해도 되는 일이고 응시는 아니다.
public func parseAppLink(_ url: String?, allowedOrigins: Set<String>) -> AppLinkEntry? {
    guard let url, let origin = webOrigin(url), allowedOrigins.contains(origin) else { return nil }
    guard let components = URLComponents(string: url) else { return nil }
    // `components.path`는 이미 퍼센트 인코딩이 풀린 값이다 — `/%74`처럼 escape로 위장한 경로가
    // `/t`로 통과하지 않게 디코딩된 쪽으로 비교한다 (안드로이드의 `URI.getPath()`와 같은 자리).
    let path = components.path
    guard path == "/t" || path == "/t/" else { return nil }
    // 같은 이름이 여러 번 오면 앞엣것 하나만 읽는다 — `?c=a&c=b`처럼 값을 덧붙여 판정을 흔드는
    // 링크에서 앱이 읽는 값이 하나로 정해져 있어야 한다.
    // `queryItems`의 값은 `%XX`만 풀리고 `+`는 글자 그대로 남는다. 안드로이드도 form-urlencoded
    // 규칙(`+` → 공백)을 쓰지 않고 같은 해석을 하도록 맞춰 뒀다.
    let raw = components.queryItems?.first { $0.name == "c" }?.value
    return AppLinkEntry(campaignToken: raw.flatMap(validCampaignToken))
}

/// 서버 계약에 맞는 값만 돌려주고, 어긋나면 nil.
private func validCampaignToken(_ value: String) -> String? {
    value.range(of: campaignTokenPattern, options: .regularExpression) != nil ? value : nil
}
