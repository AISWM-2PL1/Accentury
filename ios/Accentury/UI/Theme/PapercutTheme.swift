import SwiftUI
// 줄 높이를 재려고 부른다 (``Papercut/TextStyle/resolvedLineSpacing``). SwiftUI에는 서체의
// 자연 줄 높이를 묻는 API가 없어서 UIKit 쪽 `UIFont.lineHeight`를 빌린다.
import UIKit

/// Papercut 디자인 토큰 (KAN-148·KAN-161). 정본은 `docs/wiki/design-tokens.md`이고 이 파일은
/// 안드로이드 `ui/theme/{Color,Dimens,Type}.kt`에 대응하는 iOS 사본이다 — 값을 바꾸려면
/// 정본부터 고치고 같은 커밋에서 세 런타임(안드로이드·웹·iOS)을 함께 고친다. 한쪽만 고치면
/// 네이티브와 WebView가 번갈아 나오는 화면 경계에서 색과 간격이 튄다.
///
/// §4까지는 `PermissionGateView.swift` 안에 그 화면이 쓰는 값만 있었다. §6에서 화면이 넷
/// 늘면서 (녹음·목소리 점검·세션 게이트·업로드 상태 바) 사본이 파일마다 생길 자리라
/// 안드로이드처럼 테마 파일로 떼어냈다.
///
/// **팔레트는 넷뿐이다** — 잉크 `#1c1a17`, 크림 `#f3ecd9`, 종이 그림자 `#cfc5aa`,
/// 흐린 잉크 `#6b6459`. 반투명(알파) 값은 하나도 없다: 종이를 오려 붙인 그림에는
/// 비쳐 보이는 면이 없다. 상태를 색으로 알리지 않는다 (정본 §7) — 오류도 잉크 문구다.
///
/// ## 서체 (KAN-178)
/// 안드로이드와 같은 두 벌이다 — 제목·대사·등급·타이머는 Jua, 본문·캡션은 시스템 서체다
/// (정본 §3). KAN-108 §7·§8에서는 크기·행간·자간만 맞추고 글꼴을 시스템으로 뒀는데,
/// 레이아웃이 먼저 맞아야 서체를 끼울 때 화면이 안 흔들리기 때문이었다. 그 레이아웃이
/// 잠긴 지금 번들을 얹는다 — Jua는 어느 기기에도 없는 디스플레이 폰트라 번들이 필수다
/// (``Papercut/juaFamily``).
enum Papercut {

    // MARK: - 색 (정본 §2)

    /// 잉크. 텍스트·선·버튼 면·F0 곡선이 전부 이 한 색이다.
    static let ink = Color(red: 0x1c / 255, green: 0x1a / 255, blue: 0x17 / 255)
    /// 크림. 배경이자 카드 면이다 — 카드를 세우는 것은 색이 아니라 테두리와 그늘이다.
    static let cream = Color(red: 0xf3 / 255, green: 0xec / 255, blue: 0xd9 / 255)
    /// 오프셋 종이 그림자(`3 4 0`). 번지지 않는 단색 면이라 알파를 주지 않는다.
    ///
    /// 크림 위 1.46:1이라 **상태를 이 색으로 알리면 안 된다** — 그늘과 "없는 것"에만 쓴다.
    static let paperShadow = Color(red: 0xcf / 255, green: 0xc5 / 255, blue: 0xaa / 255)
    /// 흐린 잉크. 캡션·부연·레인 라벨이 쓴다.
    static let muted = Color(red: 0x6b / 255, green: 0x64 / 255, blue: 0x59 / 255)

    // MARK: - 간격 (정본 §4)

    static let space1: CGFloat = 4
    static let space2: CGFloat = 8
    static let space3: CGFloat = 12
    static let space4: CGFloat = 16
    static let space6: CGFloat = 24
    static let space8: CGFloat = 32

    // MARK: - 반경

    static let radiusSM: CGFloat = 12
    static let radiusMD: CGFloat = 16
    static let radiusLG: CGFloat = 18
    static let radiusXL: CGFloat = 24
    /// 완전한 원. 배지·캡슐·원형 버튼처럼 모서리를 끝까지 굴리는 자리.
    static let radiusFull: CGFloat = 9999

    // MARK: - 치수 (정본 §4·§5)

    /// 탭 가능한 요소의 최소 높이 (`ux-ui.md` §5).
    static let touchTargetMin: CGFloat = 48
    /// 화면의 주 컨트롤 높이 — 주 버튼이 쓴다. 보조는 ``touchTargetMin`` 그대로다.
    static let controlHeightLarge: CGFloat = 56
    /// 대사 카드 최소 높이. 문항 길이가 달라도 카드가 들썩이지 않게 고정한다.
    static let promptCardMinHeight: CGFloat = 152
    static let promptCardPadding: CGFloat = 22
    /// 곡선 레인 하나의 높이.
    static let curveLaneHeight: CGFloat = 120
    /// 원형 녹음 버튼 지름.
    static let recordButtonSize: CGFloat = 80
    /// 화면을 여는 원형 히어로 아이콘 지름.
    static let heroIconSize: CGFloat = 112
    /// 막대 두께. 입력 레벨 바가 쓴다.
    static let progressBarHeight: CGFloat = 8
    /// 진행 도트 하나의 두께. 막대와 같은 값이지만 뜻이 다르다 — 저쪽은 그릇, 이쪽은 칸 하나다.
    static let progressDotHeight: CGFloat = 8
    /// 화면 위쪽 여백. 좌우 24·아래 32와 달리 이 값만 8의 배수 밖에 서는 이유는 배치이기
    /// 때문이다 — 웹 `--screen-padding-top`과 같은 값이라 문항이 두 런타임을 오가도 첫 요소가
    /// 같은 높이에서 시작한다.
    static let screenPaddingTop: CGFloat = 64

    /// 오프셋 종이 그림자가 어긋난 거리 (정본 §5). 그리는 쪽과 눌려 내려가는 쪽이 같은 값을
    /// 읽어야 종이가 바닥에 정확히 닿는다.
    static let paperShadowX: CGFloat = 3
    static let paperShadowY: CGFloat = 4

    /// 비활성 불투명도. 회색으로 칠하는 대신 원래 색을 흐리게 해 대비가 함께 준다.
    static let opacityDisabled: Double = 0.6

    // MARK: - 선 굵기

    /// 주 CTA와 선택 상태만 2, 나머지는 1.5다 (시안 규칙).
    static let borderStrong: CGFloat = 2
    static let borderRegular: CGFloat = 1.5
    static let borderHairline: CGFloat = 1

    // MARK: - 모션 (정본 §5)

    enum Motion {
        static let press: Double = 0.075
        static let fast: Double = 0.15
        static let base: Double = 0.3
    }

    // MARK: - 타이포 (정본 §3)

    /// 번들한 Jua의 **PostScript 이름**이다 (KAN-178). 패밀리 이름 `Jua`가 아니라 이 이름을
    /// 쓰는 이유는 `Font.custom(_:size:)`이 패밀리가 아니라 폰트 하나를 가리키는 API라서다 —
    /// 굵기가 400 하나뿐인 폰트라 어차피 고를 것도 없다.
    ///
    /// 값의 출처는 폰트 파일의 name 테이블(nameID 6)이고, 파일 자체는 안드로이드가 쓰는
    /// `app/src/main/res/font/jua_regular.ttf`를 그대로 복사한 것이다
    /// (`Resources/Fonts/Jua-Regular.ttf`). 번들 등록은 `Info-{Debug,Release}.plist`의
    /// `UIAppFonts`가 한다 — 파일만 넣고 등록을 빠뜨리면 `Font.custom`이 조용히 시스템 서체로
    /// 떨어져서 화면은 뜨는데 글꼴만 다르다.
    static let juaFamily = "Jua-Regular"

    /// 주 CTA 라벨의 자간 (안드로이드 `AccenturyButton.PRIMARY_LETTER_SPACING`, 정본 §5 표).
    /// 굵기를 못 올리는 폰트라 자간이 라벨의 무게를 대신한다 — 라벨을 굵게 만들 수 없으니
    /// 글자 사이를 벌려 같은 일을 시킨다.
    static let primaryLabelTracking: CGFloat = 0.4

    /// 정본 §3의 스케일. 이름은 정본의 토큰 이름이고, 안드로이드가 M3 슬롯에 얹은 것과
    /// 같은 값이다 — 화면 코드는 크기를 직접 적지 않고 이 열거형만 쓴다.
    enum TextStyle {
        /// 40 — 결과 등급
        case display
        /// 26 — 대사 카드. `ux-ui.md` §5의 "대사 카드 24 이상"이 여기 걸린다.
        case headline
        /// 30 — 인트로·화면 제목(큰 쪽)
        case titleLarge
        /// 20 — 주 CTA 라벨·화면 제목
        case title
        /// 16 — 녹음 타이머·8초 경고 캡슐. 자간을 주는 슬롯은 여기 하나다
        case timer
        /// 16 — 본문
        case body
        /// 15 — 보조 버튼 라벨·선택지
        case bodySmall
        /// 14 — 부가 설명
        case label
        /// 13 — 카드 위 캡션·레인 라벨·진행 표기
        case caption

        var size: CGFloat {
            switch self {
            case .display: return 40
            case .headline: return 26
            case .titleLarge: return 30
            case .title: return 20
            case .timer: return 16
            case .body: return 16
            case .bodySmall: return 15
            case .label: return 14
            case .caption: return 13
            }
        }

        /// 정본 §3의 행간. 대사·등급이 1.15, 나머지가 1.5다 — 큰 글자에 1.5를 그대로 주면
        /// 줄 사이가 벌어져 한 덩어리로 안 읽힌다.
        var lineHeight: CGFloat {
            switch self {
            case .display: return 46
            case .headline: return 30
            case .titleLarge: return 35
            case .title: return 23
            case .timer: return 18
            case .body: return 24
            case .bodySmall: return 22
            case .label: return 21
            case .caption: return 20
            }
        }

        var weight: Font.Weight {
            switch self {
            case .label, .caption: return .medium
            default: return .regular
            }
        }

        /// Jua를 쓰는 슬롯인가 (KAN-178). 안드로이드 `Type.kt`가 `fontFamily = Jua`를 준
        /// 다섯 슬롯과 같다 — 제목·대사·등급·주 CTA 라벨·타이머다.
        ///
        /// 본문 쪽(``body``·``bodySmall``·``label``·``caption``)은 시스템 서체로 남는다.
        /// 안드로이드가 `FontFamily.Default`를 두는 것과 같은 이유인데, 그쪽 시스템 산세리프도
        /// WebView의 `sans-serif`도 Noto Sans CJK KR로 풀려서 저절로 수렴하기 때문이다.
        /// iOS에서는 그 자리가 Apple SD Gothic Neo라 웹뷰와 네이티브가 같은 서체로 만난다 —
        /// 어느 쪽이든 "본문은 그 OS의 본문 글꼴"이라는 규칙 하나로 설명된다.
        var usesJua: Bool {
            switch self {
            case .display, .headline, .titleLarge, .title, .timer: return true
            case .body, .bodySmall, .label, .caption: return false
            }
        }

        /// 이 슬롯이 실제로 그릴 폰트.
        ///
        /// Jua 슬롯에 굵기를 얹지 않는다 (안드로이드 KAN-161 2단계와 같은 판단). 번들한 Jua에는
        /// 굵기가 400 하나뿐이라 그 위를 요청하면 획을 부풀린 합성 볼드가 나오고, 곡선이 뭉개져
        /// 손으로 오린 글씨가 아니라 두껍게 인쇄한 글씨로 보인다. Jua 슬롯의 ``weight``가 전부
        /// `.regular`인 것도 같은 이유라 여기서 굵기를 버려도 잃는 것이 없다.
        var font: Font {
            usesJua
                ? .custom(Papercut.juaFamily, size: size)
                : .system(size: size, weight: weight)
        }

        /// 자리가 고정된 숫자라 자간을 벌리지 않으면 `00:04`의 두 자리가 서로 붙어 보인다.
        /// 웹 `.type-timer`와 같은 값(0.04em)이다.
        var tracking: CGFloat {
            self == .timer ? size * 0.04 : 0
        }
    }
}

extension Papercut.TextStyle {
    /// `lineSpacing`에 넣을 값 (KAN-178).
    ///
    /// `lineSpacing`은 **줄 사이에 더하는 값**이라 행간 자체가 아니다. 그래서 목표 행간에서
    /// 그 서체가 이미 쓰는 줄 높이를 빼야 정본 §3의 값이 나온다 — KAN-108에서는 `size`를
    /// 빼서 근사했는데(서체를 몰라 줄 높이를 잴 수 없었다), 그 값은 언제나 실제보다 넓었다.
    ///
    /// 음수는 0으로 잘린다. SwiftUI에는 줄을 서체의 자연 줄 높이보다 좁히는 길이 없고, Jua는
    /// 한글 폰트라 26에서 이미 30을 넘는다 — 정본에 닿지 못하는 그 몇 포인트가 안드로이드와
    /// 남는 유일한 차이이고, 방향은 "조금 넓게"라 대사가 붙어 읽히지는 않는다.
    ///
    /// 폰트를 못 찾으면(번들 등록 누락) 옛 근사로 떨어진다. 이 자리에서 죽지 않게 하는 이유는
    /// 글꼴이 틀린 화면은 쓸 수 있지만 뜨지 않는 화면은 못 쓰기 때문이다.
    var resolvedLineSpacing: CGFloat {
        let uiFont: UIFont? = usesJua
            ? UIFont(name: Papercut.juaFamily, size: size)
            : UIFont.systemFont(ofSize: size, weight: weight == .medium ? .medium : .regular)
        guard let natural = uiFont?.lineHeight else { return max(lineHeight - size, 0) }
        return max(lineHeight - natural, 0)
    }
}

extension View {
    /// 정본 §3의 한 슬롯을 통째로 적용한다.
    ///
    /// - Parameter tracking: 슬롯 기본 자간(``Papercut/TextStyle/tracking``)을 덮을 값.
    ///   주 CTA 라벨 하나가 쓴다 — 안드로이드 `AccenturyButton`의 `PRIMARY_LETTER_SPACING`
    ///   자리이고, 굵기를 못 올리는 Jua에서 자간이 라벨의 무게를 대신한다.
    func papercutType(_ style: Papercut.TextStyle, tracking: CGFloat? = nil) -> some View {
        self
            .font(style.font)
            .tracking(tracking ?? style.tracking)
            .lineSpacing(style.resolvedLineSpacing)
    }
}
