import AccenturyCore
import UIKit
import XCTest
@testable import Accentury

/// 녹음 파일 수명 (KAN-108 §5.5, FR-DP-02). **녹음은 디스크에 닿지 않는다.**
///
/// 규칙을 문장으로만 두면 리팩터링 한 번에 깨진다 — 실제로 깨졌던 자리도 있다(§3의
/// 스모크 화면이 파형을 눈으로 보려고 임시 폴더에 `last.wav`를 남겼다). 그래서 녹음 한 벌이
/// 앱을 통째로 지나가는 경로를 태우고, 지나간 자리에 파일이 남지 않는지를 파일 시스템에 직접
/// 묻는다. "코드에 `write(to:)`가 없다"가 아니라 "파일이 없다"를 단언하는 것이 요점이다 —
/// 우리가 안 쓰더라도 어딘가에서 쓰면 이 테스트가 잡는다.
///
/// 바이트가 사는 곳은 둘뿐이다: 녹음이 끝난 직후의 ``RecordingModel``(한 번 꺼내면 사라진다)과
/// 업로드가 끝나거나 폐기될 때까지의 ``AccenturyCore/UploadManager``(메모리).
@MainActor
final class RecordingFileLifecycleTests: XCTestCase {

    /// 녹음 → 등록 → 폐기를 한 바퀴 돌고 나면 앱이 쓰는 세 폴더 어디에도 WAV가 없다.
    func testAFullRecordEnqueueDiscardCycleLeavesNoWavOnDisk() async {
        let client = FakeUploadClient()
        let uploads = UploadModel(
            makeClient: { _ in client },
            beginBackgroundTask: { _ in UIBackgroundTaskIdentifier(rawValue: 1) },
            endBackgroundTask: { _ in }
        )
        uploads.bind(
            to: Session(
                sessionId: "s_1",
                sessionToken: "st_1",
                testVersion: "gn-2026.08.1",
                scoreVersion: "sv-1",
                expiresAt: "2099-01-01T00:00:00Z"
            )
        )

        let source = SpyPcmSource()
        let recording = RecordingModel(engine: RecordingEngine(source: source))
        recording.start()
        await RecordingModelTests.waitForAudio(recording)
        recording.stop()
        let review = await waitForReview(recording)

        // 화면이 [다음]에서 하는 일 그대로다 — PCM을 꺼내 WAV로 접고 업로드에 넘긴다.
        let pcm = recording.consumeRecording()
        XCTAssertNotNil(pcm, "녹음이 끝났는데 꺼낼 PCM이 없다")
        uploads.enqueue(
            UploadRequest(
                attemptId: review.attemptId,
                itemId: "it_1",
                wavBytes: WavWriter.toWavBytes(pcm ?? []),
                durationMs: review.durationMs,
                clientQuality: AudioQuality.measure(pcm ?? [])
            ),
            label: "1번 문항"
        )
        await waitUntil { uploads.uploads[review.attemptId] == .inFlight }

        // 재녹음 전환·밀려남에서 도는 경로 (KAN-147). 바이트가 사라지는 자리가 여기다.
        uploads.discard(review.attemptId)
        await waitUntil("폐기가 반영되지 않았다") { uploads.entries.isEmpty }
        recording.reset()

        let leftovers = Self.wavFilesInAppDirectories()
        XCTAssertTrue(
            leftovers.isEmpty,
            "녹음이 디스크에 남았다 (FR-DP-02 위반): \(leftovers.map(\.path).joined(separator: ", "))"
        )
    }

    /// PCM은 **한 번만** 꺼낼 수 있다. 두 번째는 nil이다 — 업로드가 가져간 바이트를 화면이
    /// 계속 쥐고 있으면, 그 화면이 사라지지 않는 한 음성이 메모리에 남는다.
    func testConsumeRecordingHandsThePcmOverExactlyOnce() async {
        let recording = RecordingModel(engine: RecordingEngine(source: SpyPcmSource()))
        recording.start()
        await RecordingModelTests.waitForAudio(recording)
        recording.stop()
        _ = await waitForReview(recording)

        XCTAssertNotNil(recording.consumeRecording(), "첫 호출은 방금 녹음한 PCM이어야 한다")
        XCTAssertNil(recording.consumeRecording(), "두 번째 호출까지 PCM을 돌려주면 안 된다")

        recording.reset()
    }

    // MARK: - 도구

    private func waitForReview(_ model: RecordingModel) async -> RecordingUiState.Review {
        await waitUntil("정지를 눌렀는데 검토 화면으로 넘어가지 않았다") {
            if case .review = model.uiState { return true }
            return false
        }
        guard case .review(let review) = model.uiState else {
            XCTFail("검토 상태가 아니다: \(model.uiState)")
            return RecordingUiState.Review(
                attemptId: "",
                durationMs: 0,
                quality: .tooShort,
                autoStopped: false
            )
        }
        return review
    }

    /// 앱이 쓰는 세 폴더를 재귀로 훑어 WAV를 모은다.
    ///
    /// 임시 폴더·캐시·문서 셋을 다 보는 이유: 이 규칙을 어기는 코드는 "잠깐만 쓰고 지우려고"
    /// 임시 폴더를 고르지만, 지우는 쪽이 빠지면 남는다. 캐시와 문서는 백업·동기화에 실려 나가
    /// 더 오래 산다.
    private static func wavFilesInAppDirectories() -> [URL] {
        let manager = FileManager.default
        var roots = [manager.temporaryDirectory]
        roots += manager.urls(for: .cachesDirectory, in: .userDomainMask)
        roots += manager.urls(for: .documentDirectory, in: .userDomainMask)

        var found: [URL] = []
        for root in roots {
            guard let walker = manager.enumerator(at: root, includingPropertiesForKeys: nil) else { continue }
            for case let url as URL in walker where url.pathExtension.lowercased() == "wav" {
                found.append(url)
            }
        }
        return found
    }
}
