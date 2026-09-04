import AccenturyCore
import Foundation
import os

/// 이번 실행이 쓸 PCM 소스를 고른다. 안드로이드
/// `app/src/{debug,release}/java/com/accentury/app/audio/PcmSources.kt` 두 변형의 이식본이다.
///
/// 시뮬레이터에는 마이크가 없다시피 하고(맥 입력이 그대로 오지 않는다) 실기기라도 매번 같은
/// 발화를 낼 수는 없다. 피치 곡선을 눈으로 다듬으려면 같은 음성이 같은 속도로 들어와야 해서,
/// 디버그 빌드에 한해 WAV 파일을 마이크 자리에 끼울 수 있게 해 둔다.
///
/// ## 사용법
/// `ios/Accentury/Config/Local.xcconfig`(gitignore 대상, 안드로이드 `local.properties` 자리)에
/// 한 줄 적고 다시 빌드한다:
/// ```
/// FAKE_MIC_ASSET = fake_mic.wav          // 앱 번들에 넣은 리소스 이름
/// FAKE_MIC_ASSET = /Users/me/fake_mic.wav // 또는 맥의 절대 경로 (시뮬레이터는 맥 파일을 읽는다)
/// ```
/// 안드로이드의 `./gradlew :app:installDebug -PfakeMic=fake_mic.wav`와 같은 자리다.
/// 값이 없으면 `FAKE_MIC_ASSET`이 빈 문자열이라 평소대로 마이크를 쓴다.
/// WAV는 16kHz 모노 16bit여야 한다 (`FilePcmSource` 참고 — 리샘플하지 않는다).
///
/// ## 릴리스에 파일 재생 경로가 없다는 보장
/// 안드로이드는 `src/debug` 소스셋으로 클래스 자체를 릴리스에서 지운다. SwiftPM/Xcode에는
/// 그에 딱 맞는 장치가 없어 두 겹으로 막는다 — (1) 아래 읽기 경로가 통째로 `#if DEBUG`이고,
/// (2) `FAKE_MIC_ASSET` 키가 `Info-Release.plist`에 **아예 없다**. 릴리스 빌드에서 파일
/// 소스를 켜려면 릴리스 plist를 고쳐야 하고, 그건 리뷰에 걸린다 (ATS 예외를 plist 두 개로
/// 나눠 둔 것과 같은 방식이다).
///
/// ## 안드로이드와 의도적으로 다른 점
/// `MonitoredPcmSource`(파일 소스를 스피커로도 내보내는 데코레이터)는 옮기지 않았다.
/// 그건 에뮬레이터에서 "지금 어느 음성이 지나가는지" 귀로 확인하려고 만든 물건인데,
/// iOS 시뮬레이터는 앱 오디오를 맥 스피커로 그대로 낸다. 필요해지면 `AVAudioEngine`의
/// player node로 같은 데코레이터를 붙일 수 있다.
func defaultPcmSource() -> PcmSource {
    #if DEBUG
    if let source = fakeMicSource() { return source }
    #endif
    return AudioRecorder()
}

#if DEBUG
private let fakeMicLog = Logger(subsystem: "com.accentury.app", category: "audio")

/// `FAKE_MIC_ASSET`이 가리키는 WAV를 소스로 만든다. 값이 없거나 파일을 못 찾으면 nil이고,
/// 그때는 호출부가 실제 마이크로 넘어간다.
private func fakeMicSource() -> PcmSource? {
    let raw = Bundle.main.object(forInfoDictionaryKey: "FAKE_MIC_ASSET") as? String ?? ""
    let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !name.isEmpty else { return nil }

    guard let url = fakeMicURL(name) else {
        fakeMicLog.error("FAKE_MIC_ASSET=\(name, privacy: .public) 파일을 찾지 못했다 - 실제 마이크로 넘어간다")
        return nil
    }
    fakeMicLog.info("가짜 마이크 사용: \(url.path, privacy: .public)")
    // 여는 클로저를 넘긴다 - 녹음은 여러 번 일어나고 매번 파일 처음부터 흘러야 한다.
    return FilePcmSource(open: { try Data(contentsOf: url) })
}

private func fakeMicURL(_ name: String) -> URL? {
    // 절대 경로면 그대로 쓴다. 시뮬레이터는 맥 파일 시스템을 그대로 보므로, 번들에 리소스를
    // 넣지 않고도 파일 하나로 곡선을 확인할 수 있다.
    if name.hasPrefix("/") {
        return FileManager.default.fileExists(atPath: name) ? URL(fileURLWithPath: name) : nil
    }
    let base = (name as NSString).deletingPathExtension
    let ext = (name as NSString).pathExtension
    return Bundle.main.url(forResource: base, withExtension: ext.isEmpty ? "wav" : ext)
}
#endif
