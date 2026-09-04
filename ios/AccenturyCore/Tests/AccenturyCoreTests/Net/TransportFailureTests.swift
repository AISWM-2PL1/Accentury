import XCTest
@testable import AccenturyCore

/// 오류 종류를 사용자 문구의 갈래로 옮기는 판정만 본다 (KAN-147 2단계).
/// 안드로이드 `net/TransportFailureTest.kt`의 이식본이고, 실제로 어떤 오류가 올라오는지는
/// ``URLSessionUploadClientTests``가 확인한다.
final class TransportFailureTests: XCTestCase {

    func test이름조차_못_찾으면_기기가_끊긴_것으로_본다() {
        XCTAssertEqual(.offline, TransportFailure.from(URLError(.cannotFindHost)))
        XCTAssertEqual(.offline, TransportFailure.from(URLError(.dnsLookupFailed)))
    }

    /// 안드로이드에서는 망이 끊긴 기기도 DNS부터 실패해 `UnknownHostException`으로 왔다.
    /// iOS는 그 상황을 시스템이 먼저 알아채 별도 코드로 준다 — 같은 갈래로 모은다.
    func test망에_안_붙어_있으면_같은_갈래로_모인다() {
        XCTAssertEqual(.offline, TransportFailure.from(URLError(.notConnectedToInternet)))
    }

    func test연결을_거절당하면_서버_쪽_문제로_본다() {
        XCTAssertEqual(.serverUnreachable, TransportFailure.from(URLError(.cannotConnectToHost)))
        XCTAssertEqual(.serverUnreachable, TransportFailure.from(URLError(.networkConnectionLost)))
    }

    func test소켓_타임아웃은_지연으로_본다() {
        XCTAssertEqual(.timeout, TransportFailure.from(URLError(.timedOut)))
    }

    /// 전체 호출 상한(KAN-146, `timeoutIntervalForResource`)이 끊을 때도 같은 코드다 —
    /// 안드로이드에서 OkHttp callTimeout과 소켓 타임아웃이 `InterruptedIOException` 하나로
    /// 모이던 자리와 짝이 맞는다.
    func test전체_호출_상한이_끊은_것도_지연으로_본다() {
        XCTAssertEqual(.timeout, TransportFailure.from(URLError(.timedOut, userInfo: [:])))
    }

    func test갈래에_없는_전송_오류는_원인을_짐작하지_않는다() {
        XCTAssertEqual(.unknown, TransportFailure.from(URLError(.badServerResponse)))
        XCTAssertEqual(.unknown, TransportFailure.from(URLError(.cancelled)))
        // URLError가 아닌 오류도 여기로 모인다 — 원인을 말할 근거가 더 없는 자리다.
        XCTAssertEqual(.unknown, TransportFailure.from(CaptureError("unexpected end of stream")))
    }

    /// 화면에 그대로 뜨는 말이라 테스트가 직접 적어 못 박는다 (KAN-147 2단계).
    func test갈래마다_사용자에게_할_말이_정해져_있다() {
        XCTAssertEqual("인터넷 연결을 확인해 주세요", TransportFailure.offline.userMessage)
        XCTAssertEqual("서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요", TransportFailure.serverUnreachable.userMessage)
        XCTAssertEqual("응답이 늦어요. 다시 시도해 주세요", TransportFailure.timeout.userMessage)
        XCTAssertEqual("전송에 실패했어요. 다시 시도해 주세요", TransportFailure.unknown.userMessage)
    }
}
