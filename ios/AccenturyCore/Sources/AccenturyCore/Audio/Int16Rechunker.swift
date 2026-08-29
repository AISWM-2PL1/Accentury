import Foundation

/// 임의 길이로 들어오는 PCM 샘플을 고정 길이 청크로 다시 잘라 내보낸다.
///
/// 안드로이드에는 대응물이 없다. `AudioRecord.read(buffer, 0, 512)`가 이미 512샘플 단위로
/// 돌려주기 때문이다. iOS는 하드웨어 포맷(44.1/48kHz)을 `AVAudioConverter`로 16kHz로 내리고,
/// 그 결과 길이가 탭마다 들쭉날쭉하다(48kHz 1024프레임 → 341 또는 342샘플). 그대로 흘리면
/// `OverlappedFramer`가 보는 청크 페이스가 안드로이드와 달라져, 진행 리포트가 청크당 0개나
/// 2개씩 나오는 구간이 생긴다. 여기서 다시 512로 맞춰 두 플랫폼의 갱신 주기를 같게 만든다.
///
/// `AVAudioEngine` 없이 검증할 수 있도록 Core에 둔다 — 이 타입은 순수 계산이고, 실제로
/// 틀리기 쉬운 부분(경계에서 샘플이 새거나 겹치는 것)이 전부 여기 있다.
///
/// 상태가 있으므로 녹음 1회당 1개를 쓴다. 그래서 `Sendable`이 아니다 — 변환 큐 하나가
/// 소유하고 그 안에서만 `push`를 부른다(`OverlappedFramer`와 같은 전제다).
public final class Int16Rechunker {

    private let chunkSize: Int

    /// buffer[0 ..< count]는 아직 청크로 나가지 못한 잔여 샘플이다.
    ///
    /// `Array.removeFirst(_:)`로 앞을 깎지 않는 이유: 그건 남은 원소를 매번 앞으로 당기는
    /// O(n) 연산이라, 32ms마다 도는 자리에서 청크 수만큼 반복된다. 대신 읽기 커서(offset)로
    /// 훑고 push 한 번당 딱 한 번만 잔여를 앞으로 옮긴다.
    private var buffer: [Int16]
    private var count = 0

    public init(chunkSize: Int = readChunkSize) {
        precondition(chunkSize > 0, "chunkSize는 양수여야 함")
        self.chunkSize = chunkSize
        // 잔여 + 한 번에 들어올 만한 양을 넉넉히 잡아 두면 평소에는 재할당이 없다.
        self.buffer = [Int16](repeating: 0, count: chunkSize * 4)
    }

    /// 아직 청크로 나가지 못하고 남아 있는 샘플 수. 항상 `0 ..< chunkSize`다.
    public var pendingCount: Int { count }

    public func push(_ samples: [Int16]) -> [[Int16]] {
        samples.withUnsafeBufferPointer { push($0) }
    }

    /// 변환 버퍼를 복사 없이 그대로 넘길 수 있게 포인터 오버로드를 둔다.
    public func push(_ samples: UnsafeBufferPointer<Int16>) -> [[Int16]] {
        if samples.isEmpty { return [] }
        ensureCapacity(count + samples.count)
        buffer.replaceSubrange(count..<(count + samples.count), with: samples)
        count += samples.count

        var chunks: [[Int16]] = []
        var offset = 0
        while count - offset >= chunkSize {
            chunks.append(Array(buffer[offset..<(offset + chunkSize)]))
            offset += chunkSize
        }
        if offset > 0 {
            // 목적지가 원본보다 앞이라 전진 복사면 덮어쓰기 사고가 나지 않는다
            // (`OverlappedFramer`가 겹침 구간을 당길 때와 같은 이유로 memmove 대신 루프다).
            for i in 0..<(count - offset) { buffer[i] = buffer[offset + i] }
            count -= offset
        }
        return chunks
    }

    /// 남은 잔여를 통째로 꺼내고 비운다. 청크보다 짧아도 그대로 돌려준다.
    ///
    /// 캡처 쪽에서는 쓰지 않는다 — 정지 시점에는 소비자가 이미 스트림을 놓아 받을 사람이 없고,
    /// 안드로이드도 정지 순간 읽던 버퍼를 버리기 때문이다. 잔여가 실제로 어떻게 남는지를
    /// 테스트가 확인할 수 있게 열어 둔다.
    public func drain() -> [Int16] {
        if count == 0 { return [] }
        let rest = Array(buffer[0..<count])
        count = 0
        return rest
    }

    private func ensureCapacity(_ needed: Int) {
        if needed <= buffer.count { return }
        var newSize = buffer.count
        while newSize < needed { newSize *= 2 }
        buffer.append(contentsOf: [Int16](repeating: 0, count: newSize - buffer.count))
    }
}
