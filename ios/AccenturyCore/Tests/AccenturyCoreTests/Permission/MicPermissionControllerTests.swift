import XCTest
@testable import AccenturyCore

/// `app/src/test/java/com/accentury/app/permission/MicPermissionControllerTest.kt`의 1:1 이식본.
/// 케이스와 의도가 안드로이드와 같아야 브리지가 두 플랫폼에 같은 상태를 넘긴다고 말할 수 있다.
/// (메서드 이름은 XCTest 규칙상 `test`로 시작해야 해서 영어이고, 원본 한글 이름은 주석에 남긴다.)
final class MicPermissionControllerTests: XCTestCase {

    /// 이미 허용된 상태로 게이트에 들어오면 안내 화면 없이 바로 통과한다
    func testEnteringTheGateAlreadyGrantedSkipsTheRationaleScreen() {
        let controller = MicPermissionController(initiallyGranted: true)
        XCTAssertEqual(MicPermissionState.granted, controller.state)
    }

    /// 허용 전이면 OS 팝업에 앞서 자체 안내 화면부터 보여준다
    func testBeforeGrantTheOwnRationaleScreenComesFirst() {
        let controller = MicPermissionController(initiallyGranted: false)
        XCTAssertEqual(MicPermissionState.rationale, controller.state)
    }

    /// 팝업에서 허용하면 통과한다
    func testGrantingInThePopupPasses() {
        let controller = MicPermissionController(initiallyGranted: false)
        controller.onPermissionResult(granted: true, canAskAgain: false)
        XCTAssertEqual(MicPermissionState.granted, controller.state)
    }

    /// 거부해도 재요청이 가능하면 Denied - 다시 요청 버튼 경로
    func testDenialThatCanAskAgainIsDenied() {
        let controller = MicPermissionController(initiallyGranted: false)
        controller.onPermissionResult(granted: false, canAskAgain: true)
        XCTAssertEqual(MicPermissionState.denied, controller.state)
    }

    /// 거부인데 재요청까지 막혔으면 PermanentlyDenied - 설정 딥링크만 남는다
    func testDenialWithoutAnotherAskIsPermanentlyDenied() {
        let controller = MicPermissionController(initiallyGranted: false)
        controller.onPermissionResult(granted: false, canAskAgain: false)
        XCTAssertEqual(MicPermissionState.permanentlyDenied, controller.state)
    }

    /// 재요청에서 다시 거부하면 영구 거부로 굳는다 - Android 2회 거부 정책
    /// (iOS는 첫 거부에서 곧바로 아래 상태가 되지만, 상태 머신은 두 경로를 모두 받는다.)
    func testDenyingTwiceHardensIntoPermanentlyDenied() {
        let controller = MicPermissionController(initiallyGranted: false)
        controller.onPermissionResult(granted: false, canAskAgain: true)
        controller.onPermissionResult(granted: false, canAskAgain: false)
        XCTAssertEqual(MicPermissionState.permanentlyDenied, controller.state)
    }

    /// 영구 거부 후 설정에서 허용하고 돌아오면 재시작 없이 통과한다
    func testGrantingInSettingsAndComingBackPassesWithoutRestart() {
        let controller = MicPermissionController(initiallyGranted: false)
        controller.onPermissionResult(granted: false, canAskAgain: false)
        controller.onReturnedToApp(granted: true)
        XCTAssertEqual(MicPermissionState.granted, controller.state)
    }

    /// 설정에서 허용하지 않고 돌아오면 상태가 그대로다
    func testComingBackWithoutGrantingKeepsTheState() {
        let controller = MicPermissionController(initiallyGranted: false)
        controller.onPermissionResult(granted: false, canAskAgain: false)
        controller.onReturnedToApp(granted: false)
        XCTAssertEqual(MicPermissionState.permanentlyDenied, controller.state)
    }

    /// 허용 뒤에 도착한 복귀 재확인이 상태를 되돌리지 않는다
    func testALateReturnCheckDoesNotUndoAGrant() {
        let controller = MicPermissionController(initiallyGranted: false)
        controller.onPermissionResult(granted: true, canAskAgain: false)
        controller.onReturnedToApp(granted: false)
        XCTAssertEqual(MicPermissionState.granted, controller.state)
    }

    /// 회전 후 복원돼도 영구 거부가 유지된다 - 설정 딥링크 경로를 잃지 않는다
    func testRestoreKeepsPermanentlyDenied() {
        let controller = MicPermissionController.restored(saved: .permanentlyDenied, currentlyGranted: false)
        XCTAssertEqual(MicPermissionState.permanentlyDenied, controller.state)
    }

    /// 회전 후 복원돼도 소프트 거부가 유지된다
    func testRestoreKeepsSoftDenied() {
        let controller = MicPermissionController.restored(saved: .denied, currentlyGranted: false)
        XCTAssertEqual(MicPermissionState.denied, controller.state)
    }

    /// 복원 시점에 실제로 허용돼 있으면 저장값과 무관하게 통과한다
    func testRealGrantAtRestoreTimeWinsOverTheSavedState() {
        let controller = MicPermissionController.restored(saved: .permanentlyDenied, currentlyGranted: true)
        XCTAssertEqual(MicPermissionState.granted, controller.state)
    }

    /// 저장값은 Granted인데 실제로는 회수됐으면 처음부터 다시 묻는다
    func testSavedGrantedButRevokedAsksFromTheStart() {
        let controller = MicPermissionController.restored(saved: .granted, currentlyGranted: false)
        XCTAssertEqual(MicPermissionState.rationale, controller.state)
    }

    // MARK: - iOS 추가분

    /// 저장 키는 안드로이드 `rememberSaveable` 값과 글자까지 같아야 한다 — 두 플랫폼이
    /// 같은 계약 문자열을 브리지로 내보낸다.
    func testSaveKeysMatchTheAndroidStrings() {
        XCTAssertEqual("rationale", MicPermissionState.rationale.saveKey)
        XCTAssertEqual("granted", MicPermissionState.granted.saveKey)
        XCTAssertEqual("denied", MicPermissionState.denied.saveKey)
        XCTAssertEqual("permanently_denied", MicPermissionState.permanentlyDenied.saveKey)
    }

    /// 저장 키 왕복. 모르는 키·빈 값은 안내 화면으로 떨어진다.
    func testStateFromSaveKeyRoundTripsAndFallsBackToRationale() {
        for state in MicPermissionState.allCases {
            XCTAssertEqual(state, MicPermissionState.fromSaveKey(state.saveKey))
        }
        XCTAssertEqual(MicPermissionState.rationale, MicPermissionState.fromSaveKey(nil))
        XCTAssertEqual(MicPermissionState.rationale, MicPermissionState.fromSaveKey("garbage"))
    }

    /// 상태를 바꾸는 메서드는 전부 동기다 — 호출 직후에 읽은 값이 곧 새 상태다.
    /// (앱 계층 `PermissionGateModel`이 `@Published`를 이 전제로 갱신한다.)
    func testStateIsVisibleSynchronouslyAfterEachCall() {
        let controller = MicPermissionController(initiallyGranted: false)
        XCTAssertEqual(MicPermissionState.rationale, controller.state)
        controller.onReturnedToApp(granted: false)
        XCTAssertEqual(MicPermissionState.rationale, controller.state)
        controller.onPermissionResult(granted: false, canAskAgain: false)
        XCTAssertEqual(MicPermissionState.permanentlyDenied, controller.state)
        controller.onReturnedToApp(granted: true)
        XCTAssertEqual(MicPermissionState.granted, controller.state)
    }
}
