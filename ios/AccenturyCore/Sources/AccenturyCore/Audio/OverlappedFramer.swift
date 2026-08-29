import Foundation

/// 분석 창 하나. samples는 항상 windowSize 길이이고, startSampleIndex는 녹음 시작 기준 전역 샘플 위치다.
///
/// 안드로이드는 `class`(참조 타입)지만 여기서는 `struct`다 — 두 필드를 읽기만 하는 값 묶음이라
/// 참조 동일성에 기대는 코드가 없고, 값 타입이라야 캡처 스레드에서 UI 쪽으로 넘길 때 `Sendable`이 된다.
public struct AnalysisFrame: Sendable {
    public let samples: [Int16]
    public let startSampleIndex: Int64

    public init(samples: [Int16], startSampleIndex: Int64) {
        self.samples = samples
        self.startSampleIndex = startSampleIndex
    }
}

/// 겹침 프레이밍 - 임의 길이 PCM 청크를 고정 길이 분석 창으로 잘라낸다 (KAN-104).
///
/// 캡처 API(안드로이드 `AudioRecord.read()`, iOS `AVAudioEngine` tap)는 요청한 2048샘플보다
/// 짧게 돌려줄 수 있고, 청크당 1회 추정하면 갱신 주기가 128ms라 NFR-PF-02(100ms 이하)를 못 맞춘다.
/// 창을 hopSize만큼만 밀어 창 길이(YIN 탐색에 필요한 2048샘플)는 유지한 채 갱신 주기만 hop 기준으로
/// 낮춘다. 기본값 2048/512는 75% 겹침 = 16kHz에서 32ms 주기.
///
/// 상태가 있으므로 녹음 1회당 1개를 쓴다. 그래서 `Sendable`이 아니다 — 캡처 콜백 하나가 소유하고
/// 그 안에서만 `push`를 부른다. 여러 스레드에서 부를 일이 생기면 액터로 감싸는 쪽이 맞지, 이 클래스에
/// 락을 넣을 자리가 아니다(안드로이드 쪽도 같은 전제로 동기화가 없다).
public final class OverlappedFramer {

    private let windowSize: Int
    private let hopSize: Int

    /// buffer[0 ..< count]는 아직 창으로 소비되지 않은 샘플이고, buffer[0]의 전역 위치가 bufferStartIndex다.
    private var buffer: [Int16]
    private var count = 0
    private var bufferStartIndex: Int64 = 0

    public init(windowSize: Int = chunkSize, hopSize: Int = 512) {
        precondition(windowSize > 0 && hopSize > 0, "windowSize/hopSize는 양수여야 함")
        precondition(hopSize <= windowSize, "hopSize는 windowSize 이하여야 함")
        self.windowSize = windowSize
        self.hopSize = hopSize
        self.buffer = [Int16](repeating: 0, count: windowSize * 2)
    }

    /// 청크를 밀어넣고 이번에 완성된 창들을 순서대로 돌려준다. 완성된 창이 없으면 빈 배열.
    public func push(_ chunk: [Int16]) -> [AnalysisFrame] {
        if chunk.isEmpty { return [] }
        ensureCapacity(count + chunk.count)
        buffer.replaceSubrange(count..<(count + chunk.count), with: chunk)
        count += chunk.count

        var frames: [AnalysisFrame] = []
        var offset = 0
        while count - offset >= windowSize {
            frames.append(
                AnalysisFrame(
                    samples: Array(buffer[offset..<(offset + windowSize)]),
                    startSampleIndex: bufferStartIndex + Int64(offset)
                )
            )
            offset += hopSize
        }
        if offset > 0 {
            // 다음 창 시작점 앞은 다시 볼 일이 없다. 버려서 버퍼가 녹음 길이만큼 자라지 않게 한다.
            // 안드로이드는 겹치는 구간을 `System.arraycopy`(memmove) 한 줄로 당기는데, Swift에서
            // 같은 배열을 읽으며 쓰면 배타적 접근 위반이라 앞에서부터 도는 루프로 옮긴다
            // (목적지가 원본보다 앞이라 전진 복사면 덮어쓰기 사고가 나지 않는다).
            for i in 0..<(count - offset) { buffer[i] = buffer[offset + i] }
            count -= offset
            bufferStartIndex += Int64(offset)
        }
        return frames
    }

    private func ensureCapacity(_ needed: Int) {
        if needed <= buffer.count { return }
        var newSize = buffer.count
        while newSize < needed { newSize *= 2 }
        buffer.append(contentsOf: [Int16](repeating: 0, count: newSize - buffer.count))
    }
}
