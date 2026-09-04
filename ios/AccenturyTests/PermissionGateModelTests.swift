import AccenturyCore
import XCTest
@testable import Accentury

/// 앱 계층 테스트. 상태 머신 자체는 `AccenturyCoreTests/Permission`이 덮으므로, 여기서
/// 확인하는 것은 그 위에 얹힌 결선 하나다 — **저장된 상태와 실제 권한이 어긋날 때 실제
/// 권한이 이긴다**(KAN-98에서 배운 것). 저장은 일회용 `UserDefaults` 스위트로, 실제 권한은
/// 주입한 클로저로 대신해 `AVAudioSession`을 건드리지 않는다.
@MainActor
final class PermissionGateModelTests: XCTestCase {

    private var suiteName = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        // 테스트마다 새 스위트를 판다 — `.standard`를 쓰면 시뮬레이터에 남은 앞 실행의
        // 저장값이 결과를 흔든다.
        suiteName = "PermissionGateModelTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    private func model(saved: String?, realGranted: Bool) -> PermissionGateModel {
        if let saved {
            defaults.set(saved, forKey: PermissionGateModel.storageKey)
        }
        return PermissionGateModel(
            defaults: defaults,
            isGranted: { realGranted },
            requestFromOS: { realGranted }
        )
    }

    /// (a) 방금 설치한 상태 — 저장값이 없으면 안내 화면부터다.
    func testFreshInstallStartsAtRationale() {
        XCTAssertEqual(MicPermissionState.rationale, model(saved: nil, realGranted: false).state)
    }

    /// (b) 영구 거부는 프로세스를 다시 시작해도 유지된다 — 설정 딥링크 경로를 잃지 않는다.
    func testSavedPermanentlyDeniedSurvivesRelaunch() {
        XCTAssertEqual(
            MicPermissionState.permanentlyDenied,
            model(saved: "permanently_denied", realGranted: false).state
        )
    }

    /// (c) 설정에서 허용하고 돌아온 뒤 앱이 새로 떴으면, 저장된 영구 거부보다 실제 권한이 이긴다.
    func testRealGrantBeatsSavedPermanentlyDenied() {
        XCTAssertEqual(
            MicPermissionState.granted,
            model(saved: "permanently_denied", realGranted: true).state
        )
    }

    /// (d) 반대 방향 — 저장값은 허용인데 설정에서 회수됐으면 처음부터 다시 묻는다.
    func testSavedGrantedButRevokedFallsBackToRationale() {
        XCTAssertEqual(MicPermissionState.rationale, model(saved: "granted", realGranted: false).state)
    }

    /// 복원 결과는 곧바로 다시 저장된다 — 위 (d)처럼 저장값이 뒤집힌 경우 디스크가 낡은 채로
    /// 남아 다음 실행에서 같은 판정을 또 하지 않도록.
    func testRestoredStateIsWrittenBack() {
        _ = model(saved: "granted", realGranted: false)
        XCTAssertEqual("rationale", defaults.string(forKey: PermissionGateModel.storageKey))
    }

    /// 팝업에서 허용하면 통과하고 그 값이 저장된다.
    func testGrantingThroughTheRequestPersistsGranted() async {
        // 시작은 미허용(안내 화면)이고 팝업만 허용을 준다.
        let sut = PermissionGateModel(
            defaults: defaults,
            isGranted: { false },
            requestFromOS: { true }
        )
        XCTAssertEqual(MicPermissionState.rationale, sut.state)

        await sut.requestPermission()

        XCTAssertEqual(MicPermissionState.granted, sut.state)
        XCTAssertEqual("granted", defaults.string(forKey: PermissionGateModel.storageKey))
    }

    /// iOS는 거부하면 팝업을 다시 띄우지 않는다 — 한 번의 거부가 곧 영구 거부다
    /// (안드로이드는 여기서 `denied`를 한 번 거친다. 상태 계약은 같고 도달 경로만 다르다).
    func testDenyingOnceCollapsesStraightToPermanentlyDenied() async {
        let sut = PermissionGateModel(
            defaults: defaults,
            isGranted: { false },
            requestFromOS: { false }
        )
        await sut.requestPermission()
        XCTAssertEqual(MicPermissionState.permanentlyDenied, sut.state)
        XCTAssertEqual("permanently_denied", defaults.string(forKey: PermissionGateModel.storageKey))
    }

    // MARK: - 요청과 복귀 재대조의 경합

    /// 팝업이 떠 있는 동안 복귀 재대조가 먼저 허용을 관측하면, 뒤늦게 도착한 거부 결과가
    /// 그것을 덮지 못한다 — 실제 권한이 이긴다. 덮으면 실제로는 허용된 마이크를 영구 거부로
    /// 적고 디스크에까지 남겨, 사용자가 설정에서 이미 켠 뒤에도 게이트가 열리지 않는다.
    func testLateDenialDoesNotOverrideARealGrantObservedMeanwhile() async {
        let gate = RequestGate()
        var realGranted = false
        let sut = PermissionGateModel(
            defaults: defaults,
            isGranted: { realGranted },
            requestFromOS: { await gate.wait() }
        )

        let request = Task { await sut.requestPermission() }
        await gate.awaitStart()                 // 요청이 실제로 걸린 시점

        realGranted = true                      // 사용자가 그 사이 설정에서 켰다
        sut.onReturnedToApp()                   // scenePhase == .active 재대조
        XCTAssertEqual(MicPermissionState.granted, sut.state)

        await gate.finish(false)                // 낡은 거부 결과가 이제 도착한다
        await request.value

        XCTAssertEqual(MicPermissionState.granted, sut.state)
        XCTAssertEqual("granted", defaults.string(forKey: PermissionGateModel.storageKey))
    }

    /// 요청이 도는 동안 다시 눌러도 요청은 하나뿐이다 (버튼 연타·자동 스모크 중복 진입).
    func testASecondRequestWhileOneIsInFlightIsIgnored() async {
        let gate = RequestGate()
        let sut = PermissionGateModel(
            defaults: defaults,
            isGranted: { false },
            requestFromOS: { await gate.wait() }
        )

        let first = Task { await sut.requestPermission() }
        await gate.awaitStart()
        XCTAssertTrue(sut.isRequesting)

        await sut.requestPermission()           // 두 번째 호출은 즉시 되돌아온다
        let callsWhileInFlight = await gate.callCount
        XCTAssertEqual(1, callsWhileInFlight)

        await gate.finish(false)
        await first.value
        XCTAssertFalse(sut.isRequesting)
        let totalCalls = await gate.callCount
        XCTAssertEqual(1, totalCalls)
    }

    // MARK: - 통과 통보는 정확히 한 번

    /// 이미 허용된 채로 게이트에 들어온 경우.
    func testGrantedDeliveryFiresOnceWhenAlreadyGranted() {
        let sut = model(saved: nil, realGranted: true)
        XCTAssertEqual(MicPermissionState.granted, sut.state)
        XCTAssertTrue(sut.consumeGrantedDelivery())
        XCTAssertFalse(sut.consumeGrantedDelivery())

        // 화면이 다시 나타나 재대조가 여러 번 돌아도 두 번째 통보는 없다.
        for _ in 0..<3 {
            sut.onReturnedToApp()
            XCTAssertFalse(sut.consumeGrantedDelivery())
        }
    }

    /// 안내 화면에서 요청을 거쳐 통과한 경우.
    func testGrantedDeliveryFiresOnceAfterTransition() async {
        let sut = PermissionGateModel(
            defaults: defaults,
            isGranted: { false },
            requestFromOS: { true }
        )
        XCTAssertFalse(sut.consumeGrantedDelivery())    // 아직 통과 전이라 통보 없음

        await sut.requestPermission()
        XCTAssertTrue(sut.consumeGrantedDelivery())
        XCTAssertFalse(sut.consumeGrantedDelivery())

        for _ in 0..<3 {
            sut.onReturnedToApp()
            XCTAssertFalse(sut.consumeGrantedDelivery())
        }
    }

    /// 설정에서 허용하고 앱으로 돌아오면(`scenePhase == .active`) 재시작 없이 통과한다.
    func testReturningFromSettingsWithGrantPasses() async {
        var granted = false
        let sut = PermissionGateModel(
            defaults: defaults,
            isGranted: { granted },
            requestFromOS: { granted }
        )
        await sut.requestPermission()
        XCTAssertEqual(MicPermissionState.permanentlyDenied, sut.state)

        granted = true                    // 사용자가 설정 앱에서 켠 시점
        sut.onReturnedToApp()
        XCTAssertEqual(MicPermissionState.granted, sut.state)
        XCTAssertEqual("granted", defaults.string(forKey: PermissionGateModel.storageKey))
    }
}

/// 가짜 OS 팝업. 테스트가 "요청이 걸린 시점"과 "결과가 도착하는 시점"을 따로 잡을 수 있어야
/// 경합을 재현할 수 있는데, 그 둘 사이를 벌리는 것이 이 액터가 하는 일의 전부다.
/// (`Task.sleep`으로 벌리면 기계 속도에 따라 흔들리는 테스트가 된다.)
private actor RequestGate {

    private(set) var callCount = 0

    private var resultWaiter: CheckedContinuation<Bool, Never>?
    private var startWaiters: [CheckedContinuation<Void, Never>] = []
    private var didStart = false
    private var result: Bool?

    /// 검사 대상이 부르는 쪽 — 결과가 올 때까지 붙들린다.
    func wait() async -> Bool {
        callCount += 1
        didStart = true
        startWaiters.forEach { $0.resume() }
        startWaiters = []

        if let result { return result }
        return await withCheckedContinuation { resultWaiter = $0 }
    }

    /// 테스트가 부르는 쪽 — 요청이 실제로 걸릴 때까지 기다린다.
    func awaitStart() async {
        if didStart { return }
        await withCheckedContinuation { startWaiters.append($0) }
    }

    /// 테스트가 부르는 쪽 — 결과를 지금 도착시킨다.
    func finish(_ value: Bool) {
        result = value
        resultWaiter?.resume(returning: value)
        resultWaiter = nil
    }
}
