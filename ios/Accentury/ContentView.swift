import AccenturyCore
import SwiftUI
#if DEBUG
import os
#endif

/// 앱의 첫 화면. 안드로이드 `MainActivity.onCreate`가 `TestFlow()`를 세우는 자리다.
///
/// KAN-108 §5부터 기본 화면은 ``TestFlowView``(WKWebView 호스트 + 브리지)다. §1~§4에서
/// 임시로 세워 뒀던 설정값 표시와 스모크 버튼은 실행 인자 `-DebugSmokeMenu 1`로만 열린다 —
/// 지우지 않은 이유는 캡처·권한 경로를 시뮬레이터에서 손으로 다시 확인할 일이 6단계까지
/// 남아 있기 때문이고, 기본 화면에서 걷어낸 이유는 그 화면이 더는 앱의 첫 화면이 아니기 때문이다.
struct ContentView: View {

    var body: some View {
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "DebugSmokeMenu") {
            DebugSmokeMenu()
        } else {
            TestFlowView()
        }
        #else
        TestFlowView()
        #endif
    }
}

#if DEBUG
/// §1~§4의 스모크 화면. `xcrun simctl launch --console-pty booted com.accentury.app -DebugSmokeMenu 1`.
/// 릴리스 빌드에는 통째로 없다.
private struct DebugSmokeMenu: View {

    @StateObject private var smoke = RecordingSmokeModel()

    @State private var showsPermissionGate = false
    @State private var permissionResult = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("accentury")
                .font(.largeTitle.bold())

            configRow(label: "WEB_URL", value: AppConfig.webURL)
            configRow(label: "API_BASE_URL", value: AppConfig.apiBaseURL)
            configRow(label: "app", value: AppConfig.appVersionName)
            configRow(label: "bridge", value: String(bridgeContractVersion))

            Text("KAN-108 스모크 메뉴 · 기본 화면은 TestFlowView다")
                .font(.footnote)
                .foregroundStyle(.secondary)

            Divider()
            recordingSmokeSection
            Divider()
            permissionSmokeSection

            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(24)
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
            .interactiveDismissDisabled(true)
        }
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
}
#endif

#Preview {
    ContentView()
}
