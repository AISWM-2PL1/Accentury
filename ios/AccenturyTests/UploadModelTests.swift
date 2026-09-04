import AccenturyCore
import UIKit
import XCTest
@testable import Accentury

/// 앱 계층 테스트. 업로드 수명(등록·재시도·폐기·멱등)은 `AccenturyCoreTests/Upload`가 덮으므로,
/// 여기서 확인하는 것은 그 위에 얹힌 결선 셋이다.
///
/// 1. 매니저의 상태 스트림이 `@Published` 값으로 흘러 들어오는가 — 이게 끊기면 상태 바도,
///    결과 전달도(`TestFlowController.onUploadsChanged`) 통째로 멈춘다.
/// 2. 세션이 바뀌면 매니저를 갈아끼우는가 — 안 갈면 끝난 응시의 멱등 키가 새 세션으로 나간다.
/// 3. 진행 중인 업로드가 있는 동안만 백그라운드 실행 시간을 빌리는가 — 안 빌리면 앱이 뒤로
///    가는 순간 업로드가 조용히 끊기고, 반납을 잊으면 iOS가 앱을 죽인다.
@MainActor
final class UploadModelTests: XCTestCase {

    private var client: FakeUploadClient!
    private var backgroundTasksBegun = 0
    private var backgroundTasksEnded = 0
    /// iOS가 "시간이 다 됐다"고 부르는 핸들러. 테스트가 그 순간을 직접 만든다.
    private var expiryHandler: (() -> Void)?

    override func setUp() {
        super.setUp()
        client = FakeUploadClient()
        backgroundTasksBegun = 0
        backgroundTasksEnded = 0
        expiryHandler = nil
    }

    private func makeModel() -> UploadModel {
        UploadModel(
            makeClient: { [client] _ in client! },
            beginBackgroundTask: { [self] expiry in
                backgroundTasksBegun += 1
                expiryHandler = expiry
                return UIBackgroundTaskIdentifier(rawValue: 1)
            },
            endBackgroundTask: { [self] _ in backgroundTasksEnded += 1 }
        )
    }

    private func session(id: String = "s_1") -> Session {
        Session(
            sessionId: id,
            sessionToken: "st_\(id)",
            testVersion: "gn-2026.08.1",
            scoreVersion: "sv-1",
            expiresAt: "2099-01-01T00:00:00Z"
        )
    }

    private func request(_ attemptId: String) -> UploadRequest {
        UploadRequest(
            attemptId: attemptId,
            itemId: "it_1",
            wavBytes: Data([0x52, 0x49, 0x46, 0x46]),
            durationMs: 2_000,
            clientQuality: ClientQuality(rms: 0.1, peak: 0.5, silenceRatio: 0.1, clipped: false)
        )
    }

    /// 매니저의 상태 스트림이 `entries`·`uploads`로 흘러 들어온다.
    func testManagerStateFlowsIntoPublishedValues() async {
        let model = makeModel()
        model.bind(to: session())

        model.enqueue(request("at_1"), label: "3번 문항")

        await waitUntil("등록한 업로드가 진행 중으로 보이지 않는다") {
            model.uploads["at_1"] == .inFlight
        }
        XCTAssertEqual(["at_1"], model.entries.map(\.attemptId))
        XCTAssertEqual("3번 문항", model.labelOf("at_1"))
        // 라벨을 모르는 키는 대체 문구다 — 상태 바가 빈 자리를 그리지 않는다.
        XCTAssertEqual(UploadManager.defaultLabel, model.labelOf("at_모르는키"))

        await client.respond(.accepted(analysisJobId: "aj_1"))

        await waitUntil("완료가 반영되지 않았다") {
            model.uploads["at_1"] == .done(analysisJobId: "aj_1")
        }
    }

    /// 서버 거절도 그대로 흘러 들어온다 — 상태 바의 [재시도]가 이 값 하나로 선다.
    func testServerRejectionFlowsThroughWithItsMessage() async {
        let model = makeModel()
        model.bind(to: session())

        model.enqueue(request("at_1"), label: "1번 문항")
        await waitUntil { model.uploads["at_1"] == .inFlight }

        await client.respond(
            .rejected(code: "UPSTREAM_UNAVAILABLE", message: "잠시 뒤에 다시 시도해 주세요", retryable: true, retryAfterMs: nil)
        )

        await waitUntil("거절이 반영되지 않았다") {
            if case .failed(let failure)? = model.uploads["at_1"] {
                return failure.retryable && failure.message == "잠시 뒤에 다시 시도해 주세요"
            }
            return false
        }
    }

    /// 세션이 바뀌면 매니저가 갈리고 앞 세션의 업로드는 사라진다.
    func testRebindingToANewSessionDropsThePreviousUploads() async {
        let model = makeModel()
        model.bind(to: session(id: "s_1"))
        model.enqueue(request("at_1"), label: "1번 문항")
        await waitUntil { model.uploads["at_1"] == .inFlight }

        model.bind(to: session(id: "s_2"))

        XCTAssertTrue(model.entries.isEmpty, "새 세션에 앞 세션의 업로드가 남았다")
        XCTAssertEqual(UploadManager.defaultLabel, model.labelOf("at_1"), "라벨도 함께 지워져야 한다")

        // 같은 세션으로 다시 결선하면 아무 일도 하지 않는다 — 화면이 다시 그려질 때마다 불린다.
        model.enqueue(request("at_2"), label: "2번 문항")
        await waitUntil { model.uploads["at_2"] == .inFlight }
        model.bind(to: session(id: "s_2"))
        XCTAssertEqual(["at_2"], model.entries.map(\.attemptId), "같은 세션인데 업로드가 날아갔다")
    }

    /// 진행 중인 업로드가 있는 동안만 백그라운드 실행 시간을 빌리고, 끝나면 반납한다.
    func testBackgroundTimeIsHeldOnlyWhileUploadsAreInFlight() async {
        let model = makeModel()
        model.bind(to: session())
        XCTAssertEqual(0, backgroundTasksBegun, "올릴 것이 없는데 시간을 빌렸다")

        model.enqueue(request("at_1"), label: "1번 문항")
        await waitUntil { model.uploads["at_1"] == .inFlight }
        XCTAssertEqual(1, backgroundTasksBegun)
        XCTAssertEqual(0, backgroundTasksEnded, "아직 올라가는 중인데 시간을 반납했다")

        await client.respond(.accepted(analysisJobId: "aj_1"))
        await waitUntil { model.uploads["at_1"] == .done(analysisJobId: "aj_1") }

        await waitUntil("업로드가 끝났는데 빌린 시간을 반납하지 않았다") { self.backgroundTasksEnded == 1 }
        XCTAssertEqual(1, backgroundTasksBegun, "한 건이 끝날 때마다 새로 빌리면 안 된다")
    }

    /// 올라가는 중에 흐름이 끝나도 빌린 시간은 반납된다 — 정확히 한 번.
    ///
    /// 이 자리가 없으면 업로드가 끝나기 전에 화면이 사라진 경우 식별자가 주인 없이 남고, iOS는
    /// 빌린 시간을 안 놓는 앱을 죽인다.
    func testTeardownReleasesBorrowedTimeWhileAnUploadIsStillInFlight() async {
        let model = makeModel()
        model.bind(to: session())
        model.enqueue(request("at_1"), label: "1번 문항")
        await waitUntil { model.uploads["at_1"] == .inFlight }
        XCTAssertEqual(1, backgroundTasksBegun)

        model.teardown()

        XCTAssertEqual(1, backgroundTasksEnded, "끝내면서 빌린 시간을 반납하지 않았다")
        XCTAssertTrue(model.entries.isEmpty, "끝냈는데 업로드가 남았다")

        // 여러 번 불려도 반납은 한 번이다 — 같은 식별자를 두 번 놓는 것은 잘못된 API 사용이다.
        model.teardown()
        XCTAssertEqual(1, backgroundTasksEnded)
    }

    /// iOS가 시간이 다 됐다고 알린 뒤의 ``UploadModel/teardown()``은 **추가 반납을 하지 않는다.**
    ///
    /// 만료 핸들러가 이미 식별자를 가져갔으므로, 뒤늦은 정리가 같은 식별자를 한 번 더 놓으면 안 된다.
    func testTeardownAfterExpiryDoesNotReleaseTwice() async {
        let model = makeModel()
        model.bind(to: session())
        model.enqueue(request("at_1"), label: "1번 문항")
        await waitUntil { model.uploads["at_1"] == .inFlight }

        let expiry = try? XCTUnwrap(expiryHandler)
        expiry?()
        XCTAssertEqual(1, backgroundTasksEnded, "만료 핸들러가 반납하지 않았다")

        model.teardown()

        XCTAssertEqual(1, backgroundTasksEnded, "만료와 정리가 겹쳐 두 번 반납했다")
    }

    /// 모델이 해제되면 빌린 시간도 함께 반납된다 — 소유자가 사라진 식별자는 아무도 못 놓는다.
    ///
    /// 참조가 남아 있지 않은지도 함께 본다. 상태 구독 Task가 매니저를 붙잡고 있으면 모델이
    /// 영영 해제되지 않아 이 반납 자체가 일어나지 않는다.
    func testDeallocReleasesBorrowedTimeAndLeavesNoStrongReference() async {
        weak var released: UploadModel?

        do {
            let model = makeModel()
            released = model
            model.bind(to: session())
            model.enqueue(request("at_1"), label: "1번 문항")
            await waitUntil { model.uploads["at_1"] == .inFlight }
            XCTAssertEqual(1, backgroundTasksBegun)
            XCTAssertEqual(0, backgroundTasksEnded)
        }

        XCTAssertNil(released, "모델이 해제되지 않았다 — 어딘가 강한 참조가 남아 있다")
        // 반납은 `deinit`에서 나가고, 메인이 아닌 스레드였다면 메인 큐를 한 번 거친다.
        await waitUntil("해제됐는데 빌린 시간이 반납되지 않았다") { self.backgroundTasksEnded == 1 }
    }
}
