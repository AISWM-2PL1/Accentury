import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/audio/AudioQualityTest.kt`의 1:1 이식본.
/// 케이스 순서와 합성 입력(교대 부호 구형파)이 안드로이드와 같아야 임계값 판정이 같음을 보증할 수 있다.
final class AudioQualityTests: XCTestCase {

    private func pcmOfSeconds(_ seconds: Double, amplitude: Int) -> [Int16] {
        let count = Int(Double(sampleRate) * seconds)
        return (0..<count).map { Int16($0 % 2 == 0 ? amplitude : -amplitude) }
    }

    /// 1초 미만 발화는 TOO_SHORT다
    func testUtteranceShorterThanOneSecondIsTooShort() {
        let pcm = pcmOfSeconds(0.5, amplitude: 1000)
        XCTAssertEqual(QualityStatus.tooShort, AudioQuality.judge(pcm, durationMs: 500))
    }

    /// 무음에 가까운 녹음은 TOO_QUIET다
    func testNearSilentRecordingIsTooQuiet() {
        let pcm = pcmOfSeconds(2.0, amplitude: 10)
        XCTAssertEqual(QualityStatus.tooQuiet, AudioQuality.judge(pcm, durationMs: 2000))
    }

    /// 클리핑 비율이 임계를 넘으면 CLIPPED다
    func testClipRatioAboveThresholdIsClipped() {
        var pcm = pcmOfSeconds(2.0, amplitude: 1000)
        let clipCount = Int(Double(pcm.count) * 0.02)
        for i in 0..<clipCount { pcm[i] = Int16.max }
        XCTAssertEqual(QualityStatus.clipped, AudioQuality.judge(pcm, durationMs: 2000))
    }

    /// 정상 발화는 NORMAL이다
    func testNormalUtteranceIsNormal() {
        let pcm = pcmOfSeconds(2.0, amplitude: 1000)
        XCTAssertEqual(QualityStatus.normal, AudioQuality.judge(pcm, durationMs: 2000))
    }

    /// measure는 진폭을 0에서 1 사이로 정규화한다
    func testMeasureNormalizesAmplitudeBetweenZeroAndOne() {
        let half = Int(AudioQuality.fullScale / 2)
        let quality = AudioQuality.measure(pcmOfSeconds(1.0, amplitude: half))

        XCTAssertEqual(0.5, quality.rms, accuracy: 1e-6)
        XCTAssertEqual(0.5, quality.peak, accuracy: 1e-6)
        XCTAssertEqual(0.0, quality.silenceRatio, accuracy: 1e-9)
        XCTAssertFalse(quality.clipped)
    }

    /// 무음 배열은 silenceRatio가 1이고 나머지는 0이다
    func testSilentArrayHasSilenceRatioOneAndZeroElsewhere() {
        let quality = AudioQuality.measure([Int16](repeating: 0, count: sampleRate))

        XCTAssertEqual(0.0, quality.rms, accuracy: 1e-9)
        XCTAssertEqual(0.0, quality.peak, accuracy: 1e-9)
        XCTAssertEqual(1.0, quality.silenceRatio, accuracy: 1e-9)
        XCTAssertFalse(quality.clipped)
    }

    /// 클리핑이 임계를 넘으면 measure의 clipped가 true다
    func testMeasureReportsClippedWhenClipRatioExceedsThreshold() {
        var pcm = pcmOfSeconds(2.0, amplitude: 1000)
        let clipCount = Int(Double(pcm.count) * 0.02)
        for i in 0..<clipCount { pcm[i] = Int16.max }

        let quality = AudioQuality.measure(pcm)

        XCTAssertTrue(quality.clipped)
        XCTAssertTrue(quality.peak > 0.99)
    }

    /// 빈 배열은 0으로 나누지 않고 전부 0을 반환한다
    func testEmptyArrayReturnsAllZerosWithoutDividingByZero() {
        let quality = AudioQuality.measure([])

        XCTAssertEqual(
            ClientQuality(rms: 0.0, peak: 0.0, silenceRatio: 0.0, clipped: false),
            quality
        )
    }
}
