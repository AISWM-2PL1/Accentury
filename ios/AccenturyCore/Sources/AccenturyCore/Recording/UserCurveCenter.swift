import Foundation

/// 사용자 억양 곡선의 **세로축 중심**을 정하는 부분만 먼저 떼어 온 것.
/// 안드로이드 `recording/UserCurve.kt`에 있는 함수 둘과 상수 하나다.
///
/// 곡선 좌표 계산(`userCurveDisplayPoints`·`fillShortGaps`·창 길이) 전체는 §7 몫이다.
/// 목소리 점검(``VoiceCheckController``)이 그보다 먼저 중심 Hz와 유성 판정을 필요로 해서,
/// 그 둘만 이 파일에 앞세웠다 — **§7에서 `UserCurve.swift`를 이식할 때 이 심볼들을 다시
/// 정의하지 말고 그대로 쓸 것.** 정의가 둘로 갈리면 곡선이 그려지는 조건과 점검이 통과하는
/// 조건이 조용히 어긋난다.

/// 중심(화자 기준 음높이)을 정하는 데 필요한 유성 프레임 수. 32ms 간격 기준 약 250ms.
///
/// 이보다 적으면 첫 한두 음절의 음높이가 곧 화자의 중심이 되어, 우연히 높게 시작한 발화가
/// 통째로 레인 아래쪽에 눌린다. 반대로 너무 크게 잡으면 축이 정해지기까지 곡선이 안 나온다.
public let centerMinVoicedFrames = 8

public extension RecordingEngine.PitchFrame {
    /// 이 프레임의 유효한 유성 F0. 무성이거나 값이 성립하지 않으면 nil이다.
    ///
    /// 목소리 점검이 유성 프레임을 세는데, "무엇이 유성인가"의 정의가 둘로 갈리면
    /// 곡선이 그려지는 조건과 점검이 통과하는 조건이 조용히 어긋난다.
    var voicedHz: Float? {
        guard let hz = pitchHz, hz.isFinite, hz > 0 else { return nil }
        return hz
    }
}

/// 이 화자의 중심 음높이(Hz). 유성 프레임이 ``centerMinVoicedFrames``개에 못 미치면 nil이다.
///
/// **처음** N개만 본다. 프레임이 더 쌓여도 값이 안 변하므로(잠금) 축이 한 번 정해지면 끝까지
/// 같고, 이미 그린 곡선이 나중 발화 때문에 위아래로 밀리는 일이 없다.
///
/// 평균이 아니라 중앙값인 이유: YIN은 이따금 옥타브 오류(진짜 값의 2배나 절반)를 낸다.
/// 8개 중 하나만 2배로 튀어도 평균은 12% 넘게 밀리지만(≈2 semitone) 중앙값은 꿈쩍하지 않는다.
public func userCurveCenterHz(_ frames: [RecordingEngine.PitchFrame]) -> Float? {
    var first: [Float] = []
    first.reserveCapacity(centerMinVoicedFrames)
    for frame in frames {
        guard let hz = frame.voicedHz else { continue }
        first.append(hz)
        if first.count == centerMinVoicedFrames { break }
    }
    if first.count < centerMinVoicedFrames { return nil }
    first.sort()
    let mid = first.count / 2
    // 짝수개면 가운데 둘의 평균이 통상의 중앙값이다. 옥타브 오류는 정렬하면 끝으로 밀려나므로
    // 가운데 둘은 여전히 정상 값이다.
    return first.count % 2 == 0 ? (first[mid - 1] + first[mid]) / 2 : first[mid]
}
