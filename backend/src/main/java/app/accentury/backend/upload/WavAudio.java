package app.accentury.backend.upload;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 업로드된 WAV의 헤더 해석 결과 (KAN-23, API 명세서 §3.3).
 * <p>
 * 재생 시간은 클라이언트 신고값(meta.durationMs)이 아니라 data 청크 크기에서
 * 직접 계산한다 - 길이 제한(§3.3)의 정본은 서버가 본 바이트다.
 *
 * @param sampleRate    샘플레이트 (Hz)
 * @param channels      채널 수
 * @param bitsPerSample 샘플당 비트 수
 * @param durationMs    data 청크 크기로 계산한 재생 시간 (ms)
 */
record WavAudio(int sampleRate, int channels, int bitsPerSample, long durationMs) {

    private static final int PCM = 1;

    /**
     * RIFF/WAVE 헤더를 해석한다. WAV가 아니거나 깨진 파일, 비 PCM 인코딩은
     * 전부 415 {@link ErrorCode#AUDIO_FORMAT_UNSUPPORTED}다.
     */
    static WavAudio parse(byte[] bytes) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            require(readTag(buf).equals("RIFF"));
            buf.getInt();   // RIFF 전체 크기 - data 청크 크기를 쓰므로 참조하지 않는다
            require(readTag(buf).equals("WAVE"));

            Integer audioFormat = null;
            int sampleRate = 0;
            int channels = 0;
            int bitsPerSample = 0;
            Long dataSize = null;

            // 청크 순회 - fmt와 data 사이에 다른 청크(LIST 등)가 있어도 건너뛴다
            while (buf.remaining() >= 8 && (audioFormat == null || dataSize == null)) {
                String chunkId = readTag(buf);
                long chunkSize = Integer.toUnsignedLong(buf.getInt());
                require(chunkSize <= buf.remaining());
                if (chunkId.equals("fmt ")) {
                    require(chunkSize >= 16);
                    audioFormat = Short.toUnsignedInt(buf.getShort());
                    channels = Short.toUnsignedInt(buf.getShort());
                    sampleRate = buf.getInt();
                    buf.getInt();    // byteRate
                    buf.getShort();  // blockAlign
                    bitsPerSample = Short.toUnsignedInt(buf.getShort());
                    skip(buf, chunkSize - 16);
                } else if (chunkId.equals("data")) {
                    dataSize = chunkSize;
                    skip(buf, chunkSize);
                } else {
                    skip(buf, chunkSize);
                }
                // RIFF 규격 - 홀수 크기 청크는 1바이트 패딩된다
                if (chunkSize % 2 == 1 && buf.hasRemaining()) {
                    buf.get();
                }
            }

            // 샘플이 하나도 없는 헤더뿐인 파일은 녹음이 아니다 (Codex sol 리뷰 P2)
            require(audioFormat != null && dataSize != null && dataSize > 0);
            require(audioFormat == PCM);
            require(sampleRate > 0 && channels > 0 && bitsPerSample >= 8 && bitsPerSample % 8 == 0);

            long bytesPerSecond = (long) sampleRate * channels * (bitsPerSample / 8);
            return new WavAudio(sampleRate, channels, bitsPerSample, dataSize * 1000 / bytesPerSecond);
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
        }
    }

    private static String readTag(ByteBuffer buf) {
        byte[] tag = new byte[4];
        buf.get(tag);
        return new String(tag, StandardCharsets.US_ASCII);
    }

    private static void skip(ByteBuffer buf, long count) {
        require(count <= buf.remaining());
        buf.position(buf.position() + (int) count);
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new ApiException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
        }
    }
}
