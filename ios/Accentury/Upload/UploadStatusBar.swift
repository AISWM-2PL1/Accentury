import AccenturyCore
import SwiftUI

/// 업로드 상태 바 (KAN-100, KAN-147). 안드로이드 `upload/UploadStatusBar.kt`의 이식본이다.
///
/// 성공(Done)은 조용히 넘어가고, 진행 중 개수와 실패 건의 복구 경로만 보여준다. 요약 판정은
/// Core ``AccenturyCore/summarize(_:)``가 하고 여기는 그리기만 한다 — 안드로이드는 그 함수가
/// 같은 파일에 있지만 화면 요소가 아니라 갈림이라 이쪽에서는 화면 밖 순수 계층에 있다.
///
/// **복구 경로는 [재시도] 하나다** (KAN-147). [테스트 종료] 버튼은 없다 — 이탈 UX는 KAN-39
/// 디자인 때 정한다. 재시도 불가 실패에는 버튼을 주지 않는다: 같은 바이트를 다시 보내도 결과가
/// 같으므로 버튼이 거짓말이 된다. 그 대신 서버가 준 문구가 행에 그대로 남는다.
///
/// 오버레이가 아니라 WebView **아래**에 놓인다 — 녹음 중에도 실패한 업로드의 재시도 통로가
/// 가려지지 않아야 한다. 보여줄 것이 없으면 자리 자체를 차지하지 않는다.
struct UploadStatusBar: View {

    let entries: [UploadEntry]
    let labelOf: (String) -> String
    let onRetry: (String) -> Void

    var body: some View {
        let summary = summarize(entries)
        if !summary.isEmpty {
            VStack(alignment: .leading, spacing: Papercut.space1) {
                if summary.inFlight > 0 {
                    Text("업로드 중 \(summary.inFlight)건")
                        .papercutType(.label)
                        .foregroundColor(Papercut.ink)
                }

                ForEach(summary.failed, id: \.attemptId) { row in
                    HStack(spacing: Papercut.space2) {
                        Text("\(labelOf(row.attemptId)) 업로드 실패 — \(row.failure.message ?? "알 수 없는 오류")")
                            .papercutType(.caption)
                            .foregroundColor(Papercut.ink)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if row.failure.retryable {
                            AccenturyButton(text: "재시도", variant: .secondary) { onRetry(row.attemptId) }
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Papercut.space4)
            .padding(.vertical, Papercut.space2)
            .background(Papercut.cream)
            // 위 화면(WebView·녹음 오버레이)과 이 바를 가르는 줄. 상자 테두리보다 얇고 흐리다.
            .overlay(alignment: .top) {
                Papercut.muted.frame(height: Papercut.borderHairline)
            }
        }
    }
}
