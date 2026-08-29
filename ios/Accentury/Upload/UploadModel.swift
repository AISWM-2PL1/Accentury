import AccenturyCore
import Combine
import Foundation
import UIKit

/// 진행 중인 업로드와 재시도 상태의 주인 (KAN-100). 안드로이드 `upload/UploadViewModel.kt`의
/// 자리다 — 수명(등록·전송·실패·재시도·폐기)은 Core ``AccenturyCore/UploadManager``(actor)가
/// 전부 갖고, 여기는 그 상태를 SwiftUI가 볼 수 있는 `@Published`로 옮기고 세션이 바뀔 때
/// 매니저를 갈아끼운다.
///
/// ## 세션마다 다른 매니저다
///
/// 업로드가 어느 세션으로 나가는지는 매니저를 만드는 순간 정해진다(생성자가 sessionId·토큰을
/// 받는다). 인트로 시점에는 그 값이 없고, 재응시하면 세션이 통째로 바뀐다 — 같은 인스턴스에
/// 새 세션을 덮어씌우면 이미 나간 멱등 키가 다른 세션으로 재시도된다. 그래서 ``bind(to:)``가
/// sessionId를 보고 갈아끼우고, 갈아끼울 때 옛 매니저의 바이트를 전부 폐기한다 (FR-DP-02).
///
/// ## 프로세스 사망은 전부 폐기다
///
/// PCM을 디스크에 남기지 않는 것이 FR-DP-02이고, 그 경로에서 홀로 복원된 대기 시도는
/// ``AccenturyCore/TestFlowController/pruneAttemptsWithoutUpload(_:)``가 걷어낸다. 그래서 이
/// 클래스에는 저장이 없다.
///
/// ## 백그라운드 생존 (안드로이드와 갈리는 지점)
///
/// 안드로이드는 `UploadViewModel`이 Activity 재생성을 넘겨 살아남는 것으로 충분했다 — 앱이
/// 뒤로 가도 프로세스가 살아 있는 한 코루틴은 계속 돈다. iOS는 다르다: 앱이 백그라운드로 가면
/// 몇 초 안에 실행이 정지되고, 진행 중이던 `URLSession` 작업도 함께 멈춘다. 사용자가 [다음]을
/// 누르고 화면을 끄는 것은 흔한 일이라 그 구간을 그냥 두면 업로드가 조용히 실패한다.
///
/// 그래서 진행 중인 업로드가 있는 동안 `beginBackgroundTask`로 실행 시간을 빌린다.
/// **상한은 약 30초다** (iOS가 주는 시간이고 보장값이 아니다). 그 안에 못 끝내면 만료 핸들러가
/// 시간을 반납하고 업로드는 전송 실패로 떨어지는데, 그건 이미 있는 복구 경로다 — 상태 바의
/// [재시도]가 받는다 (KAN-147).
///
/// `URLSessionConfiguration.background`를 쓰지 않는 이유: 그쪽은 본문을 **파일**로 요구한다.
/// 메모리에서 조립한 멀티파트를 디스크에 쓰는 순간 녹음 바이트가 파일로 남고(FR-DP-02 위반)
/// 그 파일의 수명은 우리가 아니라 시스템 데몬이 쥔다. 30초 안에 끝나는 320KB 업로드를 위해
/// 치를 대가가 아니다.
@MainActor
final class UploadModel: ObservableObject {

    /// 넣은 순서대로의 업로드 상태. 상태 바(``AccenturyCore/summarize(_:)``)가 그대로 받는 모양이다.
    @Published private(set) var entries: [UploadEntry] = []

    /// 순서가 필요 없는 조회용. 안드로이드 `uploads.value` 자리이고
    /// ``AccenturyCore/TestFlowController/onUploadsChanged(_:)``가 이 모양을 받는다.
    private(set) var uploads: [String: UploadState] = [:]

    private let makeClient: (Session) -> UploadClient

    private var manager: UploadManager?
    private var boundSessionId: String?
    /// 상태 구독 Task. `deinit`은 격리 밖에서 도는 자리라 `@MainActor` 저장 프로퍼티를 만질 수
    /// 없어서, 취소 통로만 격리 없는 상자에 담아 둔다.
    private let streamTask = CancellableTaskBox()
    /// 실패 표시에 쓸 문항 라벨. 매니저도 들고 있지만 그쪽은 actor라 화면이 동기로 못 읽는다.
    private var labels: [String: String] = [:]
    /// 빌린 실행 시간. 식별자를 `@MainActor` 프로퍼티가 아니라 격리 없는 상자에 두는 이유는
    /// `deinit`이다 — 거기서도 반드시 반납해야 하는데 격리된 값은 만질 수 없다.
    private let backgroundTime: BackgroundTimeHolder

    /// - Parameters:
    ///   - makeClient: 세션 하나에 붙일 업로드 클라이언트. 기본값이 실제 `URLSession`이고,
    ///     테스트는 서버 없이 상태 흐름만 보도록 가짜를 끼운다.
    ///   - beginBackgroundTask: 실행 시간을 빌린다. 인자는 만료 핸들러다.
    ///     `UIApplication`을 클로저 뒤에 두는 이유도 테스트다 — 유닛 테스트가 앱 생명주기를
    ///     건드리면 다른 테스트의 실행 시간까지 흔든다.
    init(
        makeClient: @escaping (Session) -> UploadClient = { _ in URLSessionUploadClient(baseURL: AppConfig.apiBaseURL) },
        beginBackgroundTask: @escaping (@escaping () -> Void) -> UIBackgroundTaskIdentifier = { expiry in
            UIApplication.shared.beginBackgroundTask(withName: "accentury.upload", expirationHandler: expiry)
        },
        endBackgroundTask: @escaping (UIBackgroundTaskIdentifier) -> Void = { id in
            UIApplication.shared.endBackgroundTask(id)
        }
    ) {
        self.makeClient = makeClient
        backgroundTime = BackgroundTimeHolder(begin: beginBackgroundTask, end: endBackgroundTask)
    }

    deinit {
        // 구독을 끊지 않으면 매니저를 붙잡은 Task가 영영 남는다 (self는 이미 없다).
        streamTask.cancel()
        /*
         * 빌린 실행 시간도 반드시 여기서 반납한다. 안 하면 iOS가 **앱을 죽인다** — 빌린 시간을
         * 제때 안 놓는 것은 크래시 사유고, 소유자가 사라진 식별자는 다른 누구도 반납할 수 없다.
         *
         * 정상 경로(``teardown()``·업로드 완료)가 이미 놓았으면 상자가 걸러 아무 일도 하지
         * 않는다 — 반납은 정확히 한 번이다.
         */
        backgroundTime.release()
    }

    // MARK: - 세션 결선

    /// 이 세션의 업로드 매니저로 갈아낀다. 같은 세션이면 아무 일도 하지 않는다 —
    /// 화면이 다시 그려질 때마다 불려도 올라가던 음성과 재시도 통로가 그대로 살아 있어야 한다.
    ///
    /// nil을 주면(인트로로 돌아간 경우) 매니저를 내리고 남은 바이트를 전부 폐기한다.
    func bind(to session: Session?) {
        guard boundSessionId != session?.sessionId else { return }

        releaseCurrentSession()

        boundSessionId = session?.sessionId
        guard let session else { return }

        let manager = UploadManager(
            client: makeClient(session),
            sessionId: session.sessionId,
            sessionToken: session.sessionToken
        )
        self.manager = manager
        // 안드로이드 `StateFlow` 구독 자리. 구독하는 순간 현재 값을 한 번 흘리고 이후 변화를 잇는다.
        streamTask.replace(with: Task { [weak self] in
            for await entries in await manager.stateChanges() {
                if Task.isCancelled { return }
                self?.apply(entries)
            }
        })
    }

    /// 이 모델을 끝낸다 — 구독을 끊고, 남은 음성 바이트를 폐기하고, 빌린 실행 시간을 반납한다.
    ///
    /// 세션 교체(``bind(to:)``)와 같은 정리를 하되 **다음 세션을 잇지 않는다**는 점만 다르다.
    /// 부르는 자리는 흐름이 끝나는 지점(`TestFlowView`의 `onDisappear`)이고, 빠뜨려도
    /// `deinit`이 같은 정리를 한 번 더 시도한다 — 다만 `deinit`이 언제 도는지는 SwiftUI가
    /// 정하므로, 빌린 실행 시간처럼 늦게 반납하면 앱이 죽는 자원은 여기서 명시적으로 놓는다.
    ///
    /// 여러 번 불려도 안전하다.
    func teardown() {
        releaseCurrentSession()
        boundSessionId = nil
    }

    /// 지금 붙어 있는 세션의 것을 전부 놓는다. ``bind(to:)``와 ``teardown()``이 나눠 쓴다 —
    /// 둘이 각자 정리하면 한쪽에만 새 자원이 빠지는 날이 온다.
    private func releaseCurrentSession() {
        // 옛 매니저의 음성 바이트를 확정 폐기한 뒤 놓는다 (FR-DP-02). 참조만 놓으면
        // register~start 사이의 시도가 원본을 든 채로 남을 수 있다.
        if let previous = manager {
            Task { await previous.clearAll() }
        }
        streamTask.cancel()
        manager = nil
        labels.removeAll()
        // 빈 목록을 반영하면 "진행 중 0건"이 되어 빌린 실행 시간도 함께 반납된다.
        apply([])
    }

    // MARK: - 변이

    /// 업로드를 걸면서 실패 표시에 쓸 문항 라벨을 함께 기억한다.
    func enqueue(_ request: UploadRequest, label: String) {
        guard let manager else { return }
        labels[request.attemptId] = label
        Task { await manager.enqueue(request, label: label) }
    }

    /// 실패한 전송을 같은 멱등 키와 같은 바이트로 다시 보낸다. 횟수 제한은 없다 (KAN-147).
    func retry(_ attemptId: String) {
        guard let manager else { return }
        Task { await manager.retry(attemptId) }
    }

    /// 결과가 나올 일이 없어진 시도 하나를 폐기한다 (KAN-147) — 재녹음 전환(rerecord)이 확정된
    /// 업로드와, 같은 문항의 새 녹음에 밀려난 앞 시도가 여기로 온다. 라벨도 함께 지운다:
    /// 상태 바에서 사라진 업로드의 라벨은 쓸 곳이 없고, 남겨두면 같은 키가 재사용될 때 옛 문항
    /// 번호가 따라붙는다.
    func discard(_ attemptId: String) {
        guard let manager else { return }
        labels.removeValue(forKey: attemptId)
        Task { await manager.discard(attemptId) }
    }

    /// `UploadState`는 itemId를 들고 있지 않아 실패 표시에 쓸 문항 라벨을 모른다.
    func labelOf(_ attemptId: String) -> String {
        labels[attemptId] ?? UploadManager.defaultLabel
    }

    // MARK: - 반영

    private func apply(_ next: [UploadEntry]) {
        entries = next
        uploads = Dictionary(uniqueKeysWithValues: next.map { ($0.attemptId, $0.state) })
        syncBackgroundTask(inFlight: next.contains { $0.state == .inFlight })
    }

    /// 진행 중인 업로드가 있는 동안만 실행 시간을 빌린다. 빌린 시간을 제때 반납하지 않으면
    /// iOS가 앱을 죽인다 — 그래서 마지막 업로드가 끝나는 순간이 반납 자리다.
    private func syncBackgroundTask(inFlight: Bool) {
        if inFlight {
            backgroundTime.acquire()
        } else {
            backgroundTime.release()
        }
    }
}

/// 빌린 백그라운드 실행 시간 한 건. 식별자를 쥐고 **반납이 정확히 한 번만** 나가게 한다.
///
/// 이 타입이 따로 있는 이유는 반납을 부르는 자리가 셋이기 때문이다 — 마지막 업로드가 끝날 때,
/// ``UploadModel/teardown()``, 그리고 `deinit`. 여기에 iOS가 부르는 만료 핸들러까지 더하면 넷이고,
/// 넷이 겹칠 수 있다. `endBackgroundTask`를 두 번 부르는 것은 잘못된 API 사용이고(같은 식별자를
/// 두 번 놓는다) 아예 안 부르면 앱이 강제 종료되므로, 식별자를 락 뒤에 두고 **꺼내 가는 쪽이
/// 하나뿐이게** 만든다.
///
/// `@MainActor`가 아닌 이유도 `deinit` 때문이다 — 거기서는 격리된 값을 만질 수 없다. 대신
/// `UIApplication` 호출은 메인 스레드에서만 해야 하므로, 이미 메인이면 곧바로 부르고 아니면
/// 꺼낸 식별자를 캡처해 메인 큐로 넘긴다.
private final class BackgroundTimeHolder: @unchecked Sendable {

    private let lock = NSLock()
    private var identifier: UIBackgroundTaskIdentifier = .invalid

    private let begin: (@escaping () -> Void) -> UIBackgroundTaskIdentifier
    private let end: (UIBackgroundTaskIdentifier) -> Void

    init(
        begin: @escaping (@escaping () -> Void) -> UIBackgroundTaskIdentifier,
        end: @escaping (UIBackgroundTaskIdentifier) -> Void
    ) {
        self.begin = begin
        self.end = end
    }

    /// 아직 안 빌렸으면 빌린다. 이미 빌린 상태면 아무 일도 하지 않는다 — 업로드 한 건이 끝날
    /// 때마다 새로 빌리면 식별자가 쌓이고, 그중 하나만 반납되면 나머지가 새는 것과 같다.
    func acquire() {
        lock.lock()
        let alreadyHeld = identifier != .invalid
        lock.unlock()
        if alreadyHeld { return }

        /*
         * 만료 핸들러는 iOS가 시간이 다 됐을 때 부른다. 여기서 반납하지 않으면 앱이 강제
         * 종료된다 — 끊긴 업로드는 전송 실패로 떨어져 상태 바의 [재시도]가 받는다 (KAN-147).
         * 아래 `release()`가 멱등이라 정상 종료와 겹쳐도 반납은 한 번이다.
         */
        let granted = begin { [weak self] in self?.release() }

        lock.lock()
        if identifier == .invalid {
            identifier = granted
            lock.unlock()
        } else {
            // 그 사이 다른 호출이 먼저 빌렸다. 방금 받은 것은 곧바로 놓는다 — 안 놓으면 그게 샌다.
            lock.unlock()
            callEnd(granted)
        }
    }

    /// 빌린 시간이 있으면 반납한다. 여러 번 불려도, 여러 스레드에서 불려도 실제 반납은 한 번이다.
    func release() {
        lock.lock()
        let held = identifier
        identifier = .invalid
        lock.unlock()

        guard held != .invalid else { return }
        callEnd(held)
    }

    private func callEnd(_ id: UIBackgroundTaskIdentifier) {
        if Thread.isMainThread {
            end(id)
        } else {
            // `deinit`은 어느 스레드에서든 돌 수 있다. 식별자를 값으로 캡처해 메인으로 넘긴다 —
            // 이 시점에 소유자(UploadModel)는 이미 없어도 반납은 끝까지 간다.
            DispatchQueue.main.async { [end] in end(id) }
        }
    }
}

/// 취소만 노출하는 Task 상자. `@MainActor` 클래스의 `deinit`은 격리 밖이라 격리된 저장
/// 프로퍼티를 만질 수 없는데, 구독 Task는 거기서 반드시 끊어야 한다 — 안 끊으면 매니저를
/// 붙잡은 Task가 소유자보다 오래 산다.
private final class CancellableTaskBox: @unchecked Sendable {

    private let lock = NSLock()
    private var task: Task<Void, Never>?

    func replace(with next: Task<Void, Never>?) {
        lock.lock()
        let previous = task
        task = next
        lock.unlock()
        previous?.cancel()
    }

    func cancel() { replace(with: nil) }
}
