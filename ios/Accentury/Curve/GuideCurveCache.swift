import AccenturyCore
import SwiftUI

/// 가이드 곡선의 표시 좌표를 문항당 한 번만 계산한다 (KAN-108 §7b).
///
/// 안드로이드는 이 자리에 `remember(guideF0) { guideCurveDisplayPoints(...) }` 한 줄이 있다 —
/// 문항이 사는 동안 가이드는 정적이라 마운트당 한 번이면 되고, 사용자 곡선이 32ms마다 자라도
/// 위 레인의 좌표는 그대로다. SwiftUI에는 `remember`가 없어서, 뷰 본문을 다시 평가할 때마다
/// (곧 청크마다) 같은 배열로 같은 계산을 되풀이하게 된다.
///
/// 계산 자체는 가이드 값 개수(보통 100~200)의 O(n)이라 한 번은 싸다. 문제는 **빈도**다:
/// 녹음 10초면 300번 넘게 불리고, 그 결과는 매번 같은 배열이라 아래
/// ``CurveShapeCache``의 값 비교까지 통과해 버려 — 헛계산 위에 헛비교가 얹힌다.
///
/// 키는 ``AccenturyCore/GuideF0`` 자체다(`Equatable`). 문항이 바뀌면 payload가 바뀌므로
/// 키도 바뀌고, 같은 문항 안에서는 값이 같아 캐시가 계속 적중한다.
///
/// `@MainActor`·`nonisolated init()`인 근거는 ``CurveShapeCache``와 같다.
@MainActor
final class GuideCurveCache {

    nonisolated init() {}

    private var cachedKey: GuideF0??
    private var cachedPoints: [CurvePoint] = []

    /// 다시 계산한 횟수. 캐시가 죽어 있어도 화면은 똑같아서, 이 숫자 말고는 적중 여부를
    /// 밖에서 확인할 방법이 없다.
    private(set) var rebuildCount = 0

    /// 이 `guideF0`가 그릴 표시 좌표. 지난 호출과 같은 payload면 만들지 않고 그대로 돌려준다.
    ///
    /// unit 가드가 여기 있다: "0은 무성이 아니다" 규칙
    /// (``AccenturyCore/guideCurveDisplayPoints(_:)``)은 semitone에서만 참이다. 모르는 단위는
    /// 자기 스케일 덕에 그럴듯하게 그려지면서 무성 판정만 조용히 틀리므로, 안 그리는 쪽을 택한다.
    func points(for guideF0: GuideF0?) -> [CurvePoint] {
        if let cachedKey, cachedKey == guideF0 { return cachedPoints }

        rebuildCount += 1
        cachedKey = .some(guideF0)
        cachedPoints = {
            guard let guideF0, guideF0.unit == "semitone" else { return [] }
            return guideCurveDisplayPoints(guideF0.values)
        }()
        return cachedPoints
    }
}
