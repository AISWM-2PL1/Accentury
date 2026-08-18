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
            // 컨테이너 크기는 실제 파일 크기와 일치해야 한다 - 어긋난 파일은 디코더마다
            // RIFF 경계 해석이 갈려 분석 단계에서야 실패한다 (Codex sol 리뷰 P2).
            long riffSize = Integer.toUnsignedLong(buf.getInt());
            require(riffSize == bytes.length - 8L);
            require(readTag(buf).equals("WAVE"));

            Integer audioFormat = null;
            int sampleRate = 0;
            int channels = 0;
            int bitsPerSample = 0;
            int byteRate = 0;
            int blockAlign = 0;
            Long dataSize = null;

            // 청크 순회 - fmt와 data 사이에 다른 청크(LIST 등)가 있어도 건너뛴다.
            while (buf.remaining() >= 8 && (audioFormat == null || dataSize == null)) {
                String chunkId = readTag(buf);
                long chunkSize = Integer.toUnsignedLong(buf.getInt());
                require(chunkSize <= buf.remaining());
                if (chunkId.equals("fmt ")) {
                    require(chunkSize >= 16);
                    audioFormat = Short.toUnsignedInt(buf.getShort());
                    channels = Short.toUnsignedInt(buf.getShort());
                    sampleRate = buf.getInt();
                    byteRate = buf.getInt();
                    blockAlign = Short.toUnsignedInt(buf.getShort());
                    bitsPerSample = Short.toUnsignedInt(buf.getShort());
                    skip(buf, chunkSize - 16);
                } else if (chunkId.equals("data")) {
                    dataSize = chunkSize;
                    skip(buf, chunkSize);
                } else {
                    skip(buf, chunkSize);
                }
                // RIFF 규격 - 홀수 크기 청크는 1바이트 패딩된다.
                if (chunkSize % 2 == 1 && buf.hasRemaining()) {
                    buf.get();
                }
            }

            // 샘플이 하나도 없는 헤더뿐인 파일은 녹음이 아니다 (Codex sol 리뷰 P2).
            require(audioFormat != null && dataSize != null && dataSize > 0);
            require(audioFormat == PCM);
            require(sampleRate > 0 && channels > 0 && bitsPerSample >= 8 && bitsPerSample % 8 == 0);

            // 프레임(샘플 x 채널) 단위로 나누어떨어지지 않는 data는 깨진 파일이다 (Codex sol 리뷰 P2).
            long frameSize = (long) channels * (bitsPerSample / 8);
            require(dataSize % frameSize == 0);
            // 선언 필드끼리 어긋난 헤더는 디코더마다 해석이 갈린다 - 파생값과 일치해야 한다 (Codex sol 리뷰 P2).
            require(blockAlign == frameSize);
            require(byteRate == frameSize * sampleRate);

            long bytesPerSecond = frameSize * sampleRate;
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
