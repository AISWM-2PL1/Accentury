#if DEBUG
import AVFoundation
import AccenturyCore
import Foundation
import SwiftUI
import os

/// TODO(KAN-108 §5): 제거 — WKWebView 호스트가 들어오면 녹음 시작·정지는 웹 화면이 하고
/// 진행 상황은 브리지로 넘어간다. 그때까지 캡처 계층이 실제로 도는지 확인하는 임시 배선이다.
///
/// 디버그 빌드에만 컴파일된다. 두 가지 경로가 있다:
/// - **버튼**: 마이크 권한을 물어보고 `defaultPcmSource()`가 고른 소스로 최대 10초 녹음한다.
/// - **`-AutoMicSmoke 1` 실행 인자**: 마이크 경로를 그대로 자동 실행한다(최대 10초).
/// - **`-AutoRecordSmoke 1` 실행 인자**: 220Hz WAV를 임시 폴더에 만들어 파일 소스로 3초 흘린 뒤
///   `SMOKE:` 한 줄을 로그로 남긴다. 시뮬레이터는 탭을 자동화할 수 없어서
///   (`xcrun simctl`에 좌표 입력이 없다) CI/스크립트가 잡을 수 있는 경로를 따로 둔다.
///
/// `@MainActor`를 붙이지 않고 발행을 직접 메인으로 던지는 이유: `RecordingEngine.record`는
/// 액터에 매이지 않은 async 함수라 진행 콜백이 캡처 쪽 스레드에서 불린다. 모델을 MainActor로
/// 두면 그 콜백에서 상태를 만질 수 없다.
final class RecordingSmokeModel: ObservableObject {

    private static let log = Logger(subsystem: "com.accentury.app", category: "audio")

    @Published private(set) var status = "대기"
    @Published private(set) var elapsedMs: Int64 = 0
    @Published private(set) var rms: Double = 0
    @Published private(set) var lastF0: Float?
    @Published private(set) var result = ""
    @Published private(set) var isRecording = false
    @Published private(set) var isBusy = false

    private var engine: RecordingEngine?

    func toggle() {
        if isRecording {
            engine?.requestStop()
            return
        }
        Task { await runWithMicrophone() }
    }

    // MARK: - 버튼 경로

    private func runWithMicrophone() async {
        publish { self.isBusy = true; self.status = "마이크 권한 확인 중" }
        guard await Self.requestMicrophonePermission() else {
            publish { self.isBusy = false; self.status = "마이크 권한 거부됨 - 설정에서 허용해야 한다" }
            return
        }
        await run(source: defaultPcmSource(), tag: "MIC")
    }

    /// 실제 권한 게이트는 §4(온보딩 화면) 소관이다. 여기서는 녹음이 시작되게만 하는 최소 배선이다.
    private static func requestMicrophonePermission() async -> Bool {
        if #available(iOS 17.0, *) {
            return await AVAudioApplication.requestRecordPermission()
        }
        return await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    // MARK: - 자동 스모크 경로

    func runAutoSmokeIfRequested() async {
        // `-AutoMicSmoke 1`은 파일 대신 실제 마이크 경로(AVAudioEngine)를 자동으로 태운다.
        // 시뮬레이터에서 탭 없이 캡처 배선을 확인하는 유일한 방법이라 따로 둔다
        // (권한은 `xcrun simctl privacy booted grant microphone com.accentury.app`로 미리 준다).
        if UserDefaults.standard.bool(forKey: "AutoMicSmoke") {
            await runWithMicrophone()
            return
        }
        guard UserDefaults.standard.bool(forKey: "AutoRecordSmoke") else { return }
        do {
            let url = try Self.writeSineWav(hz: 220, seconds: 3)
            // 마이크와 같은 페이스(realtime)로 흘린다 - 즉시 흘리면 32ms 갱신 주기가 검증되지 않는다.
            await run(source: FilePcmSource(open: { try Data(contentsOf: url) }), tag: "SMOKE")
        } catch {
            let line = "SMOKE: FAILED \(error)"
            print(line)
            Self.log.error("\(line, privacy: .public)")
            publish { self.status = line }
        }
    }

    /// 220Hz 사인 WAV를 임시 폴더에 만든다. 시뮬레이터에는 쓸 만한 마이크 입력이 없어
    /// 스모크가 기댈 수 있는 신호를 앱이 직접 만든다 — 진폭 8000은 RMS 게이트(100)를 한참 넘겨
    /// 모든 프레임이 유성으로 판정돼야 하고, 그래서 `voiced == f0frames`가 성공 조건이 된다.
    private static func writeSineWav(hz: Double, seconds: Double) throws -> URL {
        let count = Int(Double(sampleRate) * seconds)
        let pcm = (0..<count).map { i in
            Int16(truncatingIfNeeded: Int(8000 * sin(2 * Double.pi * hz * Double(i) / Double(sampleRate))))
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("smoke_input_220hz.wav")
        try WavWriter.write(file: url, pcm: pcm)
        return url
    }

    // MARK: - 공통 녹음 루프

    private func run(source: PcmSource, tag: String) async {
        let engine = RecordingEngine(source: source)
        self.engine = engine
        publish {
            self.isBusy = true
            self.isRecording = true
            self.status = "녹음 중 (\(tag))"
            self.result = ""
            self.elapsedMs = 0
        }

        // 진행 콜백은 캡처 스레드에서 불린다. 집계는 여기(값 타입 지역 변수)에서 하고
        // 화면 갱신만 메인으로 던진다.
        var frameCount = 0
        var voicedCount = 0
        let outcome = await engine.record { progress in
            frameCount += progress.pitchFrames.count
            voicedCount += progress.pitchFrames.filter { $0.pitchHz != nil }.count
            let latest = progress.pitchFrames.last(where: { $0.pitchHz != nil })?.pitchHz
            self.publish {
                self.elapsedMs = progress.elapsedMs
                self.rms = progress.rms
                if let latest { self.lastF0 = latest }
            }
        }

        self.engine = nil
        switch outcome {
        case let .success(pcm, durationMs, autoStopped):
            /*
             * WAV를 만들되 **디스크에 쓰지 않는다** (FR-DP-02, KAN-108 §5.5). §3에서는 만들어진
             * 파일을 손으로 열어 파형을 보려고 임시 폴더에 `last.wav`를 남겼는데, 그때는 이
             * 화면이 앱의 전부였고 지금은 실제 녹음이 도는 앱에 붙어 있는 디버그 메뉴다 —
             * 녹음이 파일로 남는 경로를 하나라도 열어 두면 "녹음은 메모리에만 산다"가 규칙이
             * 아니라 습관이 된다. 바이트 수만 로그로 남기고 값은 여기서 끝난다.
             */
            let wav = WavWriter.toWavBytes(pcm)
            let line = "\(tag): pcm=\(pcm.count) duration=\(durationMs) f0frames=\(frameCount)"
                + " voiced=\(voicedCount) wav=\(wav.count)"
            print(line)
            Self.log.info("\(line, privacy: .public)")
            publish {
                self.isBusy = false
                self.isRecording = false
                self.status = autoStopped ? "10초 자동 종료" : "녹음 완료"
                self.result = line
            }
        case let .failure(reason):
            let line = "\(tag): FAILED \(reason)"
            print(line)
            Self.log.error("\(line, privacy: .public)")
            publish {
                self.isBusy = false
                self.isRecording = false
                self.status = "실패 - \(reason)"
                self.result = line
            }
        }
    }

    /// `@Published`는 메인에서만 건드린다. 이미 메인이면 곧바로 실행해 순서가 뒤집히지 않게 한다.
    private func publish(_ mutate: @escaping () -> Void) {
        if Thread.isMainThread {
            mutate()
        } else {
            DispatchQueue.main.async(execute: mutate)
        }
    }
}
#endif
