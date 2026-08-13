/**
 * 어휘 문항 답안 제출 (KAN-15, API 명세서 §3.5).
 * <p>
 * 어휘 채점은 AI를 거치지 않는다 - 서버가 보유한 정답표({@code correctChoiceId})와
 * 대조해 제출 시점에 정오를 저장하고, 클라이언트에는 정오를 반환하지 않는다 (KAN-13).
 * 저장된 정오는 KAN-21 단어 점수(정답률 x 100)가 {@code /complete}에서 소비한다.
 */
@NullMarked
package app.accentury.backend.vocab;

import org.jspecify.annotations.NullMarked;
