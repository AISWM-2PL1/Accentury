import Foundation

/// 카드 버튼 문구. 수신자가 누르면 **자기 테스트가 열린다** — 남의 결과를 보는 링크가 아니다.
/// 안드로이드 `ResultSharer.kt`의 `SHARE_BUTTON_TITLE`과 같은 값이어야 한다 (KAN-30, KAN-180).
public let shareButtonTitle = "나도 테스트하기"

/// 결과가 나가는 통로 (KAN-180). 안드로이드 `ResultSharer.kt`의 `ShareChannel`과 같다.
///
/// 티켓이 요구한 폴백은 [systemSheet]다 — 카카오 문서가 권하는 웹 공유(브라우저로 카카오 공유
/// 페이지를 여는 방식)를 쓰지 않는 이유는 안드로이드와 같다. 카톡이 없는 사용자에게 카톡 웹
/// 공유를 들이미는 건 답이 아니고, 그 사람이 실제로 쓰는 메신저로 보내야 한다.
public enum ShareChannel: Equatable, Sendable {
    case kakao
    case systemSheet
}

/// 어느 통로로 갈지. 두 조건이 모두 참일 때만 카카오다 — 순수 함수라 조합을 테스트로 못박는다.
///
/// - Parameters:
///   - kakaoEnabled: 앱 키가 주입돼 SDK가 초기화됐는가 (`AppConfig.kakaoNativeAppKey`)
///   - talkAvailable: 카톡이 깔려 있고 공유를 받을 수 있는가 (`ShareApi.isKakaoTalkSharingAvailable()`)
public func chooseShareChannel(kakaoEnabled: Bool, talkAvailable: Bool) -> ShareChannel {
    kakaoEnabled && talkAvailable ? .kakao : .systemSheet
}

/// 카카오 피드 카드에 실릴 값 (KAN-180).
///
/// **카카오 SDK 타입이 아니다.** 이 계층(AccenturyCore)은 시뮬레이터 없이 `swift test`로 도는
/// 순수 Swift라 SDK를 알지 못한다. 앱 타깃의 `ResultSharer`가 이 구조체를 `FeedTemplate`으로
/// 한 줄씩 옮긴다 — 그 매핑은 타입이 붙들지만, **무엇을 실을지**의 판단은 여기 있어서
/// 시뮬레이터 없이 검증된다.
///
/// 안드로이드 `buildFeedTemplate`이 만드는 것과 같은 카드여야 한다 (티켓 요구: 같은 피드
/// 템플릿·같은 파라미터). 그쪽에서 비워 둔 것도 여기 없다 — `description`(피드 카드 부제)은
/// 넣을 값이 점수뿐이라 두지 않고, `imageWidth`/`imageHeight`는 카드 이미지 규격이 확정 전이라
/// 지금 숫자를 박으면 실제 자산과 어긋난 비율로 잘려 나온다(값이 없으면 카카오가 원본 비율로 그린다).
public struct ShareCard: Equatable, Sendable {

    /// 카드 제목. 등급 문구이고 점수는 없다 (``SharePayload`` 주석 참고).
    public let title: String

    /// 등급별 카드 이미지 (KAN-132가 S3에 올린 URL).
    public let imageUrl: URL

    /// 카드 본문과 버튼이 **함께** 쓰는 도착지. 캠페인 파라미터가 붙은 웹 테스트 URL이다.
    public let linkUrl: URL

    /// 버튼 문구. 값은 늘 ``shareButtonTitle``이고 필드로 둔 이유는 카드가 자기 자신만으로
    /// 온전해서다 — 앱 타깃이 상수를 따로 알아야 할 이유가 없다.
    public let buttonTitle: String

    public init(title: String, imageUrl: URL, linkUrl: URL, buttonTitle: String) {
        self.title = title
        self.imageUrl = imageUrl
        self.linkUrl = linkUrl
        self.buttonTitle = buttonTitle
    }
}

/// 웹이 보낸 payload를 카드로 옮긴다 (KAN-180). 실패하면 nil이고, 부르는 쪽은 시트로 간다.
///
/// **nil이 나올 수 있는 이유가 검증 실패는 아니다.** 스킴·host 판정은 ``parseSharePayload(_:)``가
/// 이미 끝냈으므로 여기 오는 문자열은 https 주소다. 남은 건 `URL(string:)`이 그 문자열을
/// 실제로 받아 주느냐 하나인데, 두 함수가 서로 다른 파서라(``isShareableHttpsUrl(_:)``는
/// `URLComponents`) 완전히 같다고 단정할 수 없다. 단정 대신 nil을 돌려주는 쪽을 골랐다 —
/// 강제 언래핑으로 두면 그 틈이 결과 화면에서 앱이 죽는 크래시가 된다.
///
/// 링크와 버튼이 같은 URL을 쓴다: 카드 어디를 눌러도 도착지가 같아야 한다. 본문 탭과 버튼 탭이
/// 다른 곳으로 가면 캠페인 유입 집계도 갈린다 (안드로이드와 같은 규약).
public func buildShareCard(_ payload: SharePayload) -> ShareCard? {
    guard let imageUrl = URL(string: payload.imageUrl),
          let linkUrl = URL(string: payload.webTestUrl)
    else { return nil }

    return ShareCard(
        title: payload.text,
        imageUrl: imageUrl,
        linkUrl: linkUrl,
        buttonTitle: shareButtonTitle
    )
}
