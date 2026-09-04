package app.accentury.backend.result;

import app.accentury.backend.analysis.AnalysisJob;
import app.accentury.backend.analysis.AnalysisStatusService;
import app.accentury.backend.testdefinition.TestDefinition;
import app.accentury.backend.vocab.VocabAnswer;
import app.accentury.backend.vocab.VocabAnswerRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 완주 판정 - 세션의 문항별 제출 상태를 미제출/실패/분석 중/채점 입력으로 접는다.
 * <p>
 * {@code /complete}(KAN-16, §3.6)와 {@code /result}(KAN-25, §3.7)가 같은 판정을 쓴다
 * (2026-08-14 확정) - 두 API가 같은 세션을 다르게 말하면 클라이언트가 어느 화면으로
 * 안내할지 알 수 없다. 갈래를 응답으로 바꾸는 몫은 각 서비스다: 우선순위(미제출 >
 * 실패 > 분석 중)는 같고, 분석 중만 /complete는 200 PROCESSING, /result는 409
 * RESULT_NOT_READY로 낸다.
 * <p>
 * 문항 순회는 정의의 seq 순서라 결과 목록도 항상 그 순서다. 음성 문항의 판정은
 * 대표 상태({@link AnalysisStatusService#representativeByItem}) 그대로다 - 대기
 * 화면(§3.4)과 완료 판정이 같은 문항을 다르게 말하지 않고, 대표가 COMPLETED인
 * 작업이 곧 채점 대상(최신 성공 시도, §5.1)이라 별도 선정도 필요 없다.
 */
@Component
class CompletionJudge {

    /**
     * 판정 결과 - 목록 세 개는 서로 배타이고 전부 비어 있으면 완주다.
     *
     * @param missingItems          업로드된 시도(음성)나 답안(어휘)이 없는 문항 (§5.1 - 로컬 재녹음은 시도가 아니다).
     * @param retakeItems           시도가 전부 실패로 끝난 문항 - 재녹음(새 시도)으로만 풀린다.
     * @param pendingItems          대표 상태가 분석 중인 문항
     * @param intonationScoreByItem 음성 itemId → 채점 대상(최신 성공 시도)의 AI 원점수 0~100
     * @param chosenChoiceIdByItem  어휘 itemId → 제출된 choiceId
     */
    record Judgment(List<String> missingItems, List<String> retakeItems, List<String> pendingItems,
                    Map<String, Integer> intonationScoreByItem,
                    Map<String, String> chosenChoiceIdByItem) {
    }

    private final AnalysisStatusService analysisStatusService;
    private final VocabAnswerRepository vocabAnswerRepository;

    CompletionJudge(AnalysisStatusService analysisStatusService,
                    VocabAnswerRepository vocabAnswerRepository) {
        this.analysisStatusService = analysisStatusService;
        this.vocabAnswerRepository = vocabAnswerRepository;
    }

    /**
     * @param definition 세션의 세트 정의 ({@code TestDefinitionRegistry#sessionDefinition}) - 풀 정의를
     *                   넣으면 세트 밖 문항이 전부 미제출로 잡힌다 (KAN-182).
     */
    Judgment judge(String sessionId, TestDefinition definition) {
        Map<String, AnalysisJob> representatives = analysisStatusService.representativeByItem(sessionId);
        Map<String, VocabAnswer> answers = vocabAnswerRepository.findBySessionId(sessionId).stream()
                .collect(Collectors.toMap(VocabAnswer::itemId, Function.identity()));

        List<String> missing = new ArrayList<>();
        List<String> retake = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        Map<String, Integer> intonationScoreByItem = new HashMap<>();
        Map<String, String> chosenChoiceIdByItem = new HashMap<>();
        for (TestDefinition.Item item : definition.items()) {
            switch (item.type()) {
                case VOICE -> {
                    AnalysisJob representative = representatives.get(item.itemId());
                    if (representative == null) {
                        missing.add(item.itemId());
                    } else {
                        switch (representative.status()) {
                            case PROCESSING -> pending.add(item.itemId());
                            // COMPLETED의 점수가 null이면 데이터 오염이다 - 집계가 크게 실패한다 (KAN-21).
                            case COMPLETED -> intonationScoreByItem.put(item.itemId(),
                                    representative.intonationScore());
                            // FAILED(재녹음 무익)도 retake로 묶는다 (2026-08-13 확정, Codex sol 리뷰
                            // P2 기각) - §3.7이 실패 종류를 구분하지 않고, 새 시도가 세션 내 유일한
                            // 복구 경로다. 문항별 retryable의 정본은 §3.4 상태 조회이고, FAILED가
                            // 반복되면 시도 상한(§2.5) → 429 → 재응시(§3.1)로 수렴한다.
                            case RETRYABLE_FAILED, FAILED -> retake.add(item.itemId());
                            // 상태가 추가되면 조용한 문항 누락 대신 여기서 즉시 실패한다.
                            default -> throw new IllegalStateException(
                                    "완주 판정 규칙이 없는 분석 상태다: " + representative.status());
                        }
                    }
                }
                case VOCABULARY -> {
                    VocabAnswer answer = answers.get(item.itemId());
                    if (answer == null) {
                        missing.add(item.itemId());
                    } else {
                        chosenChoiceIdByItem.put(item.itemId(), answer.choiceId());
                    }
                }
            }
        }
        // 점수 맵은 Map.copyOf를 쓰지 않는다 - COMPLETED인데 점수가 null인 오염 데이터는
        // 여기서 뜻 없는 NPE가 아니라 집계 검증(ScoreAggregator)의 명시적 실패로 잡는다.
        return new Judgment(List.copyOf(missing), List.copyOf(retake), List.copyOf(pending),
                intonationScoreByItem, chosenChoiceIdByItem);
    }
}
