/**
 * 음성 문항 multipart 직접 업로드 (KAN-23, API 명세서 §3.3).
 * <p>
 * Presigned URL과 S3 없이 클라이언트가 백엔드로 WAV를 직접 보낸다 (2026-07-22 결정).
 * 오디오는 요청 처리 중에만 메모리에 존재하고 영속 저장소에 기록되지 않는다 (FR-DP-01).
 */
@NullMarked
package app.accentury.backend.upload;

import org.jspecify.annotations.NullMarked;
