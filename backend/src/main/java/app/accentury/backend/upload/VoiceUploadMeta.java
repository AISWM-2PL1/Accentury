package app.accentury.backend.upload;

import app.accentury.backend.common.ApiException;
import app.accentury.backend.common.ErrorCode;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 업로드 multipart의 {@code meta} 파트 (API 명세서 §3.3).
 * <p>
 * {@code durationMs}와 {@code clientQuality} 4개 필드 모두 필수다 (2026-08-06 확정).
 * 앱(KAN-87)과 웹(KAN-56)이 확인 단계에서 이미 산출하는 값이며, 서버가 무음 등
 * 명백한 불량을 AI 추론 전에 조기 거절하는 데 쓸 수 있다 (활용은 KAN-24).
 * 누락이나 형식 오류는 전부 400 {@link ErrorCode#VALIDATION_FAILED}다.
 */
record VoiceUploadMeta(@Nullable Long durationMs, @Nullable ClientQuality clientQuality) {

    record ClientQuality(
            @Nullable Double rms,
            @Nullable Double peak,
            @Nullable Double silenceRatio,
            @Nullable Boolean clipped) {
    }

    static VoiceUploadMeta parse(ObjectMapper objectMapper, @Nullable String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "meta 파트가 필요합니다.");
        }
        VoiceUploadMeta meta;
        try {
            meta = objectMapper.readValue(metaJson, VoiceUploadMeta.class);
        } catch (JacksonException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "meta 파트가 올바른 JSON이 아닙니다.");
        }
        // JSON 리터럴 "null"은 예외 없이 null로 역직렬화된다 - 500이 아니라 400이어야 한다 (Codex sol 리뷰 P2)
        if (meta == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "meta 파트가 비어 있습니다.");
        }
        meta.validate();
        return meta;
    }

    private void validate() {
        require(durationMs != null && durationMs > 0, "meta.durationMs는 양수여야 합니다.");
        require(clientQuality != null, "meta.clientQuality가 필요합니다.");
        require(clientQuality.rms() != null && clientQuality.peak() != null
                        && clientQuality.silenceRatio() != null && clientQuality.clipped() != null,
                "meta.clientQuality는 rms, peak, silenceRatio, clipped 4개 필드가 모두 필요합니다.");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, message);
        }
    }
}
