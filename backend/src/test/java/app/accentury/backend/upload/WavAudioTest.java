package app.accentury.backend.upload;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WAV 헤더 해석의 단위 명세 (KAN-23, 명세서 §3.3).
 * <p>
 * 깨진 파일이 어떤 형태든 예외 누출 없이 415 하나로 수렴하는지가 핵심이다.
 */
class WavAudioTest {

    @Test
    void 표준_WAV의_형식과_길이를_해석한다() {
        WavAudio wav = WavAudio.parse(WavFixtures.standardWav(3000));

        assertEquals(16_000, wav.sampleRate());
        assertEquals(1, wav.channels());
        assertEquals(16, wav.bitsPerSample());
        assertEquals(3000, wav.durationMs());
    }

    @Test
    void fmt_앞의_다른_청크는_건너뛴다() {
        byte[] standard = WavFixtures.standardWav(1000);
        // RIFF/WAVE 헤더(12바이트) 뒤에 LIST 청크를 끼워 넣는다
        ByteBuffer buf = ByteBuffer.allocate(standard.length + 12).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(standard, 0, 12);
        buf.put("LIST".getBytes(StandardCharsets.US_ASCII)).putInt(4)
                .put("INFO".getBytes(StandardCharsets.US_ASCII));
        buf.put(standard, 12, standard.length - 12);

        assertEquals(1000, WavAudio.parse(buf.array()).durationMs());
    }

    @Test
    void WAV가_아닌_바이트는_거부한다() {
        assertUnsupported("이것은 WAV가 아닙니다".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 잘린_파일은_거부한다() {
        assertUnsupported(Arrays.copyOf(WavFixtures.standardWav(1000), 30));
    }

    @Test
    void 비_PCM_인코딩은_거부한다() {
        byte[] wav = WavFixtures.standardWav(1000);
        wav[20] = 3;    // fmt 청크의 audioFormat - 3은 IEEE float
        assertUnsupported(wav);
    }

    @Test
    void data_청크가_비어_있으면_거부한다() {
        // 헤더만 있고 샘플이 0개인 파일 (Codex sol 리뷰 P2)
        assertUnsupported(WavFixtures.standardWav(0));
    }

    @Test
    void data_청크가_없으면_거부한다() {
        // fmt 청크까지만 남긴다 (12 + 8 + 16 = 36바이트)
        assertUnsupported(Arrays.copyOf(WavFixtures.standardWav(1000), 36));
    }

    @Test
    void 프레임_단위로_나누어떨어지지_않는_data는_거부한다() {
        // 16-bit mono인데 data가 1바이트 - 완결된 샘플이 없는 깨진 파일 (Codex sol 리뷰 P2)
        byte[] wav = Arrays.copyOf(WavFixtures.standardWav(0), 45);
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 1);
        assertUnsupported(wav);
    }

    @Test
    void byteRate나_blockAlign이_파생값과_다르면_거부한다() {
        // 16kHz mono 16-bit이면 byteRate=32000, blockAlign=2여야 한다 (Codex sol 리뷰 P2)
        byte[] wrongByteRate = WavFixtures.standardWav(1000);
        ByteBuffer.wrap(wrongByteRate).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 999);
        assertUnsupported(wrongByteRate);

        byte[] wrongBlockAlign = WavFixtures.standardWav(1000);
        ByteBuffer.wrap(wrongBlockAlign).order(ByteOrder.LITTLE_ENDIAN).putShort(32, (short) 4);
        assertUnsupported(wrongBlockAlign);
    }

    @Test
    void 선언된_청크_크기가_실제보다_크면_거부한다() {
        byte[] wav = WavFixtures.standardWav(1000);
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(40, Integer.MAX_VALUE);
        assertUnsupported(wav);
    }

    private static void assertUnsupported(byte[] bytes) {
        ApiException rejected = assertThrows(ApiException.class, () -> WavAudio.parse(bytes));
        assertEquals(ErrorCode.AUDIO_FORMAT_UNSUPPORTED, rejected.code());
    }
}
