import Foundation

/// `java.util.Random`의 수열을 그대로 재현하는 테스트 전용 헬퍼.
///
/// 안드로이드 `YinPitchEstimatorTest`가 백색잡음 케이스에서 `java.util.Random(42)`를 쓴다.
/// 시드를 고정한 이유는 "무성음 판정 결과가 실행마다 흔들리지 않게"인데, iOS에서 다른 난수원을 쓰면
/// 두 플랫폼이 서로 다른 잡음을 검사하게 되어 그 고정의 의미가 반쪽이 된다. 그래서 JVM 명세에
/// 적힌 48비트 LCG를 이 타깃에 다시 구현했다 (Java SE `java.util.Random` 클래스 문서 기준).
///
/// 제품 코드가 아니라 테스트 픽스처다 — 암호학적 용도가 아니고, 앱에서 난수가 필요하면
/// `SystemRandomNumberGenerator`를 쓴다.
struct JavaRandom {

    private static let multiplier: Int64 = 0x5DEECE66D
    private static let addend: Int64 = 0xB
    private static let mask: Int64 = (1 << 48) - 1

    private var seed: Int64

    init(seed: Int64) {
        self.seed = (seed ^ Self.multiplier) & Self.mask
    }

    /// `protected int next(int bits)`.
    private mutating func next(_ bits: Int) -> Int32 {
        seed = (seed &* Self.multiplier &+ Self.addend) & Self.mask
        return Int32(truncatingIfNeeded: seed >> Int64(48 - bits))
    }

    /// `public int nextInt(int bound)`. 2의 거듭제곱 분기와 모듈로 편향 제거 루프까지 그대로다 —
    /// 편향 제거 루프의 조건은 Java에서 int 오버플로를 이용하므로 Swift에서도 감싸는 연산(`&-`, `&+`)을 쓴다.
    mutating func nextInt(_ bound: Int32) -> Int32 {
        precondition(bound > 0, "bound는 양수여야 함")
        var r = next(31)
        let m = bound - 1
        if (bound & m) == 0 {
            r = Int32(truncatingIfNeeded: (Int64(bound) &* Int64(r)) >> 31)
        } else {
            var u = r
            while true {
                r = u % bound
                if u &- r &+ m >= 0 { break }
                u = next(31)
            }
        }
        return r
    }
}
