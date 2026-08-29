import AccenturyCore
import XCTest
@testable import Accentury

/// 가짜 마이크 배선이 실제 번들까지 이어졌는지 확인한다. Core 쪽 `FilePcmSourceTests`가
/// "파일을 어떻게 읽는가"를 보는 것과 달리 여기는 "빌드 설정이 앱까지 흘러왔는가"를 본다 —
/// xcconfig → Info.plist → `infoDictionary` 사슬은 앱 번들이 있어야 검증되기 때문이다.
final class PcmSourcesTests: XCTestCase {

    /// 키가 사라지면 `-PfakeMic` 대응 기능이 조용히 죽는다(값이 빈 문자열일 때와 구분이 안 된다).
    func testFakeMicKeyIsWiredInTheDebugBundle() {
        XCTAssertNotNil(
            Bundle.main.object(forInfoDictionaryKey: "FAKE_MIC_ASSET") as? String,
            "Info-Debug.plist에 FAKE_MIC_ASSET이 없다 - xcconfig→plist 치환이 끊겼다"
        )
    }

    /// 설정하지 않은 상태(정상 상태)에서는 실제 마이크를 쓴다.
    func testDefaultSourceIsTheMicrophoneWhenFakeMicIsUnset() throws {
        let configured = (Bundle.main.object(forInfoDictionaryKey: "FAKE_MIC_ASSET") as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        try XCTSkipUnless(
            configured.isEmpty,
            "Local.xcconfig가 FAKE_MIC_ASSET=\(configured)로 가짜 마이크를 켜 뒀다"
        )
        XCTAssertTrue(defaultPcmSource() is AudioRecorder)
    }
}
