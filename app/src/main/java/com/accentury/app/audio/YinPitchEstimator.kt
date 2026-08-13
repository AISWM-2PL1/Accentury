package com.accentury.app.audio

/**
 * YIN F0 추정기 — de Cheveigné & Kawahara, JASA 111(4), 2002.
 *
 * 선정 배경은 docs/wiki/ondevice-f0.md (KAN-103). TarsosDSP 등 기성 라이브러리가
 * GPL 계열이라 상용 클로즈드소스 앱에 쓸 수 없어 논문 기반으로 재구현했다.
 * GPL 소스(TarsosDSP·aubio)는 참고하지 않았다 — 파생저작물 위험.
 *
 * 구 autocorrelation 프로토타입 대비 핵심 차이:
 * - CMNDF(누적 평균 정규화)로 τ=작은 구간의 가짜 극소값을 눌러 옥타브 오류를 줄이고,
 * - 절대 임계값으로 무성음 프레임을 null로 판정해 곡선 튐을 막는다.
 *
 * 온디바이스 실시간 곡선용이므로 정확도보다 저지연 우선(architecture.md).
 * 2048샘플@16kHz 프레임 기준 시간영역 O(W·τmax) ≈ 37만 곱셈 — 예산 대비 미미.
 */
object YinPitchEstimator {

    /** 사람 목소리 F0 탐색 대역. 대역 밖(예: 50Hz 험 노이즈)은 무성음 취급된다. */
    const val MIN_F0_HZ = 80
    const val MAX_F0_HZ = 400

    /** CMNDF 절대 임계값. 원 논문 권장 0.1~0.2 — 낮출수록 무성음 판정이 보수적. */
    private const val CMNDF_THRESHOLD = 0.15f

    /**
     * 한 PCM 청크의 F0(Hz)를 추정한다. 무성음이거나 판별 불가면 null.
     * 청크가 탐색에 필요한 최소 길이(τmax의 2배)보다 짧아도 null.
     */
    fun estimate(chunk: ShortArray, sampleRate: Int = SAMPLE_RATE): Float? {
        val tauMin = sampleRate / MAX_F0_HZ // 16kHz 기준 40샘플
        val tauMax = sampleRate / MIN_F0_HZ // 16kHz 기준 200샘플
        val window = chunk.size - tauMax // 적분 창: x[j+τ]가 청크를 벗어나지 않는 범위
        if (window <= tauMax) return null

        val x = FloatArray(chunk.size) { chunk[it].toFloat() }

        // 1단계 — 차분 함수 d(τ): 파형을 τ만큼 민 복사본과의 오차 제곱합.
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

        // 2단계 — CMNDF: d(τ)를 τ까지의 누적 평균으로 나눠 정규화.
        //         작은 τ에서 d가 낮게 나오는 편향을 제거한다 (옥타브 오류 완화의 핵심).
        val cmndf = FloatArray(tauMax + 1)
        cmndf[0] = 1f
        var runningSum = 0f
        for (tau in 1..tauMax) {
            runningSum += d[tau]
            // 무음이면 runningSum이 0 — 나눗셈 방지 겸 무성음으로 흘려보낸다.
            cmndf[tau] = if (runningSum == 0f) 1f else d[tau] * tau / runningSum
        }

        // 3단계 — 절대 임계값: 탐색 대역 안에서 임계값 아래로 처음 내려간 지점을 찾고,
        //         국소 최솟값까지 하강한다. 못 찾으면 무성음.
        var tau = tauMin
        while (tau <= tauMax && cmndf[tau] >= CMNDF_THRESHOLD) tau++
        if (tau > tauMax) return null
        while (tau + 1 <= tauMax && cmndf[tau + 1] < cmndf[tau]) tau++

        // 4단계 — 포물선 보간: 정수 τ 이웃 3점으로 실수 주기를 근사해 양자화 오차를 줄인다.
        return sampleRate / parabolicInterpolation(cmndf, tau)
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
