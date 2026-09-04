import Foundation

/// 마이크 자리에 끼울 수 있는 PCM 공급자. 안드로이드 `audio/AudioRecorder.kt`의
/// `interface PcmSource { fun recordingFlow(): Flow<ShortArray> }` 이식본이다.
///
/// `Flow<ShortArray>` → `AsyncThrowingStream<[Int16], Error>`로 옮긴 이유:
/// 두 타입 다 **콜드**(수집을 시작해야 캡처가 시작된다)이고, **소비 측이 멈추면 상류가 정리된다**
/// (Kotlin은 코루틴 취소, Swift는 이터레이터 해제 → `onTermination`). 엔진이 10초에서 끊거나
/// 사용자가 정지를 눌렀을 때 마이크가 반드시 반납되어야 하는데, 그 보장이 두 API에 다 있다.
/// 값 타입 `[Int16]`을 흘리므로 안드로이드가 `buffer.copyOf(read)`로 막았던
/// "같은 버퍼를 재사용해 소리가 뒤섞이는" 사고는 구조적으로 일어나지 않는다.
public protocol PcmSource {
    func recordingStream() -> AsyncThrowingStream<[Int16], Error>
}

/// 캡처 자체가 실패했다는 신호. 안드로이드 `AudioRecorder.CaptureException`에 해당한다.
///
/// `RecordingEngine`이 이 타입만 사용자에게 보여줄 문구로 그대로 옮긴다 — 그래서 message는
/// 로그 문자열이 아니라 화면에 나가도 되는 한국어여야 한다(안드로이드 쪽 문구와 짝을 맞춘다).
public struct CaptureError: Error, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}
