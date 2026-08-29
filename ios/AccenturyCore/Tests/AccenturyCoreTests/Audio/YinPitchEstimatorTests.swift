import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/audio/YinPitchEstimatorTest.kt`의 1:1 이식본.
/// 합성 입력·허용 오차·케이스 순서를 그대로 옮겼다.
final class YinPitchEstimatorTests: XCTestCase {

    private func sine(_ freqHz: Double, amplitude: Double = 8000.0, size: Int = chunkSize) -> [Int16] {
        (0..<size).map {
            Int16(truncatingIfNeeded: Int(amplitude * sin(2 * Double.pi * freqHz * Double($0) / Double(sampleRate))))
        }
    }

    /// 220Hz 사인파의 F0를 추정한다
    func testEstimatesF0OfA220HzSine() throws {
        let f0 = YinPitchEstimator.estimate(sine(220.0))
        XCTAssertNotNil(f0)
        XCTAssertEqual(220, try XCTUnwrap(f0), accuracy: 3)
    }

    /// 저음 경계 근처 100Hz를 추정한다
    func testEstimates100HzNearTheLowBandEdge() throws {
        let f0 = YinPitchEstimator.estimate(sine(100.0))
        XCTAssertNotNil(f0)
        XCTAssertEqual(100, try XCTUnwrap(f0), accuracy: 3)
    }

    /// 고음 350Hz를 추정한다
    func testEstimatesAHigh350Hz() throws {
        let f0 = YinPitchEstimator.estimate(sine(350.0))
        XCTAssertNotNil(f0)
        XCTAssertEqual(350, try XCTUnwrap(f0), accuracy: 4)
    }

    /// 배음이 섞여도 기본 주파수를 잡는다 - 옥타브 오류 없음
    func testFindsTheFundamentalEvenWithHarmonicsNoOctaveError() throws {
        // 실제 목소리처럼 2, 3배음 포함. 단순 autocorrelation이 배음(240Hz)으로 튀던 케이스.
        let f0Hz = 120.0
        let chunk = (0..<chunkSize).map { i -> Int16 in
            let t = 2 * Double.pi * f0Hz * Double(i) / Double(sampleRate)
            return Int16(truncatingIfNeeded: Int(5000 * sin(t) + 3000 * sin(2 * t) + 2000 * sin(3 * t)))
        }
        let f0 = YinPitchEstimator.estimate(chunk)
        XCTAssertNotNil(f0)
        XCTAssertEqual(120, try XCTUnwrap(f0), accuracy: 3)
    }

    /// 대역 상한 경계 396Hz도 400Hz를 넘기지 않는다
    func testBandEdge396HzStaysAtOrBelow400Hz() throws {
        let f0 = YinPitchEstimator.estimate(sine(396.0))
        XCTAssertNotNil(f0)
        let value = try XCTUnwrap(f0)
        XCTAssertEqual(396, value, accuracy: 4)
        XCTAssertTrue(value <= 400)
    }

    /// 대역 밖 410Hz는 400Hz 초과 값을 반환하지 않는다
    func testOutOfBand410HzNeverReturnsAValueAbove400Hz() {
        // τmin=40 경계에서 보간이 대역 밖으로 새는지 확인. nil(무성음) 또는 clamp된 값만 허용.
        let f0 = YinPitchEstimator.estimate(sine(410.0))
        XCTAssertTrue(f0 == nil || f0! <= 400)
    }

    /// 무음은 무성음으로 판정한다
    func testSilenceIsJudgedUnvoiced() {
        XCTAssertNil(YinPitchEstimator.estimate([Int16](repeating: 0, count: chunkSize)))
    }

    /// 백색잡음은 무성음으로 판정한다
    func testWhiteNoiseIsJudgedUnvoiced() {
        // 시드 고정 - 무성음 판정 결과가 실행마다 흔들리지 않게.
        // 안드로이드가 `java.util.Random(42)`를 쓰므로 같은 수열을 내는 LCG를 이 타깃에 다시 구현했다
        // (JavaRandom.swift). 잡음이 두 플랫폼에서 같은 표본이라야 이 케이스가 같은 것을 검사한다.
        var random = JavaRandom(seed: 42)
        let noise = (0..<chunkSize).map { _ in Int16(truncatingIfNeeded: random.nextInt(16000) - 8000) }
        // 잡음 RMS(약 4600)는 에너지 게이트를 한참 넘는다. 게이트가 아니라
        // CMNDF가 무성음으로 판정해야 이 테스트에 의미가 있다.
        XCTAssertTrue(calculateRms(noise) > Double(YinPitchEstimator.voicedMinRms))
        XCTAssertNil(YinPitchEstimator.estimate(noise))
    }

    /// 탐색 대역 밖 저주파는 무성음으로 판정한다
    func testSubBandLowFrequencyIsJudgedUnvoiced() {
        // 50Hz(주기 320샘플)는 τmax=200 안에서 겹치는 지점이 없다.
        XCTAssertNil(YinPitchEstimator.estimate(sine(50.0)))
    }

    /// 탐색에 필요한 길이보다 짧은 청크는 무성음으로 판정한다
    func testChunkShorterThanTheSearchLengthIsJudgedUnvoiced() {
        XCTAssertNil(YinPitchEstimator.estimate(sine(220.0, size: 256)))
    }

    /// 에너지 게이트 아래의 작은 사인파는 무성음으로 판정한다
    func testQuietSineBelowTheEnergyGateIsJudgedUnvoiced() {
        // 진폭 50이면 RMS는 약 35 - 게이트(100) 아래라 CMNDF가 뭐라고 하든 판정하지 않는다.
        XCTAssertNil(YinPitchEstimator.estimate(sine(220.0, amplitude: 50.0)))
    }

    /// 에너지 게이트 위면 작은 진폭이어도 F0를 추정한다
    func testEstimatesF0ForASmallAmplitudeAboveTheEnergyGate() throws {
        // 진폭 3000이면 RMS는 약 2121 - 게이트를 넉넉히 넘는다.
        let f0 = YinPitchEstimator.estimate(sine(220.0, amplitude: 3000.0))
        XCTAssertNotNil(f0)
        XCTAssertEqual(220, try XCTUnwrap(f0), accuracy: 3)
    }

    /// 에너지 게이트 경계를 사이에 두고 판정이 갈린다
    func testTheVerdictSplitsAcrossTheEnergyGateBoundary() throws {
        // 사인파 RMS = 진폭/√2. 진폭 138이면 약 97.6(게이트 아래), 160이면 약 113(게이트 위).
        XCTAssertNil(YinPitchEstimator.estimate(sine(220.0, amplitude: 138.0)))

        let f0 = YinPitchEstimator.estimate(sine(220.0, amplitude: 160.0))
        XCTAssertNotNil(f0)
        XCTAssertEqual(220, try XCTUnwrap(f0), accuracy: 3)
    }

    // MARK: - 성능 감시 (안드로이드에는 없는 이식 단계 추가분)

    /// 2048샘플 프레임 100개를 추정하는 데 걸리는 시간을 재고 평균을 찍는다.
    ///
    /// 상한은 **느슨한 회귀 감시선**이지 성능 목표가 아니다. 진짜 예산은 프레임 하나당 100ms
    /// (NFR-PF-02)이고 그건 실기기에서 재야 의미가 있다(pitch-curve.md §3) — 여기서 잡는 건
    /// "O(W·τ) 루프가 실수로 O(W·τ²)가 되는" 종류의 사고다.
    ///
    /// 상한이 구성별로 갈리는 이유: 이 계산은 스칼라 부동소수점 루프라 최적화 유무에 100배가 걸린다.
    /// 이 맥 실측으로 디버그 2.5s(26ms/프레임), 릴리스 0.023s(0.23ms/프레임)다. 한 상한으로 묶으면
    /// 디버그에 맞춘 순간 릴리스 회귀를 100배까지 놓치므로 각자에 맞는 선을 둔다(각 2~5배 여유).
    /// **`swift test`는 디버그다** — 릴리스 쪽 선을 보려면 `swift test -c release`.
    func testEstimating100FramesStaysWellUnderTheBudget() {
        // 프레임마다 다른 주파수를 줘서 전부 유성 경로(CMNDF 전체 계산)를 타게 한다 —
        // 무음이면 에너지 게이트가 O(W·τ)를 통째로 건너뛰어 아무것도 재지 않는 셈이 된다.
        let frames = (0..<100).map { sine(120.0 + Double($0) * 2.0) }

        let started = DispatchTime.now().uptimeNanoseconds
        var voiced = 0
        for frame in frames where YinPitchEstimator.estimate(frame) != nil { voiced += 1 }
        let elapsedNs = DispatchTime.now().uptimeNanoseconds - started

        #if DEBUG
        let budgetSeconds = 6.0
        let configuration = "디버그(-Onone)"
        #else
        let budgetSeconds = 0.1
        let configuration = "릴리스"
        #endif

        let elapsedSeconds = Double(elapsedNs) / 1_000_000_000
        print(
            String(
                format: "YIN 100프레임 [%@]: 총 %.3fs, 프레임당 평균 %.3fms (유성 %d/100, 상한 %.1fs)",
                configuration, elapsedSeconds, elapsedSeconds * 1000 / 100, voiced, budgetSeconds
            )
        )
        XCTAssertEqual(100, voiced, "합성 사인파 100개는 전부 유성으로 잡혀야 한다")
        XCTAssertLessThan(elapsedSeconds, budgetSeconds)
    }
}
