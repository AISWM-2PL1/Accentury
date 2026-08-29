import Foundation

/// 두 Task를 같은 순간에 풀어 주는 출발 게이트. 안드로이드 테스트의 `CyclicBarrier(2)` 자리다.
///
/// 게이트가 없으면 `TaskGroup`의 두 자식이 겹치지 않을 수 있다 — 먼저 시작한 쪽이 일을 다 끝낸
/// 뒤에야 다음이 출발하면, 경합을 재현하겠다고 만든 테스트가 사실은 순차 실행만 확인한다.
///
/// **왜 "도착하면 곧바로 함께 재개"가 아닌가.** 마지막 도착자의 continuation을 그 자리에서
/// 재개하면 그 Task는 중단 없이 자기 스레드를 쥔 채 계속 달리고, 나머지는 실행기 큐에 들어간다 —
/// 결국 **마지막 도착자가 언제나 먼저** 목적지에 닿아 순서가 한쪽으로 고정된다(실제로 그렇게
/// 만들었더니 회귀를 못 잡았다). 그래서 셋으로 나눈다:
///
/// 1. 참가자들이 ``arrive(as:)``로 **전부 파킹**한다 — 아무도 스레드를 쥐고 있지 않다.
/// 2. 바깥(테스트)이 ``waitForAll()``로 그 상태를 확인하고,
/// 3. ``release(startingWith:)``가 지정한 순서로 한꺼번에 깨운다.
///
/// 순서를 인자로 받는 이유는 회차마다 뒤집기 위해서다. 스케줄러의 기분에 기대는 대신
/// **두 인터리빙을 모두 반드시 훑는다**.
///
/// 한 회차용이다. 라운드마다 새로 만든다 — 재사용하는 순환 장벽은 앞 회차의 늦은 도착이
/// 다음 회차를 잘못 열 수 있어서, 그 위험을 값의 수명으로 없앤다.
actor StartGate {

    private let parties: Int
    private var parked: [Int: CheckedContinuation<Void, Never>] = [:]
    private var arrivalWaiter: CheckedContinuation<Void, Never>?

    init(parties: Int = 2) {
        self.parties = parties
    }

    /// 출발선에 서서 ``release(startingWith:)``를 기다린다.
    func arrive(as id: Int) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            parked[id] = continuation
            if parked.count == parties, let waiter = arrivalWaiter {
                arrivalWaiter = nil
                waiter.resume()
            }
        }
    }

    /// 참가자가 전부 파킹될 때까지 기다린다.
    func waitForAll() async {
        if parked.count == parties { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            arrivalWaiter = continuation
        }
    }

    /// 지정한 참가자부터 순서대로 한꺼번에 깨운다. 둘 다 파킹돼 있어 어느 쪽도
    /// "이미 스레드를 쥔" 이점이 없고, 먼저 깨운 쪽이 먼저 달릴 뿐이다.
    func release(startingWith first: Int) {
        let order = (0..<parties).map { (first + $0) % parties }
        for continuation in order.compactMap({ parked.removeValue(forKey: $0) }) {
            continuation.resume()
        }
    }
}
