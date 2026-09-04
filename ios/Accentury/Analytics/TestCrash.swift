#if DEBUG
import Foundation

/// 일부러 내는 테스트 크래시 (KAN-33 AC 9). 안드로이드 `analytics/TestCrash.kt`의 짝이다.
///
/// Crashlytics가 **실제 크래시**를 기기 모델·OS 버전·심볼화된 스택과 함께 받는지는 진짜 크래시
/// 하나를 내 봐야 확인된다. ``CrashReports``의 `record(error:)`는 비치명 보고라 그 경로를 지나지
/// 않고, 앱에는 일부러 죽을 자리가 없으므로 여기에 통로를 하나 둔다.
///
/// 사용법 — 다른 디버그 통로와 같은 실행 인자다 (`README.md` «디버그 실행 인자»):
/// ```
/// xcrun simctl launch --console-pty booted com.accentury.app -TestCrash 1
/// xcrun simctl launch --console-pty booted com.accentury.app   # 다시 켜야 리포트가 올라간다
/// ```
///
/// **다시 켜야 하는 이유**: Crashlytics는 죽는 순간 네트워크를 쓰지 않는다 — 리포트를 디스크에
/// 쓰고, 다음 실행의 초기화에서 그 파일을 올려보낸다. 그래서 크래시 뒤 앱을 한 번 더 켜기
/// 전에는 대시보드에 아무것도 없다. 두 번째 실행에 인자를 빼는 것이 요점이다.
///
/// 디버거를 붙인 채로는 LLDB가 먼저 멈춰 세워 리포트가 만들어지지 않는다. 시뮬레이터는 위처럼
/// `simctl launch`로 띄우면 되고, **실기기**는 Xcode의 Edit Scheme > Run에서
/// «Debug executable»을 끄고 Arguments에 `-TestCrash 1`을 넣은 뒤 Run한다 — 그러면 설치·실행은
/// Xcode가 하고 디버거만 빠져, 실행 인자를 넘기면서도 크래시가 그대로 잡힌다. AC 9의 «기기 모델·
/// OS 버전»은 시뮬레이터로는 채워지지 않으므로 그 확인은 실기기여야 한다.
///
/// ## 릴리스에 남지 않는 보장
///
/// 파일 전체가 `#if DEBUG` 안이고 호출 지점(``ContentView``)도 마찬가지라, 릴리스 바이너리에는
/// 죽는 코드도 `TestCrash`라는 문자열도 없다. 안드로이드는 같은 보장을 소스셋으로 하고(릴리스
/// 변형이 빈 함수) 이쪽은 컴파일 조건으로 한다 — 두 플랫폼의 관용을 각각 따른 것뿐이다.
///
/// 설정(GoogleService-Info.plist)이 없는 빌드에서도 그대로 죽는다. Firebase를 부르지 않아서다 —
/// 리포트가 올라가지 않을 뿐 빌드도 동작도 갈리지 않는다.
enum TestCrash {

    /// 실행 인자가 있으면 즉시 죽는다. 없으면 아무 일도 하지 않는다.
    ///
    /// `fatalError`인 이유: 잡히는 예외가 아니라 프로세스를 끝내는 트랩이라야 Crashlytics의
    /// 시그널 핸들러가 받는다. Swift에는 잡히는 런타임 예외라는 개념이 없어 `try`로 감싸도
    /// 되살아나는 길이 없고, 그게 여기서는 원하는 성질이다.
    @MainActor
    static func fireIfRequested() {
        guard UserDefaults.standard.bool(forKey: "TestCrash") else { return }
        fatalError("KAN-33 테스트 크래시 (-TestCrash 1)")
    }
}
#endif
