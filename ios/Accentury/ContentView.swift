import AccenturyCore
import SwiftUI

/// KAN-108 §1의 임시 화면. 빌드 구성이 정한 주소가 실제로 앱까지 흘러왔는지를 눈으로 확인하는 용도다 —
/// 시뮬레이터 스크린샷 한 장으로 Debug/Release 구분이 되도록 값을 그대로 찍는다.
/// §5에서 WKWebView 호스트로 통째로 갈아 끼운다.
struct ContentView: View {
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

            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(24)
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
}

#Preview {
    ContentView()
}
