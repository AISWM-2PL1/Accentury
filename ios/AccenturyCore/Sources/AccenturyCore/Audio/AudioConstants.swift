import Foundation

/// 안드로이드 `app/src/main/java/com/accentury/app/audio/AudioRecorder.kt`의 상수·순수 함수 이식본.
///
/// 정본 파일은 `AudioRecord` 캡처까지 한 파일에 들고 있지만, 캡처는 플랫폼 API(iOS는 AVAudioEngine)라
/// 이쪽 순수 Swift 계층에 올 수 없다. 그래서 캡처와 무관한 상수·RMS만 떼어 이 파일에 모았다 —
/// iOS 캡처 계층은 별도 단계에서 앱 타깃에 붙고, 그쪽이 여기 상수를 참조한다.

public let sampleRate = 16_000

/// YIN 분석 창 길이 (16kHz에서 128ms). 캡처 단위가 아니다 - `OverlappedFramer`의 windowSize이고,
/// 남성 저음(~70Hz)의 주기 두 개가 들어가야 YIN이 최저 F0를 찾을 수 있어 이 길이가 필요하다.
public let chunkSize = 2048

/// 마이크에서 한 번에 읽어 흘리는 샘플 수 (16kHz에서 32ms). `OverlappedFramer`의 기본 hop과 같다.
///
/// 분석은 KAN-104부터 이미 hop 단위(32ms)로 돌지만 방출은 `chunkSize`마다여서, 화면 곡선이
/// 128ms에 한 번 4점씩 계단으로 자랐다. 읽기 단위를 hop과 맞추면 청크마다 창이 정확히 하나
/// 완성돼 갱신 주기가 곧 32ms가 된다 (AC2 - 중급 단말에서 끊겨 보이지 않는다).
/// 상태 갱신 31회/s는 UI에 부담이 없다 - 곡선 재계산은 최대 313프레임짜리 O(n)이다.
public let readChunkSize = 512

/// 청크의 RMS(제곱평균제곱근). 정규화 전 원 스케일(0..32768) 기준이다.
///
/// 안드로이드와 마찬가지로 누적은 `Double`이다 — YIN 내부가 `Float`인 것과 달리 여기는
/// 청크 전체를 더하므로 누적 오차가 쌓인다. 빈 배열이면 0/0이라 NaN이 나오는 것도 같다
/// (호출부가 빈 청크를 넘기지 않는다는 전제가 양쪽 동일).
public func calculateRms(_ chunk: [Int16]) -> Double {
    var sum = 0.0
    for sample in chunk { sum += Double(sample) * Double(sample) }
    return sqrt(sum / Double(chunk.count))
}
