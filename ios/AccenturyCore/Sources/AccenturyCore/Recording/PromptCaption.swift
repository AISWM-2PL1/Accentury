import Foundation

/// 대사 카드 위 캡션. 아트보드는 "3 / 10 · 이 문장을 읽어주세요"다 — 진행 도트 아래 캡션이
/// 이미 같은 숫자를 말하지만 두 줄이 화면 위아래로 떨어져 있어, 대사 바로 위에 한 번 더
/// 있는 편이 "지금 읽을 것은 이것"으로 읽힌다.
///
/// 번호를 모르는 경우(구버전 웹이 문항 수를 안 실어 보냈다)에는 안내만 남긴다. `0 / 0 ·`으로
/// 시작하는 줄은 숫자가 있는 것보다 나쁘다 — 사용자가 자기가 몇 번째인지 잘못 읽는다.
///
/// 안드로이드는 이 함수가 `RecordingScreen.kt`(Composable 파일) 안에 있지만 화면 요소가 아니라
/// 갈림 하나라서, 여기서는 화면 밖 순수 계층에 둔다 — `PromptCaptionTest`가 보는 것도 그 갈림이다.
public func promptCaption(questionIndex: Int, totalQuestions: Int) -> String {
    if questionIndex > 0 && totalQuestions > 0 {
        return "\(questionIndex) / \(totalQuestions) · 이 문장을 읽어주세요"
    }
    return "이 문장을 읽어주세요"
}
