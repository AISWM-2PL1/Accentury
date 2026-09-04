package com.accentury.app.audio

/**
 * YIN F0 추정기 - de Cheveigné & Kawahara, JASA 111(4), 2002.
 *
 * 선정 배경은 docs/wiki/ondevice-f0.md (KAN-103). TarsosDSP 등 기성 라이브러리가
 * GPL 계열이라 상용 클로즈드소스 앱에 쓸 수 없어 논문 기반으로 재구현했다.
 * GPL 소스(TarsosDSP와 aubio)는 참고하지 않았다 - 파생저작물 위험.
 *
 * 구 autocorrelation 프로토타입 대비 핵심 차이:
 * - CMNDF(누적 평균 정규화)로 τ=작은 구간의 가짜 극소값을 눌러 옥타브 오류를 줄이고,
 * - 절대 임계값으로 무성음 프레임을 null로 판정해 곡선 튐을 막는다.
 *
 * 온디바이스 실시간 곡선용이므로 정확도보다 저지연 우선(architecture.md).
 * 2048샘플@16kHz 프레임 기준 시간영역 O(W·τmax) ≈ 37만 곱셈 - 예산 대비 미미.
 */
object YinPitchEstimator {

    /** 사람 목소리 F0 탐색 대역. 대역 밖(예: 50Hz 험 노이즈)은 무성음 취급된다. */
    const val MIN_F0_HZ = 80
    const val MAX_F0_HZ = 400

    /**
     * CMNDF 절대 임계값. 원 논문 권장은 0.1~0.2지만 우리는 0.25로 느슨하게 잡았다.
     *
     * 근거 - 우리 용도는 정밀 F0 측정이 아니라 실시간 억양 곡선이라, 판단 기준이
     * "끊김 < 약간의 오검출"이다. 곡선이 조각나면 사용자가 억양을 읽을 수 없지만
     * 한두 점이 살짝 튀는 건 곡선 모양을 해치지 않는다.
     *
     * 실제 대화 샘플 실측(KAN-105) - 유성 판정률 / 옥타브 오류(중앙값 대비 1.8배 밖):
     * | 임계값 | 20대 샘플 | 50대 샘플 | 옥타브 오류 |
     * | 0.15  | 85%      | 36~48%   | -          |
     * | 0.25  | 91%      | 72~77%   | 0~1개       |
     * | 0.30  | +2~7%p   | +2~7%p   | -          |
     * 0.15는 50대 목소리에서 곡선이 조각났고, 0.30은 0.25 대비 이득이 작아 0.25로 정했다.
     */
    private const val CMNDF_THRESHOLD = 0.25f

    /**
     * 유성 판정을 시도할 최소 청크 RMS. `AudioQuality.QUIET_RMS_THRESHOLD`와 같은 값이다.
     *
     * 임계값을 0.25로 느슨하게 잡으면 마이크 잡음이나 무음 구간에서도 CMNDF가 우연히
     * 0.25 아래로 내려가 가짜 피치가 나올 수 있다. 소리가 없는 곳에서는 판정 자체를 안 한다.
     * 점검 화면의 볼륨 판정(`VoiceCheckController`)과 같은 문턱을 쓰므로
     * "점검을 통과한 볼륨이면 곡선이 나온다"가 일관되게 유지된다.
     */
    const val VOICED_MIN_RMS = 100f

    /**
     * 한 PCM 청크의 F0(Hz)를 추정한다. 무성음이거나 판별 불가면 null.
     * 청크가 탐색에 필요한 최소 길이(τmax의 2배)보다 짧아도 null.
     */
    fun estimate(chunk: ShortArray, sampleRate: Int = SAMPLE_RATE): Float? {
        val tauMin = sampleRate / MAX_F0_HZ // 16kHz 기준 40샘플
        val tauMax = sampleRate / MIN_F0_HZ // 16kHz 기준 200샘플
        val window = chunk.size - tauMax // 적분 창: x[j+τ]가 청크를 벗어나지 않는 범위
        if (window <= tauMax) return null

        // 에너지 게이트 - CMNDF 계산 앞에 둬서 무음 프레임은 O(W·τ) 연산을 아예 건너뛴다.
        if (calculateRms(chunk) < VOICED_MIN_RMS) return null

        val x = FloatArray(chunk.size) { chunk[it].toFloat() }

        // 1단계 - 차분 함수 d(τ): 파형을 τ만큼 민 복사본과의 오차 제곱합.
        //         한 주기만큼 밀면 파형이 겹쳐 d(τ)가 극소가 된다.
        val d = FloatArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var sum = 0f
            for (j in 0 until window) {
                val diff = x[j] - x[j + tau]
                sum += diff * diff
            }
            d[tau] = sum
        }

        // 2단계 - CMNDF: d(τ)를 τ까지의 누적 평균으로 나눠 정규화.
        //         작은 τ에서 d가 낮게 나오는 편향을 제거한다 (옥타브 오류 완화의 핵심).
        val cmndf = FloatArray(tauMax + 1)
        cmndf[0] = 1f
        var runningSum = 0f
        for (tau in 1..tauMax) {
            runningSum += d[tau]
            // 무음이면 runningSum이 0 - 나눗셈 방지 겸 무성음으로 흘려보낸다.
            cmndf[tau] = if (runningSum == 0f) 1f else d[tau] * tau / runningSum
        }

        // 3단계 - 절대 임계값: 탐색 대역 안에서 임계값 아래로 처음 내려간 지점을 찾고,
        //         국소 최솟값까지 하강한다. 못 찾으면 무성음.
        var tau = tauMin
        while (tau <= tauMax && cmndf[tau] >= CMNDF_THRESHOLD) tau++
        if (tau > tauMax) return null
        while (tau + 1 <= tauMax && cmndf[tau + 1] < cmndf[tau]) tau++

        // 4단계 - 포물선 보간: 정수 τ 이웃 3점으로 실수 주기를 근사해 양자화 오차를 줄인다.
        //         대역 경계 τ에서 보간이 대역을 살짝 벗어날 수 있어(예: τ=40 → 400Hz 초과)
        //         결과를 탐색 대역으로 clamp한다 (Codex 1R).
        val f0 = sampleRate / parabolicInterpolation(cmndf, tau)
        return f0.coerceIn(MIN_F0_HZ.toFloat(), MAX_F0_HZ.toFloat())
    }

    private fun parabolicInterpolation(cmndf: FloatArray, tau: Int): Float {
        if (tau <= 0 || tau >= cmndf.size - 1) return tau.toFloat()
        val s0 = cmndf[tau - 1]
        val s1 = cmndf[tau]
        val s2 = cmndf[tau + 1]
        val denom = 2f * (2f * s1 - s2 - s0)
        if (denom == 0f) return tau.toFloat()
        return tau + (s2 - s0) / denom
    }
}
