import Foundation

/// 안드로이드 `audio/WavWriter.kt`의 1:1 이식본. 16kHz 모노 16bit PCM RIFF 컨테이너를 만든다.
///
/// 바이트 순서를 리틀엔디언으로 **명시**해서 쓴다. 애플 기기는 어차피 리틀엔디언이지만 WAV 규격이
/// 리틀엔디언이라 호스트 바이트 순서에 기대면 안 되고, 안드로이드도 같은 이유로
/// `ByteOrder.LITTLE_ENDIAN`을 못박아 두었다.
public enum WavWriter {

    public static func write(file: URL, pcm: [Int16], sampleRate: Int = AccenturyCore.sampleRate) throws {
        try toWavBytes(pcm, sampleRate: sampleRate).write(to: file)
    }

    /// 업로드는 파일을 거치지 않고 메모리에서 바로 멀티파트로 실어 보낸다 (KAN-88).
    public static func toWavBytes(_ pcm: [Int16], sampleRate: Int = AccenturyCore.sampleRate) -> Data {
        var wav = header(pcmByteCount: pcm.count * 2, sampleRate: sampleRate)
        wav.reserveCapacity(44 + pcm.count * 2)
        for sample in pcm { appendInt16LE(&wav, sample) }
        return wav
    }

    private static func header(pcmByteCount: Int, sampleRate: Int) -> Data {
        let channels = 1
        let bitsPerSample = 16
        let byteRate = sampleRate * channels * bitsPerSample / 8

        var header = Data()
        header.reserveCapacity(44)
        header.append(contentsOf: Array("RIFF".utf8))
        appendInt32LE(&header, Int32(pcmByteCount + 36))
        header.append(contentsOf: Array("WAVE".utf8))
        header.append(contentsOf: Array("fmt ".utf8))
        appendInt32LE(&header, 16)
        appendInt16LE(&header, 1)
        appendInt16LE(&header, Int16(channels))
        appendInt32LE(&header, Int32(sampleRate))
        appendInt32LE(&header, Int32(byteRate))
        appendInt16LE(&header, Int16(channels * bitsPerSample / 8))
        appendInt16LE(&header, Int16(bitsPerSample))
        header.append(contentsOf: Array("data".utf8))
        appendInt32LE(&header, Int32(pcmByteCount))
        return header
    }

    private static func appendInt32LE(_ data: inout Data, _ value: Int32) {
        let bits = UInt32(bitPattern: value)
        data.append(UInt8(truncatingIfNeeded: bits))
        data.append(UInt8(truncatingIfNeeded: bits >> 8))
        data.append(UInt8(truncatingIfNeeded: bits >> 16))
        data.append(UInt8(truncatingIfNeeded: bits >> 24))
    }

    private static func appendInt16LE(_ data: inout Data, _ value: Int16) {
        let bits = UInt16(bitPattern: value)
        data.append(UInt8(truncatingIfNeeded: bits))
        data.append(UInt8(truncatingIfNeeded: bits >> 8))
    }
}
