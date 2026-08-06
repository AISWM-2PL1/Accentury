/**
 * 음성 분석 작업(시도)의 저장과 전달 경계 (KAN-23 생성, KAN-24 관리).
 * <p>
 * 업로드 1건 = 분석 작업 1건이다. 재녹음은 새 작업(새 attempt)으로 쌓이고
 * 이전 시도를 덮어쓰지 않는다 (API 명세서 §5.1). 상태 전이와 AI 호출,
 * 상태 조회 API는 KAN-24에서 구현한다.
 */
@NullMarked
package app.accentury.backend.analysis;

import org.jspecify.annotations.NullMarked;
