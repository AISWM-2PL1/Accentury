import XCTest
@testable import AccenturyCore

/// 안드로이드 정본과 iOS 이식본이 **같은 입력에 같은 곡선**을 내는지 못박는 교차 검증.
///
/// 개별 이식 테스트(`YinPitchEstimatorTests` 등)는 "220Hz ± 3Hz"처럼 느슨한 허용 오차를 쓴다.
/// 알고리즘이 통째로 갈려도 그 정도는 통과하므로, 두 플랫폼이 정말 같은 수를 내는지는 못 잡는다.
/// 이 파일이 그 자리다.
///
/// ## 기준값 출처
/// 아래 숫자는 안드로이드 구현을 직접 돌려 받아 적은 것이다. 절차 —
/// 임시 테스트(`TempParityDumpTest.kt`)를 `app/src/test`에 두고
/// `./gradlew :app:testDebugUnitTest --tests '*TempParityDumpTest*' -i`로 실행해 stdout을 옮긴 뒤,
/// 그 임시 파일은 지웠다(정본 레포에 커밋하지 않는다). 값이 다시 필요하면 같은 절차로 재생성한다.
/// 실행 시점: 2026-08-30, `feature/KAN-108-ios-port` 기준 `audio/` 정본.
///
/// ## 허용 오차
/// - **F0: 0.01Hz.** 두 언어가 같은 `Float` 연산을 같은 순서로 하므로 원리적으로는 비트가 같아야 하지만,
///   입력 생성에 쓰는 `sin`이 JVM(`StrictMath` 계열)과 Darwin libm에서 최대 1 ulp 다를 수 있다.
///   그 1 ulp가 `.toInt()` 절단 경계를 넘으면 표본 하나가 1만큼 달라지고, 그 여파가 F0에 남는다.
///   0.01Hz는 그 여파를 덮으면서도 알고리즘이 갈린 경우(수 Hz~옥타브)는 놓치지 않는 폭이다.
/// - **입력 체크섬은 오차 없이 정확히 같아야 한다.** 체크섬이 맞으면 위의 1 ulp 걱정조차 실제로는
///   일어나지 않았다는 뜻이고, F0가 어긋나면 원인이 입력이 아니라 알고리즘이라고 바로 좁힐 수 있다.
/// - RMS·품질 지표는 `Double` 연산이라 1e-6로 조인다.
final class AndroidParityTests: XCTestCase {

    // MARK: - 안드로이드에서 받아 적은 기준값

    private static let caseAChecksum: Int64 = 3_970_478_494_216_241_896
    private static let caseARms = 5648.229203
    private static let caseAF0: Float = 220.015381

    private static let caseBChecksum: Int64 = -6_498_552_261_542_958_870
    private static let caseBFrameCount = 13
    /// (startSampleIndex, rms, f0) — f0가 nil이면 무성음 판정.
    private static let caseBFrames: [(start: Int64, rms: Double, f0: Float?)] = [
        (0, 4236.071798, 150.004074),
        (512, 4237.947847, 150.004395),
        (1024, 4255.090432, 150.004318),
        (1536, 4225.436412, 150.004135),
        (2048, 4256.245014, 150.004486),
        (2560, 3683.051326, 150.243393),
        (3072, 3004.997536, 150.373718),
        (3584, 2154.239066, 150.829224),
        (4096, 0.0, nil),
        (4608, 0.0, nil),
        (5120, 0.0, nil),
        (5632, 0.0, nil),
        (6144, 0.0, nil),
    ]
    private static let caseBQuality = ClientQuality(
        rms: 0.091628902, peak: 0.183105469, silenceRatio: 0.515625000, clipped: false
    )

    private static let f0Tolerance: Float = 0.01
    private static let rmsTolerance = 1e-6

    // MARK: - 입력 생성 (안드로이드 임시 테스트와 문자 그대로 같은 식)

    private func sine(_ freqHz: Double, amplitude: Double, size: Int) -> [Int16] {
        (0..<size).map {
            Int16(truncatingIfNeeded: Int(amplitude * sin(2 * Double.pi * freqHz * Double($0) / Double(sampleRate))))
        }
    }

    /// 입력 표본이 두 플랫폼에서 정말 같은지 한 수로 접는다. 안드로이드 쪽과 같은 식이며
    /// Long 오버플로가 그대로 감기도록 Swift에서도 감싸는 연산을 쓴다.
    private func checksum(_ pcm: [Int16]) -> Int64 {
        var h: Int64 = 1_125_899_906_842_597
        for s in pcm { h = h &* 31 &+ Int64(s) }
        return h
    }

    // MARK: - 케이스 A: 단일 프레임 220Hz

    /// 2048샘플 220Hz 사인파(진폭 8000) 한 프레임의 F0가 안드로이드와 같다.
    func testCaseA220HzSingleFrameMatchesAndroid() throws {
        let caseA = sine(220.0, amplitude: 8000.0, size: 2048)

        XCTAssertEqual(2048, caseA.count)
        XCTAssertEqual(Self.caseAChecksum, checksum(caseA), "입력 표본부터 안드로이드와 다르다")
        XCTAssertEqual(Self.caseARms, calculateRms(caseA), accuracy: Self.rmsTolerance)

        let f0 = try XCTUnwrap(YinPitchEstimator.estimate(caseA))
        XCTAssertEqual(Self.caseAF0, f0, accuracy: Self.f0Tolerance)
    }

    // MARK: - 케이스 B: 150Hz 사인 + 무음 꼬리를 겹침 프레이밍에 태운다

    /// 4096샘플 150Hz 사인(진폭 6000) 뒤에 4096샘플 무음을 붙여 `OverlappedFramer`(2048/512)에
    /// 밀어넣고, 프레임 13개의 시작 위치·RMS·F0와 유성/무성 판정이 전부 안드로이드와 같은지 본다.
    /// 꼬리 5프레임이 무성음으로 떨어지는 것까지 같아야 곡선의 끊김 위치가 두 플랫폼에서 같다.
    func testCaseB150HzWithSilenceTailMatchesAndroidFrameByFrame() {
        var caseB = [Int16](repeating: 0, count: 8192)
        let voiced = sine(150.0, amplitude: 6000.0, size: 4096)
        caseB.replaceSubrange(0..<4096, with: voiced)

        XCTAssertEqual(Self.caseBChecksum, checksum(caseB), "입력 표본부터 안드로이드와 다르다")

        let framer = OverlappedFramer()
        let frames = framer.push(caseB)

        XCTAssertEqual(Self.caseBFrameCount, frames.count)
        for (i, expected) in Self.caseBFrames.enumerated() {
            let frame = frames[i]
            XCTAssertEqual(expected.start, frame.startSampleIndex, "프레임 \(i)의 전역 시작 위치")
            XCTAssertEqual(chunkSize, frame.samples.count, "프레임 \(i)의 창 길이")
            XCTAssertEqual(expected.rms, calculateRms(frame.samples), accuracy: Self.rmsTolerance, "프레임 \(i)의 RMS")

            let actual = YinPitchEstimator.estimate(frame.samples)
            switch (expected.f0, actual) {
            case (nil, nil):
                break
            case (nil, let value?):
                XCTFail("프레임 \(i)(start=\(expected.start))는 안드로이드에서 무성음인데 \(value)Hz가 나왔다")
            case (let value?, nil):
                XCTFail("프레임 \(i)(start=\(expected.start))는 안드로이드에서 \(value)Hz인데 무성음으로 나왔다")
            case (let expectedF0?, let actualF0?):
                XCTAssertEqual(expectedF0, actualF0, accuracy: Self.f0Tolerance, "프레임 \(i)의 F0")
            }
        }
    }

    /// 같은 입력의 업로드용 품질 지표(`AudioQuality.measure`)도 안드로이드와 같은 수를 낸다 —
    /// 서버가 두 앱에서 오는 meta를 같은 기준으로 읽을 수 있어야 한다.
    func testCaseBClientQualityMatchesAndroid() {
        var caseB = [Int16](repeating: 0, count: 8192)
        caseB.replaceSubrange(0..<4096, with: sine(150.0, amplitude: 6000.0, size: 4096))

        let quality = AudioQuality.measure(caseB)

        XCTAssertEqual(Self.caseBQuality.rms, quality.rms, accuracy: 1e-9)
        XCTAssertEqual(Self.caseBQuality.peak, quality.peak, accuracy: 1e-9)
        XCTAssertEqual(Self.caseBQuality.silenceRatio, quality.silenceRatio, accuracy: 1e-9)
        XCTAssertEqual(Self.caseBQuality.clipped, quality.clipped)
    }
}
