import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/upload/UploadManagerTest.kt`의 불변식 케이스를
/// 상태 타입 쪽으로 옮겨 온 이식본 — 검증 대상이 `UploadState.Failed`의 `require`이기 때문이다.
///
/// 안드로이드는 `assertThrows(IllegalArgumentException)`으로 잡지만, 스위프트의 `precondition`은
/// 프로세스를 중단시켜 테스트가 잡을 수 없다. 그래서 같은 판정을 노출한
/// ``UploadState/isRepresentable(retryable:rerecord:)``를 검증하고, 합법 조합은 실제로 만들어 본다.
final class UploadStateTests: XCTestCase {

    func testRetryAndRerecordCannotStandTogether() {
        XCTAssertFalse(UploadState.isRepresentable(retryable: true, rerecord: true))

        // 한쪽만 서는 조합은 그대로 허용된다.
        XCTAssertTrue(UploadState.isRepresentable(retryable: true, rerecord: false))
        XCTAssertTrue(UploadState.isRepresentable(retryable: false, rerecord: true))
        XCTAssertTrue(UploadState.isRepresentable(retryable: false, rerecord: false))

        _ = UploadState.failed(retryable: true, message: "x", rerecord: false)
        _ = UploadState.failed(retryable: false, message: "x", rerecord: true)
        _ = UploadState.failed(retryable: false, message: "x", rerecord: false)
    }
}
