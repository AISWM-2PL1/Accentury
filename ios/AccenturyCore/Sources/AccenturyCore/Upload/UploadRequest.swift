import Foundation

/// 업로드 한 건에 실어 보낼 것 전부. 안드로이드 `upload/UploadRequest.kt`의 이식본이다.
///
/// 코틀린 쪽에는 `equals`/`hashCode`를 손으로 적은 블록이 있다 — `ByteArray`의 기본 `equals`가
/// 참조 비교라 `data class` 자동 구현을 쓸 수 없어서다. Swift의 `Data`는 값 타입이고 `==`가
/// 내용 비교라 그 블록이 통째로 필요 없다. 같은 이유로 `UploadManager`가 뜨던
/// `wavBytes.copyOf()` 스냅샷도 대입 자체가 값 복사라 문법에 이미 들어 있다.
public struct UploadRequest: Equatable, Sendable {

    /// 멱등 키. 재시도해도 같은 값을 쓴다 (§2.2).
    public let attemptId: String
    /// 경로에 실리는 문항 ID (§3.3).
    public let itemId: String
    /// 16kHz 모노 16bit WAV 한 벌. `WavWriter.toWavBytes`가 만든 것이다.
    public let wavBytes: Data
    public let durationMs: Int64
    public let clientQuality: ClientQuality

    public init(
        attemptId: String,
        itemId: String,
        wavBytes: Data,
        durationMs: Int64,
        clientQuality: ClientQuality
    ) {
        self.attemptId = attemptId
        self.itemId = itemId
        self.wavBytes = wavBytes
        self.durationMs = durationMs
        self.clientQuality = clientQuality
    }
}
