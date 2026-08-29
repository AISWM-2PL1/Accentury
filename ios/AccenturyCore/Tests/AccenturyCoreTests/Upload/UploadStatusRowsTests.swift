import XCTest
@testable import AccenturyCore

/// 안드로이드 `upload/UploadStatusBarTest.kt`의 이식본 (3개). SwiftUI 바는 §6b 몫이고
/// 여기서는 순수한 표시 로직만 본다.
final class UploadStatusRowsTests: XCTestCase {

    func test진행_중은_세고_완료는_표시_대상에서_빠진다() {
        let summary = summarize([
            UploadEntry(attemptId: "at-1", state: .inFlight),
            UploadEntry(attemptId: "at-2", state: .done(analysisJobId: "aj_2")),
            UploadEntry(attemptId: "at-3", state: .inFlight),
        ])

        XCTAssertEqual(2, summary.inFlight)
        XCTAssertTrue(summary.failed.isEmpty)
    }

    func test실패는_재시도_가능_여부와_함께_넣은_순서대로_나온다() {
        let summary = summarize([
            UploadEntry(attemptId: "at-1", state: .failed(retryable: true, message: "timeout")),
            UploadEntry(attemptId: "at-2", state: .done(analysisJobId: "aj_2")),
            UploadEntry(attemptId: "at-3", state: .failed(retryable: false, message: "파일이 너무 큽니다")),
        ])

        XCTAssertEqual(0, summary.inFlight)
        XCTAssertEqual(["at-1", "at-3"], summary.failed.map(\.attemptId))
        XCTAssertEqual([true, false], summary.failed.map(\.failure.retryable))
        XCTAssertEqual("timeout", summary.failed.first?.failure.message)
    }

    func test업로드가_없으면_보여줄_것도_없다() {
        let summary = summarize([])

        XCTAssertEqual(0, summary.inFlight)
        XCTAssertTrue(summary.failed.isEmpty)
        XCTAssertTrue(summary.isEmpty)
    }
}
