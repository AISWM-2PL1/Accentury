import XCTest
@testable import AccenturyCore

/// 대사 카드 캡션 (KAN-161 4단계). 안드로이드 `recording/PromptCaptionTest.kt`의 이식본.
///
/// 아트보드는 "3 / 10 · 이 문장을 읽어주세요"인데, 문항 수를 안 실어 보내는 구버전 웹이 있어
/// (브리지 `VoiceItemStart`) 숫자가 0으로 들어올 수 있다.
///
/// `0 / 0 ·`으로 시작하는 줄은 숫자가 아예 없는 것보다 나쁘다 — 사용자가 자기가 몇 번째인지
/// 잘못 읽는다. 그 갈림을 화면 밖으로 빼서 여기서 고정한다.
final class PromptCaptionTests: XCTestCase {

    func test번호를_알면_진행과_안내를_한_줄로_붙인다() {
        XCTAssertEqual("3 / 10 · 이 문장을 읽어주세요", promptCaption(questionIndex: 3, totalQuestions: 10))
        XCTAssertEqual("1 / 5 · 이 문장을 읽어주세요", promptCaption(questionIndex: 1, totalQuestions: 5))
    }

    func test번호를_모르면_안내만_남긴다_0_슬래시_0을_보여주지_않는다() {
        XCTAssertEqual("이 문장을 읽어주세요", promptCaption(questionIndex: 0, totalQuestions: 0))
        XCTAssertEqual("이 문장을 읽어주세요", promptCaption(questionIndex: 3, totalQuestions: 0))
        XCTAssertEqual("이 문장을 읽어주세요", promptCaption(questionIndex: 0, totalQuestions: 10))
    }
}
