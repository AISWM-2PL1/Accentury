import AVFoundation
import UIKit

/// 마이크 권한을 묻고 읽는 iOS API 어댑터. 안드로이드의 `ContextCompat.checkSelfPermission` +
/// `ActivityResultContracts.RequestPermission` + `ACTION_APPLICATION_DETAILS_SETTINGS` 자리다.
///
/// 판단은 하나도 하지 않는다 — 상태 머신은 `AccenturyCore`의 `MicPermissionController`에 있고
/// 여기는 OS에 묻고 답을 그대로 옮기기만 한다. 이 분리 덕에 게이트 로직이 시뮬레이터 없이 돈다.
///
/// ## 안드로이드와 갈리는 지점: `canAskAgain`이 없다
/// 안드로이드는 `shouldShowRequestPermissionRationale`로 "팝업을 한 번 더 띄울 수 있는가"를
/// 되물을 수 있다. iOS에는 그런 질의가 없고, 대신 규칙이 고정돼 있다 — **팝업은 앱 설치당
/// 한 번뿐이다.** 사용자가 거부하면 `requestRecordPermission`은 그 뒤로 화면을 띄우지 않고
/// 곧바로 false를 준다. 그래서 이 앱에서 `canAskAgain`은 언제나 false이고, 거부는 곧
/// `permanentlyDenied`(설정 딥링크만)로 접힌다.
enum MicPermission {

    /// 지금의 실제 권한.
    ///
    /// - Returns: `granted`는 녹음 가능 여부, `undetermined`는 아직 한 번도 묻지 않았는지다.
    ///   둘 다 false면 거부된 상태다. `undetermined`를 따로 돌려주는 이유는 스모크 로그가
    ///   "묻기 전"과 "거부됨"을 구분해 찍어야 시뮬레이터 확인이 의미를 갖기 때문이다.
    static func currentStatus() -> (granted: Bool, undetermined: Bool) {
        if #available(iOS 17.0, *) {
            switch AVAudioApplication.shared.recordPermission {
            case .granted: return (true, false)
            case .undetermined: return (false, true)
            case .denied: return (false, false)
            @unknown default: return (false, false)
            }
        }
        return legacyStatus()
    }

    /// OS 팝업을 띄우고 결과를 기다린다. 이미 거부된 상태면 팝업 없이 즉시 false다.
    static func request() async -> Bool {
        if #available(iOS 17.0, *) {
            return await AVAudioApplication.requestRecordPermission()
        }
        return await legacyRequest()
    }

    /// 앱의 설정 화면을 연다. 안드로이드 `ACTION_APPLICATION_DETAILS_SETTINGS` 인텐트 자리로,
    /// 영구 거부에서 유일하게 남은 통로다 (2026-07-27 확정: 건너뛰기 없음).
    @MainActor
    static func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString),
              UIApplication.shared.canOpenURL(url) else { return }
        UIApplication.shared.open(url)
    }

    // MARK: - iOS 16 경로
    //
    // 아래 둘은 스스로 deprecated로 표시돼 있다. iOS 17 SDK로 빌드하면서 구버전 API를 쓰면
    // 경고가 나는데, deprecated 함수 안에서의 호출은 경고 대상이 아니다 — 배포 하한이 16이라
    // 코드는 남겨야 하고, 경고는 지워야 하는 상황의 표준 처리다.

    @available(iOS, introduced: 16.0, deprecated: 17.0, message: "iOS 17+는 AVAudioApplication을 쓴다")
    private static func legacyStatus() -> (granted: Bool, undetermined: Bool) {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted: return (true, false)
        case .undetermined: return (false, true)
        case .denied: return (false, false)
        @unknown default: return (false, false)
        }
    }

    @available(iOS, introduced: 16.0, deprecated: 17.0, message: "iOS 17+는 AVAudioApplication을 쓴다")
    private static func legacyRequest() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }
}
