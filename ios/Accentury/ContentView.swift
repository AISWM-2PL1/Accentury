import AccenturyCore
import SwiftUI
#if DEBUG
import os
#endif

/// KAN-108 §1의 임시 화면. 빌드 구성이 정한 주소가 실제로 앱까지 흘러왔는지를 눈으로 확인하는 용도다 —
/// 시뮬레이터 스크린샷 한 장으로 Debug/Release 구분이 되도록 값을 그대로 찍는다.
/// §5에서 WKWebView 호스트로 통째로 갈아 끼운다.
struct ContentView: View {

    #if DEBUG
    @StateObject private var smoke = RecordingSmokeModel()

    // TODO(KAN-108 §5): 제거 — 게이트를 여는 것은 웹의 `requestMicPermission`이 된다.
    @State private var showsPermissionGate = false
    @State private var permissionResult = ""
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
            Divider()
            permissionSmokeSection
            #endif

            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(24)
        #if DEBUG
        .task { await smoke.runAutoSmokeIfRequested() }
        // `-AutoPermissionSmoke 1`: 시뮬레이터에는 탭을 넣을 방법이 없어서(`xcrun simctl`에
        // 좌표 입력이 없다) 버튼 말고 실행 인자로도 게이트를 열 수 있게 둔다.
        .task { showsPermissionGate = UserDefaults.standard.bool(forKey: "AutoPermissionSmoke") }
        .fullScreenCover(isPresented: $showsPermissionGate) {
            PermissionGateView(
                onGranted: {
                    permissionResult = "게이트 통과 - 마이크 허용됨"
                    showsPermissionGate = false
                },
                onStateChange: Self.reportPermissionState
            )
            // 권한 없이 게이트를 벗어나는 길은 없다 (FR-AD-01, 2026-07-27 확정).
            // `fullScreenCover`는 기본적으로 아래로 쓸어 닫을 수 없지만, 계약을 코드로 적어
            // 둔다 — 나중에 sheet로 바꾸는 순간 조용히 열리는 종류의 구멍이다.
            .interactiveDismissDisabled(true)
        }
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

    /// TODO(KAN-108 §5): 제거 — 권한 게이트는 웹의 `requestMicPermission`이 연다
    /// (`docs/wiki/webview-layer.md` §8). 그때까지 게이트 화면과 상태 저장·복원이 실기기·
    /// 시뮬레이터에서 실제로 도는지 눈과 로그로 확인하는 임시 배선이다.
    private var permissionSmokeSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button("권한 게이트 테스트") { showsPermissionGate = true }
                .buttonStyle(.borderedProminent)
            if !permissionResult.isEmpty {
                Text(permissionResult)
                    .font(.system(.footnote, design: .monospaced))
            }
        }
    }

    /// 게이트 상태가 바뀔 때마다 한 줄 찍는다. `state`는 저장 키(안드로이드와 같은 문자열),
    /// `real`은 OS가 말하는 실제 권한이라 둘이 어긋나는 순간이 로그에 그대로 남는다.
    private static func reportPermissionState(_ state: MicPermissionState) {
        let status = MicPermission.currentStatus()
        let real = status.granted ? "granted" : (status.undetermined ? "undetermined" : "denied")
        let line = "PERM: state=\(state.saveKey) real=\(real)"
        print(line)
        Logger(subsystem: "com.accentury.app", category: "permission").info("\(line, privacy: .public)")
    }
    #endif
}

#Preview {
    ContentView()
}
