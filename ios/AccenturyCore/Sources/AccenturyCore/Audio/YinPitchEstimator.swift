import Foundation

/// YIN F0 추정기 - de Cheveigné & Kawahara, JASA 111(4), 2002.
///
/// 안드로이드 `audio/YinPitchEstimator.kt`의 1:1 이식본이다. 알고리즘·상수·연산 순서를 그대로 옮겼다 —
/// 두 플랫폼의 곡선이 같은 지점에서 같은 값과 같은 무성음 판정을 내야 하기 때문이다(ondevice-f0.md §4).
///
/// 선정 배경은 docs/wiki/ondevice-f0.md (KAN-103). TarsosDSP 등 기성 라이브러리가
/// GPL 계열이라 상용 클로즈드소스 앱에 쓸 수 없어 논문 기반으로 재구현했다.
/// GPL 소스(TarsosDSP와 aubio)는 참고하지 않았다 - 파생저작물 위험.
///
/// 구 autocorrelation 프로토타입 대비 핵심 차이:
/// - CMNDF(누적 평균 정규화)로 τ=작은 구간의 가짜 극소값을 눌러 옥타브 오류를 줄이고,
/// - 절대 임계값으로 무성음 프레임을 null로 판정해 곡선 튐을 막는다.
///
/// 온디바이스 실시간 곡선용이므로 정확도보다 저지연 우선(architecture.md).
/// 2048샘플@16kHz 프레임 기준 시간영역 O(W·τmax) ≈ 37만 곱셈 - 예산 대비 미미.
///
/// Accelerate(vDSP)는 쓰지 않는다. 실기기 프로파일링에서 예산을 못 맞추는 게 확인되기 전까지는
/// 안드로이드와 같은 스칼라 코드를 유지해야 값이 갈릴 여지가 없다.
///
/// **디버그 빌드 속도에 놀라지 말 것.** 아래 차분 함수 이중 루프는 프레임 하나에 약 37만 회를
/// 도는데, `-Onone`에서는 지역 변수가 전부 스택을 거쳐 회당 ~68ns가 붙는다 — 맥에서 프레임당
/// 약 26ms다. 같은 코드가 릴리스에서는 0.23ms/프레임으로, 갱신 주기(hop 512 = 32ms)에 100배
/// 여유다. 생 포인터·버퍼 포인터로 바꿔 봐야 디버그에서 16%뿐이라 (측정치) 첨자를 그대로 둔다.
public enum YinPitchEstimator {

    /// 사람 목소리 F0 탐색 대역. 대역 밖(예: 50Hz 험 노이즈)은 무성음 취급된다.
    public static let minF0Hz = 80
    public static let maxF0Hz = 400

    /// CMNDF 절대 임계값. 원 논문 권장은 0.1~0.2지만 우리는 0.25로 느슨하게 잡았다.
    ///
    /// 근거 - 우리 용도는 정밀 F0 측정이 아니라 실시간 억양 곡선이라, 판단 기준이
    /// "끊김 < 약간의 오검출"이다. 곡선이 조각나면 사용자가 억양을 읽을 수 없지만
    /// 한두 점이 살짝 튀는 건 곡선 모양을 해치지 않는다.
    ///
    /// 실제 대화 샘플 실측(KAN-105) - 유성 판정률 / 옥타브 오류(중앙값 대비 1.8배 밖):
    /// | 임계값 | 20대 샘플 | 50대 샘플 | 옥타브 오류 |
    /// | 0.15  | 85%      | 36~48%   | -          |
    /// | 0.25  | 91%      | 72~77%   | 0~1개       |
    /// | 0.30  | +2~7%p   | +2~7%p   | -          |
    /// 0.15는 50대 목소리에서 곡선이 조각났고, 0.30은 0.25 대비 이득이 작아 0.25로 정했다.
    private static let cmndfThreshold: Float = 0.25

    /// 유성 판정을 시도할 최소 청크 RMS. `AudioQuality.quietRmsThreshold`와 같은 값이다.
    ///
    /// 임계값을 0.25로 느슨하게 잡으면 마이크 잡음이나 무음 구간에서도 CMNDF가 우연히
    /// 0.25 아래로 내려가 가짜 피치가 나올 수 있다. 소리가 없는 곳에서는 판정 자체를 안 한다.
    /// 점검 화면의 볼륨 판정(`VoiceCheckController`)과 같은 문턱을 쓰므로
    /// "점검을 통과한 볼륨이면 곡선이 나온다"가 일관되게 유지된다.
    public static let voicedMinRms: Float = 100

    /// 한 PCM 청크의 F0(Hz)를 추정한다. 무성음이거나 판별 불가면 nil.
    /// 청크가 탐색에 필요한 최소 길이(τmax의 2배)보다 짧아도 nil.
    ///
    /// 기본 인자를 `AccenturyCore.sampleRate`로 모듈 한정해 적은 이유는 파라미터 이름이
    /// 전역 상수와 같아서다 — 한정 없이 적으면 읽는 사람이 자기 자신을 가리키는 걸로 오해한다.
    public static func estimate(_ chunk: [Int16], sampleRate: Int = AccenturyCore.sampleRate) -> Float? {
        let tauMin = sampleRate / maxF0Hz // 16kHz 기준 40샘플
        let tauMax = sampleRate / minF0Hz // 16kHz 기준 200샘플
        let window = chunk.count - tauMax // 적분 창: x[j+τ]가 청크를 벗어나지 않는 범위
        if window <= tauMax { return nil }

        // 에너지 게이트 - CMNDF 계산 앞에 둬서 무음 프레임은 O(W·τ) 연산을 아예 건너뛴다.
        if calculateRms(chunk) < Double(voicedMinRms) { return nil }

        var x = [Float](repeating: 0, count: chunk.count)
        for i in 0..<chunk.count { x[i] = Float(chunk[i]) }

        // 1단계 - 차분 함수 d(τ): 파형을 τ만큼 민 복사본과의 오차 제곱합.
        //         한 주기만큼 밀면 파형이 겹쳐 d(τ)가 극소가 된다.
        var d = [Float](repeating: 0, count: tauMax + 1)
        for tau in 1...tauMax {
            var sum: Float = 0
            for j in 0..<window {
                let diff = x[j] - x[j + tau]
                sum += diff * diff
            }
            d[tau] = sum
        }

        // 2단계 - CMNDF: d(τ)를 τ까지의 누적 평균으로 나눠 정규화.
        //         작은 τ에서 d가 낮게 나오는 편향을 제거한다 (옥타브 오류 완화의 핵심).
        var cmndf = [Float](repeating: 0, count: tauMax + 1)
        cmndf[0] = 1
        var runningSum: Float = 0
        for tau in 1...tauMax {
            runningSum += d[tau]
            // 무음이면 runningSum이 0 - 나눗셈 방지 겸 무성음으로 흘려보낸다.
            cmndf[tau] = runningSum == 0 ? 1 : d[tau] * Float(tau) / runningSum
        }

        // 3단계 - 절대 임계값: 탐색 대역 안에서 임계값 아래로 처음 내려간 지점을 찾고,
        //         국소 최솟값까지 하강한다. 못 찾으면 무성음.
        var tau = tauMin
        while tau <= tauMax && cmndf[tau] >= cmndfThreshold { tau += 1 }
        if tau > tauMax { return nil }
        while tau + 1 <= tauMax && cmndf[tau + 1] < cmndf[tau] { tau += 1 }

        // 4단계 - 포물선 보간: 정수 τ 이웃 3점으로 실수 주기를 근사해 양자화 오차를 줄인다.
        //         대역 경계 τ에서 보간이 대역을 살짝 벗어날 수 있어(예: τ=40 → 400Hz 초과)
        //         결과를 탐색 대역으로 clamp한다 (Codex 1R).
        let f0 = Float(sampleRate) / parabolicInterpolation(cmndf, tau)
        return min(max(f0, Float(minF0Hz)), Float(maxF0Hz))
    }

    private static func parabolicInterpolation(_ cmndf: [Float], _ tau: Int) -> Float {
        if tau <= 0 || tau >= cmndf.count - 1 { return Float(tau) }
        let s0 = cmndf[tau - 1]
        let s1 = cmndf[tau]
        let s2 = cmndf[tau + 1]
        let denom = 2 * (2 * s1 - s2 - s0)
        if denom == 0 { return Float(tau) }
        return Float(tau) + (s2 - s0) / denom
    }
}
