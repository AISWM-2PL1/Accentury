import AccenturyCore
import SwiftUI

/// KAN-108 §1의 임시 화면. 빌드 구성이 정한 주소가 실제로 앱까지 흘러왔는지를 눈으로 확인하는 용도다 —
/// 시뮬레이터 스크린샷 한 장으로 Debug/Release 구분이 되도록 값을 그대로 찍는다.
/// §5에서 WKWebView 호스트로 통째로 갈아 끼운다.
struct ContentView: View {

    #if DEBUG
    @StateObject private var smoke = RecordingSmokeModel()
    #endif

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("accentury")
                .font(.largeTitle.bold())

            configRow(label: "WEB_URL", value: AppConfig.webURL)
            configRow(label: "API_BASE_URL", value: AppConfig.apiBaseURL)
            configRow(label: "app", value: AppConfig.appVersionName)
            configRow(label: "bridge", value: String(bridgeContractVersion))

            Text("KAN-108 §1 · WKWebView 호스트는 §5에서 붙는다")
                .font(.footnote)
                .foregroundStyle(.secondary)

            #if DEBUG
            // TODO(KAN-108 §5): 제거 — WKWebView 호스트가 들어오면 녹음은 웹 화면이 시작한다.
            // 여기 있는 동안의 역할은 "캡처 → 프레이밍 → YIN → WAV"가 실기기·시뮬레이터에서
            // 실제로 도는지 눈과 로그로 한 번 확인하는 것뿐이다.
            Divider()
            recordingSmokeSection
            #endif

            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(24)
        #if DEBUG
        .task { await smoke.runAutoSmokeIfRequested() }
        #endif
    }

    private func configRow(label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(.body, design: .monospaced))
                .textSelection(.enabled)
        }
    }

    #if DEBUG
    private var recordingSmokeSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button(smoke.isRecording ? "정지" : "녹음 테스트") {
                smoke.toggle()
            }
            .buttonStyle(.borderedProminent)
            .disabled(smoke.isBusy)

            Text(smoke.status)
                .font(.callout)
            Text("경과 \(smoke.elapsedMs)ms · rms \(String(format: "%.0f", smoke.rms)) · f0 \(smoke.lastF0.map { String(format: "%.1fHz", $0) } ?? "무성")")
                .font(.system(.footnote, design: .monospaced))
            if !smoke.result.isEmpty {
                Text(smoke.result)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
            }
        }
    }
    #endif
}

#Preview {
    ContentView()
}
