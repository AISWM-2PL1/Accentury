import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/audio/RmsTest.kt`의 1:1 이식본.
///
/// XCTest는 메서드 이름으로 케이스를 찾아가므로 안드로이드의 한글 백틱 이름을 그대로 쓸 수 없다.
/// 원래 이름은 각 메서드의 문서 주석에 그대로 남겨, 두 파일을 나란히 놓고 대조할 수 있게 했다.
final class RmsTests: XCTestCase {

    /// 무음은 RMS 0이다
    func testSilenceHasZeroRms() {
        XCTAssertEqual(0.0, calculateRms([Int16](repeating: 0, count: 2048)), accuracy: 0.0)
    }

    /// 일정 진폭 신호의 RMS는 그 진폭이다
    func testConstantAmplitudeSignalRmsEqualsThatAmplitude() {
        let amplitude = 1000
        let square = (0..<2048).map { Int16($0 % 2 == 0 ? amplitude : -amplitude) }
        XCTAssertEqual(Double(amplitude), calculateRms(square), accuracy: 0.001)
    }
}
