import XCTest
@testable import AccenturyCore

/// 안드로이드 `upload/UploadManagerTest.kt`의 1:1 이식본.
///
/// 22개 중 "재전송과 재녹음이 함께 선 실패 상태는 만들 수 없다"는 상태 타입의 불변식이라
/// 이미 `UploadStateTests`에 있고, 나머지 21개가 여기 있다. 라벨 3개는 안드로이드에서
/// `UploadViewModelTest`에 있던 것으로, 라벨 보관을 매니저로 내리면서 함께 왔다.
///
/// 코틀린 테스트의 `advanceUntilIdle()`은 ``waitUntil(_:timeout:file:line:_:)``이 대신한다 —
/// 가상 시간이 없어 시간을 밀 수는 없지만, 기다리는 대상이 "스케줄러가 빌 때까지"가 아니라
/// "이 조건이 참이 될 때까지"라 오히려 무엇을 기다리는지가 테스트에 적힌다.
final class UploadManagerTests: XCTestCase {

    /// `TransportFailure.unknown`에 붙는 안내 문구. 화면에 실제로 뜨는 말이라 테스트가 직접 적어 못 박는다.
    private let transportFailed = UploadState.failed(retryable: true, message: "전송에 실패했어요. 다시 시도해 주세요")

    /// 종류를 따지지 않는 전송 실패. 이 테스트들이 보는 것은 "응답이 안 왔다"는 사실 하나뿐이다.
    private func transportError(_ failure: TransportFailure = .unknown) -> UploadResult {
        .transportError(failure: failure, reason: "network down")
    }

    private func requestOf(_ attemptId: String, itemId: String = "item-1") -> UploadRequest {
        UploadRequest(
            attemptId: attemptId,
            itemId: itemId,
            wavBytes: Data((0..<32).map { UInt8(($0 + attemptId.count) & 0xFF) }),
            durationMs: 2_000,
            clientQuality: ClientQuality(rms: 0.11, peak: 0.83, silenceRatio: 0.12, clipped: false)
        )
    }

    private func withManager(
        _ body: (FakeUploadClient, UploadManager) async throws -> Void
    ) async rethrows {
        let fake = FakeUploadClient()
        let manager = UploadManager(client: fake, sessionId: "sess-1", sessionToken: "token-1")
        try await body(fake, manager)
        await manager.clearAll()
        await fake.drain()
    }

    /// 이 키로 호출이 도착할 때까지 기다린다 — 응답을 주려면 전송이 먼저 대기 상태여야 한다.
    private func awaitCall(_ fake: FakeUploadClient, _ attemptId: String, count: Int = 1) async {
        await waitUntil("\(attemptId) 전송이 \(count)번째 도착하지 않았다") {
            await fake.hasCalls(attemptId, atLeast: count)
        }
    }

    private func awaitState(
        _ manager: UploadManager,
        _ attemptId: String,
        _ expected: UploadState?,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        await waitUntil("\(attemptId) 상태가 \(String(describing: expected))가 아니다", file: file, line: line) {
            await manager.state(of: attemptId) == expected
        }
    }

    // MARK: - 상태 전이

    func testenqueue_직후_InFlight이고_응답이_오면_Done으로_전이된다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            let immediate = await manager.state(of: "at-1")
            XCTAssertEqual(.inFlight, immediate)

            await awaitCall(fake, "at-1")
            await fake.respond("at-1", .accepted(analysisJobId: "aj_1"))

            await awaitState(manager, "at-1", .done(analysisJobId: "aj_1"))
        }
    }

    func test첫_업로드가_끝나기_전에_다음_업로드를_넣어도_둘_다_진행된다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            await manager.enqueue(requestOf("at-2", itemId: "item-2"))
            await awaitCall(fake, "at-2")

            let states = await manager.uploads
            XCTAssertEqual(.inFlight, states["at-1"])
            XCTAssertEqual(.inFlight, states["at-2"])
            let actual1 = await fake.receivedCount
            XCTAssertEqual(2, actual1)

            await fake.respond("at-2", .accepted(analysisJobId: "aj_2"))
            await awaitState(manager, "at-2", .done(analysisJobId: "aj_2"))
            let actual2 = await manager.state(of: "at-1")
            XCTAssertEqual(.inFlight, actual2)
        }
    }

    func test재시도_불가_Rejected는_Failed로_남고_retry는_무시된다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            await fake.respond(
                "at-1",
                .rejected(code: "SESSION_COMPLETED", message: "이미 종료된 세션입니다", retryable: false, retryAfterMs: nil)
            )
            await awaitState(manager, "at-1", .failed(retryable: false, message: "이미 종료된 세션입니다"))

            await manager.retry("at-1")

            let actual3 = await manager.state(of: "at-1")
            XCTAssertEqual(.failed(retryable: false, message: "이미 종료된 세션입니다"), actual3)
            let actual4 = await fake.callsFor("at-1")
            XCTAssertEqual(1, actual4)
        }
    }

    func testTransportError는_재시도_가능한_Failed가_된다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            await fake.respond("at-1", transportError(.timeout))

            await awaitState(manager, "at-1", .failed(retryable: true, message: "응답이 늦어요. 다시 시도해 주세요"))
        }
    }

    /*
     * 전송 실패 문구는 오류 종류에서 온다 (KAN-147 2단계). 네트워크 스택이 준
     * "A server with the specified hostname could not be found." 같은 문구가 상태 바에 그대로 뜨면
     * 사용자는 자기가 끊긴 건지 서버가 죽은 건지 알 수 없다.
     */
    func test기기가_끊긴_전송_실패는_연결을_확인하라는_문구로_내려온다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            await fake.respond("at-1", transportError(.offline))

            await awaitState(manager, "at-1", .failed(retryable: true, message: "인터넷 연결을 확인해 주세요"))
        }
    }

    /*
     * 녹음 자체를 거절한 코드는 재전송이 아니라 재녹음으로 간다 (KAN-147, 2026-08-25 B안).
     * 같은 바이트를 다시 보내면 서버가 같은 답을 할 뿐이라 [재시도]를 세워둘 자리가 아니다.
     */
    func test녹음이_문제라고_답한_거절은_재녹음_전환으로_내려온다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            await fake.respond(
                "at-1",
                .rejected(code: "AUDIO_TOO_LONG", message: "녹음이 너무 깁니다", retryable: false, retryAfterMs: nil)
            )

            await awaitState(manager, "at-1", .failed(retryable: false, message: "녹음이 너무 깁니다", rerecord: true))
        }
    }

    /*
     * AUDIO_TOO_QUIET은 서버가 재시도 가능이라고 주지만 같은 바이트를 다시 보내면 같은 판정이
     * 돌아온다. 재녹음이 재전송을 이기고, 두 복구 경로가 함께 서지 않는다.
     */
    func test서버가_재시도_가능이라_해도_녹음이_문제면_재녹음이_이긴다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            await fake.respond(
                "at-1",
                .rejected(code: "AUDIO_TOO_QUIET", message: "소리가 너무 작습니다", retryable: true, retryAfterMs: nil)
            )

            await awaitState(manager, "at-1", .failed(retryable: false, message: "소리가 너무 작습니다", rerecord: true))
        }
    }

    /*
     * 녹음과 무관한 서버 거절은 재녹음으로 보내지 않는다 (KAN-147, B안). 자동 전환을 걸면 사용자가
     * 읽어야 할 서버 안내가 녹음 화면에 밀려 사라지고, 그 문항은 다시 녹음해도 같은 거절을 받는다.
     */
    func test세션이_끝나_거절된_건은_재녹음으로_보내지_않고_서버_문구를_남긴다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            await fake.respond(
                "at-1",
                .rejected(code: "SESSION_EXPIRED", message: "세션이 만료되었습니다", retryable: false, retryAfterMs: nil)
            )

            await awaitState(
                manager, "at-1",
                .failed(retryable: false, message: "세션이 만료되었습니다", rerecord: false)
            )
        }
    }

    /*
     * 전송 실패에는 상한이 없다 (KAN-147, B안). 응답이 오지 않은 것은 녹음의 문제가 아니라
     * 잠깐 끊긴 것일 뿐이라, 여기서 녹음을 빼앗으면 되돌릴 수 없는 손실이 된다.
     */
    func test전송_실패는_몇_번을_다시_보내도_재시도_가능으로_남는다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))

            for round in 1...3 {
                await awaitCall(fake, "at-1", count: round)
                await fake.respond("at-1", transportError())
                await awaitState(manager, "at-1", transportFailed)
                await manager.retry("at-1")
                let actual5 = await manager.state(of: "at-1")
                XCTAssertEqual(.inFlight, actual5)
            }

            await awaitCall(fake, "at-1", count: 4)
            await fake.respond("at-1", transportError())
            await awaitState(manager, "at-1", transportFailed)
            let actual6 = await fake.callsFor("at-1")
            XCTAssertEqual(4, actual6)
        }
    }

    func testretry는_같은_멱등_키와_같은_바이트로_재전송한다() async {
        await withManager { fake, manager in
            let original = requestOf("at-1")

            await manager.enqueue(original)
            await awaitCall(fake, "at-1")
            await fake.respond("at-1", transportError())
            await awaitState(manager, "at-1", transportFailed)

            await manager.retry("at-1")
            let actual7 = await manager.state(of: "at-1")
            XCTAssertEqual(.inFlight, actual7)

            await awaitCall(fake, "at-1", count: 2)
            let resent = await fake.lastReceived
            XCTAssertEqual(original.attemptId, resent?.attemptId)
            XCTAssertEqual(original.wavBytes, resent?.wavBytes)

            await fake.respond("at-1", .accepted(analysisJobId: "aj_retry"))
            await awaitState(manager, "at-1", .done(analysisJobId: "aj_retry"))
        }
    }

    func test같은_attemptId로_다시_enqueue해도_이중_업로드하지_않는다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            let actual8 = await fake.callsFor("at-1")
            XCTAssertEqual(1, actual8)

            await fake.respond("at-1", .accepted(analysisJobId: "aj_1"))
            await awaitState(manager, "at-1", .done(analysisJobId: "aj_1"))

            await manager.enqueue(requestOf("at-1"))

            let actual9 = await fake.callsFor("at-1")
            XCTAssertEqual(1, actual9)
            let actual10 = await manager.state(of: "at-1")
            XCTAssertEqual(.done(analysisJobId: "aj_1"), actual10)
        }
    }

    func test실패한_키에_다른_바이트로_enqueue해도_무시되고_retry는_원본_바이트를_보낸다() async {
        await withManager { fake, manager in
            let original = requestOf("at-1")

            await manager.enqueue(original)
            await awaitCall(fake, "at-1")
            await fake.respond("at-1", transportError())
            await awaitState(manager, "at-1", transportFailed)

            // 같은 멱등 키에 다른 payload를 붙이려는 시도는 상태도 호출 횟수도 건드리지 못한다.
            await manager.enqueue(
                UploadRequest(
                    attemptId: "at-1",
                    itemId: original.itemId,
                    wavBytes: Data(repeating: 0x7F, count: 32),
                    durationMs: original.durationMs,
                    clientQuality: original.clientQuality
                )
            )
            let actual11 = await fake.callsFor("at-1")
            XCTAssertEqual(1, actual11)
            let actual12 = await manager.state(of: "at-1")
            XCTAssertEqual(transportFailed, actual12)

            await manager.retry("at-1")
            await awaitCall(fake, "at-1", count: 2)

            let actual13 = await fake.lastReceived?.wavBytes
            XCTAssertEqual(original.wavBytes, actual13)
        }
    }

    /// 안드로이드는 `wavBytes.copyOf()`로 스냅샷을 떴다. Swift `Data`는 값 타입이라 대입이 곧
    /// 스냅샷이고, 호출자가 자기 변수를 덮어써도 매니저가 든 바이트는 움직이지 않는다.
    func testenqueue_후_호출자가_바이트를_바꿔도_재전송_바이트는_스냅샷_그대로다() async {
        await withManager { fake, manager in
            var original = requestOf("at-1")
            let snapshot = original.wavBytes

            await manager.enqueue(original)
            await awaitCall(fake, "at-1")
            original = UploadRequest( // 호출자가 버퍼를 재사용하는 상황
                attemptId: original.attemptId,
                itemId: original.itemId,
                wavBytes: Data(repeating: 0x7F, count: 32),
                durationMs: original.durationMs,
                clientQuality: original.clientQuality
            )
            let actual14 = await fake.received.first?.wavBytes
            XCTAssertEqual(snapshot, actual14)

            await fake.respond("at-1", transportError())
            await awaitState(manager, "at-1", transportFailed)
            await manager.retry("at-1")
            await awaitCall(fake, "at-1", count: 2)

            let actual15 = await fake.lastReceived?.wavBytes
            XCTAssertEqual(snapshot, actual15)
        }
    }

    /// 안드로이드에는 "클라이언트가 예외를 던지면 InFlight로 남지 않는다"가 있다. Swift에서는
    /// ``UploadClient/upload(_:sessionId:sessionToken:)``이 `throws`가 아니라 그 경로 자체가
    /// 타입으로 막혀 있어, 같은 자리를 지키는 것은 "원인 불명 전송 실패도 재시도 쪽에 남는다"다.
    func test원인을_모르는_전송_실패도_InFlight로_고착되지_않고_재시도_가능한_Failed가_된다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            let actual16 = await manager.state(of: "at-1")
            XCTAssertEqual(.inFlight, actual16)

            await fake.respond("at-1", .transportError(failure: .unknown, reason: "unexpected boom"))

            // 오류 문구는 사용자가 읽을 말이 아니다. 원인 불명 전송 실패의 안내로 덮인다 (KAN-147 2단계).
            await awaitState(manager, "at-1", transportFailed)
        }
    }

    func test완료된_업로드와_모르는_키에_대한_retry는_아무_일도_하지_않는다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            await fake.respond("at-1", .accepted(analysisJobId: "aj_1"))
            await awaitState(manager, "at-1", .done(analysisJobId: "aj_1"))

            await manager.retry("at-1")
            await manager.retry("at-unknown")

            let actual17 = await fake.receivedCount
            XCTAssertEqual(1, actual17)
            let actual18 = await manager.state(of: "at-1")
            XCTAssertEqual(.done(analysisJobId: "aj_1"), actual18)
            let actual19 = await manager.state(of: "at-unknown")
            XCTAssertNil(actual19)
        }
    }

    // MARK: - 폐기 (FR-DP-02)

    func testdiscard한_Failed_건은_상태와_원본이_사라지고_같은_키로_다시_enqueue할_수_있다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            await fake.respond("at-1", transportError())
            await awaitState(manager, "at-1", transportFailed)

            await manager.discard("at-1")
            let actual20 = await manager.state(of: "at-1")
            XCTAssertNil(actual20)

            // 원본이 풀렸으므로 retry는 보낼 바이트가 없다.
            await manager.retry("at-1")
            let actual21 = await fake.callsFor("at-1")
            XCTAssertEqual(1, actual21)
            let actual22 = await manager.state(of: "at-1")
            XCTAssertNil(actual22)

            // 폐기는 시도 자체를 버리는 것이라 같은 키의 새 enqueue는 다시 받는다.
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1", count: 2)
            let actual23 = await manager.state(of: "at-1")
            XCTAssertEqual(.inFlight, actual23)
        }
    }

    func testdiscard는_진행_중_전송을_끊고_뒤늦은_응답도_상태를_되살리지_못한다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            let actual24 = await manager.state(of: "at-1")
            XCTAssertEqual(.inFlight, actual24)

            await manager.discard("at-1")
            let actual25 = await manager.state(of: "at-1")
            XCTAssertNil(actual25)

            await fake.respond("at-1", .accepted(analysisJobId: "aj_late"))
            // 늦은 완료가 상태를 되살릴 시간을 충분히 준 뒤에도 비어 있어야 한다.
            try? await Task.sleep(nanoseconds: 20_000_000)
            let actual26 = await manager.state(of: "at-1")
            XCTAssertNil(actual26)
            let actual27 = await manager.retainedOriginalKeys.isEmpty
            XCTAssertTrue(actual27)
        }
    }

    func testclearAll은_상태가_섞인_여러_건을_전부_지우고_진행_중_건도_되살아나지_않는다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-done"))
            await manager.enqueue(requestOf("at-failed", itemId: "item-2"))
            await manager.enqueue(requestOf("at-inflight", itemId: "item-3"))
            await awaitCall(fake, "at-done")
            await awaitCall(fake, "at-failed")
            await awaitCall(fake, "at-inflight")

            await fake.respond("at-done", .accepted(analysisJobId: "aj_1"))
            await fake.respond("at-failed", transportError())
            await awaitState(manager, "at-done", .done(analysisJobId: "aj_1"))
            await awaitState(manager, "at-failed", transportFailed)
            let actual28 = await manager.uploads.count
            XCTAssertEqual(3, actual28)

            await manager.clearAll()
            let actual29 = await manager.uploads.isEmpty
            XCTAssertTrue(actual29)

            await fake.respond("at-inflight", .accepted(analysisJobId: "aj_late"))
            try? await Task.sleep(nanoseconds: 20_000_000)
            let actual30 = await manager.uploads.isEmpty
            XCTAssertTrue(actual30)
        }
    }

    func testclearAll_후_새_enqueue는_정상_동작한다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")
            await manager.clearAll()

            await manager.enqueue(requestOf("at-2", itemId: "item-2"))
            await awaitCall(fake, "at-2")
            let actual31 = await manager.state(of: "at-2")
            XCTAssertEqual(.inFlight, actual31)

            await fake.respond("at-2", .accepted(analysisJobId: "aj_2"))
            await awaitState(manager, "at-2", .done(analysisJobId: "aj_2"))
            let actual32 = await manager.uploads.count
            XCTAssertEqual(1, actual32)
        }
    }

    func testdiscard_후_다시_enqueue하면_옛_전송의_늦은_완료가_새_시도의_원본을_지우지_못한다() async {
        await withManager { fake, manager in
            await fake.swallowCancellationFor("at-1")

            await manager.enqueue(requestOf("at-1"))
            await awaitCall(fake, "at-1")

            // 폐기 직후 같은 키로 새 시도를 연다. 옛 전송은 아직 취소 재개를 돌리지 않았다.
            await manager.discard("at-1")
            await manager.enqueue(requestOf("at-1"))
            let actual33 = await manager.state(of: "at-1")
            XCTAssertEqual(.inFlight, actual33)

            // 여기서 옛 전송이 Done을 들고 publish에 도달한다. 새 시도의 세대가 아니므로 버려져야 한다.
            await awaitCall(fake, "at-1", count: 2)
            try? await Task.sleep(nanoseconds: 20_000_000)
            let actual34 = await fake.callsFor("at-1")
            XCTAssertEqual(2, actual34)
            let actual35 = await manager.state(of: "at-1")
            XCTAssertEqual(.inFlight, actual35)

            // 새 시도의 원본이 남아 있어야 재시도가 같은 바이트를 다시 보낼 수 있다.
            await fake.respond("at-1", transportError())
            await awaitState(manager, "at-1", transportFailed)

            await manager.retry("at-1")
            await awaitCall(fake, "at-1", count: 3)
            let actual36 = await manager.state(of: "at-1")
            XCTAssertEqual(.inFlight, actual36)
        }
    }

    /**
     enqueue와 clearAll을 300라운드 정면 충돌시키고, **실제로 일어난 진입 순서를 관측해** 그
     순서에 맞는 불변식을 검사한다 (KAN-89).

     안드로이드는 실제 스레드 둘을 `CyclicBarrier`로 맞부딪쳤다. 여기서는 ``StartGate``가 그
     자리이고, 한 가지가 더 붙는다 — **깨운 순서는 actor 진입 순서가 아니다.** continuation
     재개 순서와 실행기가 실제로 집어 드는 순서는 다르므로, 게이트만 두고 "두 인터리빙을
     훑었다"고 말하면 증명이 아니라 희망이다. 그래서 매니저의 임계 구역 진입점에 관측 훅을 걸고
     회차마다 **누가 먼저 들어갔는지 읽어서** 단언한다.

     읽은 순서별 기대값:
     - `enqueue` 먼저 → 뒤이은 clearAll이 전부 지운다: tracked=[] retained=[]
     - `clearAll` 먼저 → 빈 상태를 지운 뒤 enqueue가 등록한다: tracked=[at-n]. 전송이
       ``ImmediateUploadClient``로 곧장 성공하므로 Done이 원본을 즉시 해제해 retained=[]다
       (FR-DP-02 — 성공한 업로드의 WAV는 더 쓸 일이 없다).

     어느 쪽이든 폐기 불변식(retained ⊆ tracked)은 깨지지 않아야 한다. enqueue의
     "상태 등록 → 원본 보관 → 전송 시작"이 원자적이지 않으면 폐기 이후에 시작된 업로드가
     originals에 WAV 바이트를 영구히 남긴다.
     */
    func testenqueue와_clearAll이_경합해도_폐기된_시도의_원본이_남지_않는다() async {
        let manager = UploadManager(client: ImmediateUploadClient(), sessionId: "sess-1", sessionToken: "token-1")
        let entryLog = EventLog()
        await manager.observeCriticalSections { entryLog.append($0) }

        var enqueueFirst = 0
        var clearAllFirst = 0
        let rounds = 300

        for round in 0..<rounds {
            let request = requestOf("at-\(round)")
            // 두 자식이 출발선에 다 선 뒤 함께 풀린다 (안드로이드 `CyclicBarrier(2)` 자리).
            // 푸는 순서는 회차마다 뒤집지만, 그것이 진입 순서를 정하지는 않는다 — 그래서 아래에서 관측한다.
            let gate = StartGate()
            await withTaskGroup(of: Void.self) { group in
                group.addTask {
                    await gate.arrive(as: 0)
                    await manager.enqueue(request)
                }
                group.addTask {
                    await gate.arrive(as: 1)
                    await manager.clearAll()
                }
                await gate.waitForAll()
                await gate.release(startingWith: round % 2)
            }

            let entries = entryLog.drain()
            XCTAssertEqual(["clearAll", "enqueue"].sorted(), entries.sorted(), "round=\(round) 두 임계 구역이 다 들어와야 한다")

            await waitUntil("round=\(round) 업로드가 InFlight에서 멈췄다") {
                await manager.uploads.values.allSatisfy { $0 != .inFlight }
            }

            let tracked = Set(await manager.uploads.keys)
            let retained = await manager.retainedOriginalKeys
            XCTAssertTrue(
                tracked.isSuperset(of: retained),
                "round=\(round) 폐기된 원본이 남았다: retained=\(retained) tracked=\(tracked)"
            )

            if entries.first == "enqueue" {
                enqueueFirst += 1
                XCTAssertEqual([], tracked, "round=\(round) enqueue가 먼저면 뒤이은 clearAll이 전부 지운다")
                XCTAssertEqual([], retained, "round=\(round) 폐기 뒤에 원본이 남으면 안 된다")
            } else {
                clearAllFirst += 1
                XCTAssertEqual([request.attemptId], tracked, "round=\(round) clearAll이 먼저면 enqueue한 건이 남는다")
                // 성공한 업로드의 WAV는 Done 시점에 해제된다 (FR-DP-02).
                let settled = await manager.state(of: request.attemptId)
                XCTAssertEqual(.done(analysisJobId: "aj-\(request.attemptId)"), settled)
                XCTAssertEqual([], retained, "round=\(round) Done은 원본을 즉시 놓는다")
            }

            await manager.clearAll()
            _ = entryLog.drain() // 정리 호출의 발자국은 다음 회차로 넘기지 않는다
        }

        // 게이트가 실제로 두 인터리빙을 만들어 냈는지가 이 테스트의 값어치다 — 한쪽만 일어났다면
        // 300라운드를 돌고도 검사한 것은 순서 하나뿐이다.
        //
        // 하한을 30%로 잡은 근거: 게이트가 회차마다 푸는 순서를 뒤집고 실제 진입도 그 순서를
        // 대체로 따라가, 측정하면 46~49% / 51~54%로 앉는다(6회 측정). 90회는 그 아래로 50회쯤
        // 여유가 있어, 편향이 진짜로 생겼을 때만 걸린다.
        let coverageFloor = rounds * 3 / 10
        let distribution = "enqueue 먼저 \(enqueueFirst)회 / clearAll 먼저 \(clearAllFirst)회 (총 \(rounds))"
        XCTAssertGreaterThanOrEqual(enqueueFirst, coverageFloor, "enqueue가 먼저 들어간 회차가 너무 적다 — \(distribution)")
        XCTAssertGreaterThanOrEqual(clearAllFirst, coverageFloor, "clearAll이 먼저 들어간 회차가 너무 적다 — \(distribution)")
        print("경합 진입 순서 분포: \(distribution)")
    }

    // MARK: - 라벨 (안드로이드에서는 UploadViewModel의 몫)

    func test업로드를_걸면_라벨을_함께_기억한다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at_1"), label: "1번 문항")
            await awaitCall(fake, "at_1")

            let actual37 = await manager.state(of: "at_1")
            XCTAssertEqual(.inFlight, actual37)
            let actual38 = await manager.labelOf("at_1")
            XCTAssertEqual("1번 문항", actual38)
        }
    }

    func test모르는_시도에는_기본_라벨을_준다_상태_바가_빈_칸을_그리지_않게() async {
        await withManager { _, manager in
            let actual39 = await manager.labelOf("at_unknown")
            XCTAssertEqual("문항", actual39)
        }
    }

    /// 라벨을 남겨두면 같은 키가 재사용될 때 옛 문항 번호가 따라붙는다.
    func testdiscard와_clearAll은_업로드와_라벨을_함께_지운다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at_1"), label: "1번 문항")
            await manager.enqueue(requestOf("at_2", itemId: "item_2"), label: "2번 문항")
            await awaitCall(fake, "at_1")
            await awaitCall(fake, "at_2")

            await manager.discard("at_1")
            let actual40 = await manager.state(of: "at_1")
            XCTAssertNil(actual40)
            let actual41 = await manager.labelOf("at_1")
            XCTAssertEqual("문항", actual41)
            // 남은 건은 그대로다 - 폐기는 지목한 시도 하나만 버린다.
            let actual42 = await manager.state(of: "at_2")
            XCTAssertEqual(.inFlight, actual42)
            let actual43 = await manager.labelOf("at_2")
            XCTAssertEqual("2번 문항", actual43)

            await manager.clearAll()
            let actual44 = await manager.labelOf("at_2")
            XCTAssertEqual("문항", actual44)
        }
    }

    /// 상태 바가 읽는 순서는 넣은 순서다 — 재시도는 자리를 바꾸지 않는다.
    func testentries는_넣은_순서를_지키고_재시도가_순서를_바꾸지_않는다() async {
        await withManager { fake, manager in
            await manager.enqueue(requestOf("at-1"))
            await manager.enqueue(requestOf("at-2", itemId: "item-2"))
            await awaitCall(fake, "at-1")
            await awaitCall(fake, "at-2")
            let actual45 = await manager.entries.map(\.attemptId)
            XCTAssertEqual(["at-1", "at-2"], actual45)

            await fake.respond("at-1", transportError())
            await awaitState(manager, "at-1", transportFailed)
            await manager.retry("at-1")

            let actual46 = await manager.entries.map(\.attemptId)
            XCTAssertEqual(["at-1", "at-2"], actual46)
        }
    }
}
