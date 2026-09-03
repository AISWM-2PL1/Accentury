package app.accentury.backend.upload;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** 테스트용 WAV 생성기 - 표준 44바이트 헤더 + 무음 데이터 */
public final class WavFixtures {

    private WavFixtures() {
    }

    /** 규격(§3.3) WAV - 16kHz Mono 16-bit PCM */
    public static byte[] standardWav(int durationMs) {
        return wav(16_000, 1, 16, durationMs);
    }

    static byte[] wav(int sampleRate, int channels, int bitsPerSample, int durationMs) {
        int bytesPerSecond = sampleRate * channels * (bitsPerSample / 8);
        int dataSize = (int) ((long) bytesPerSecond * durationMs / 1000);
        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(ascii("RIFF")).putInt(36 + dataSize).put(ascii("WAVE"));
        buf.put(ascii("fmt ")).putInt(16)
                .putShort((short) 1)                                  // PCM
                .putShort((short) channels)
                .putInt(sampleRate)
                .putInt(bytesPerSecond)
                .putShort((short) (channels * bitsPerSample / 8))     // blockAlign
                .putShort((short) bitsPerSample);
        buf.put(ascii("data")).putInt(dataSize);
        return buf.array();
    }

    private static byte[] ascii(String tag) {
        return tag.getBytes(StandardCharsets.US_ASCII);
    }
}
