import Foundation

public enum QualityStatus: Sendable {
    case normal
    case tooShort
    case tooQuiet
    case clipped
}

/// 업로드 meta 파트에 실리는 클라이언트 측 품질 지표(API 명세서 §3.3).
/// rms·peak·silenceRatio는 모두 0..1 정규화 실수다.
///
/// 안드로이드는 `@Serializable data class`다. 이쪽은 `Codable`이고, 필드 이름을 그대로 두어
/// 기본 키 전략이 만드는 JSON 키가 안드로이드가 보내는 것과 같다 — 서버 한쪽이 두 앱을 받는다.
public struct ClientQuality: Codable, Equatable, Sendable {
    public let rms: Double
    public let peak: Double
    public let silenceRatio: Double
    public let clipped: Bool

    public init(rms: Double, peak: Double, silenceRatio: Double, clipped: Bool) {
        self.rms = rms
        self.peak = peak
        self.silenceRatio = silenceRatio
        self.clipped = clipped
    }
}

/// 안드로이드 `audio/AudioQuality.kt`의 1:1 이식본. 임계값이 갈리면 같은 녹음을 한쪽은 반려하고
/// 다른 쪽은 올려 보내므로, 값과 판정 순서를 그대로 옮긴다.
public enum AudioQuality {

    public static let minDurationMs: Int64 = 1_000
    public static let quietRmsThreshold = 100.0
    public static let clipSampleThreshold = 32_000
    public static let clipRatioThreshold = 0.01

    /// 16-bit PCM 전체 스케일. 정규화(0..1) 분모로 쓴다.
    public static let fullScale = 32_768.0

    /// 무음으로 볼 진폭 상한. 전체 스케일의 1%(= -40 dBFS)로,
    /// 조용한 실내 잡음은 걸러내면서 실제 발화는 남기는 수준이다.
    public static let silenceSampleThreshold = 328

    public static func judge(_ pcm: [Int16], durationMs: Int64) -> QualityStatus {
        if durationMs < minDurationMs { return .tooShort }
        if pcm.isEmpty { return .tooShort }

        let quality = measure(pcm)
        if quality.clipped { return .clipped }
        // quietRmsThreshold는 정규화 전 원 스케일 기준이라 되돌려서 비교한다.
        if quality.rms * fullScale < quietRmsThreshold { return .tooQuiet }

        return .normal
    }

    /// 서버로 보낼 품질 지표를 한 번의 순회로 계산한다. 빈 배열은 전부 0으로 본다.
    public static func measure(_ pcm: [Int16]) -> ClientQuality {
        if pcm.isEmpty { return ClientQuality(rms: 0.0, peak: 0.0, silenceRatio: 0.0, clipped: false) }

        var peak = 0
        var silentCount = 0
        var clippedCount = 0
        for sample in pcm {
            // Int16.min의 절댓값(32768)까지 담으려면 Int로 올려서 비교한다.
            let magnitude = abs(Int(sample))
            if magnitude > peak { peak = magnitude }
            if magnitude < silenceSampleThreshold { silentCount += 1 }
            if magnitude >= clipSampleThreshold { clippedCount += 1 }
        }

        return ClientQuality(
            rms: calculateRms(pcm) / fullScale,
            peak: Double(peak) / fullScale,
            silenceRatio: Double(silentCount) / Double(pcm.count),
            clipped: Double(clippedCount) / Double(pcm.count) > clipRatioThreshold
        )
    }
}
