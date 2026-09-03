package app.accentury.backend.testdefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 음성 문장 풀을 세트로 나누는 규칙 (KAN-182, 2026-09-01 확정).
 * <p>
 * 세트는 발행본에 손으로 나열하지 않고 여기서 유도한다 - 풀 크기가 바뀌어도 규칙만 따르면
 * 되고, 사람이 나누다 빠뜨리거나 겹치는 실수가 없다. 규칙은 순수 산술이라 같은 풀이면
 * 어느 인스턴스에서 유도해도 같은 세트다 (다중 인스턴스에서 세트 번호가 어긋나지 않는다).
 * <ul>
 *   <li>풀 = 정의의 VOICE 문항 목록, seq 오름차순이 풀 순서다 (poolIndex 1..N). 발행본의
 *       배열 순서가 아니라 seq를 쓰는 것은 레지스트리가 모든 순서를 seq로 고정하기 때문이다
 *       (KAN-10 AC - 배열 순서에 의존하지 않는다).</li>
 *   <li>세트 수 = ceil(N / 5). 세트 k = 풀의 (k-1)*5+1 번째부터 k*5 번째까지.</li>
 *   <li>마지막 세트가 5개에 못 미치면 부족한 만큼 풀의 처음부터 순서대로 채운다. N이 5의
 *       배수면 채우지 않는다. 채워진 문항은 풀의 원래 문항 그대로다 (사본 없음).</li>
 *   <li>N < 5는 발행 거부다 - 채워도 한 세트 안에 같은 문항이 두 번 들어간다. N >= 5면
 *       채움 문항이 마지막 세트의 자기 문항과 겹치지 않는다 (채움 수 5-r < N-r+1).
 *       N = 5면 세트 1개로 현행과 같다 (하위 호환).</li>
 *   <li>세트 안 출제 순서는 현행대로 음성과 어휘를 교차한다 (v, w, v, w, ...). seq 1..10은
 *       세트를 만들 때 부여한다.</li>
 * </ul>
 * 예: N = 34면 세트 7개이고 세트 7은 poolIndex 31, 32, 33, 34, 1이다.
 */
final class VoiceSets {

    /** 세트 하나의 음성 문항 수이자 풀의 최소 크기 (문항 구성 확정 2026-07-27: 음성 5). */
    static final int SET_SIZE = 5;

    private VoiceSets() {
    }

    /** 풀 크기 N의 세트 수 = ceil(N / 5). */
    static int setCount(int poolSize) {
        requirePoolSize(poolSize);
        return (poolSize + SET_SIZE - 1) / SET_SIZE;
    }

    /**
     * 세트 k(1부터)의 poolIndex(1부터) 5개 - 위 규칙 그대로다. 채움 문항은 뒤에 붙는다.
     *
     * @throws IllegalArgumentException 풀이 5개 미만이거나 세트 번호가 범위 밖일 때
     */
    static List<Integer> poolIndexes(int poolSize, int set) {
        int setCount = setCount(poolSize);
        if (set < 1 || set > setCount) {
            throw new IllegalArgumentException("세트 번호가 범위 밖이다: " + set + " (세트 수 " + setCount + ")");
        }
        List<Integer> indexes = new ArrayList<>(SET_SIZE);
        int first = (set - 1) * SET_SIZE + 1;
        for (int index = first; index <= Math.min(set * SET_SIZE, poolSize); index++) {
            indexes.add(index);
        }
        for (int fill = 1; indexes.size() < SET_SIZE; fill++) {
            indexes.add(fill);
        }
        return List.copyOf(indexes);
    }

    /**
     * 풀 정의에서 세트 정의 전부를 유도한다. 입력은 검증을 통과한 풀 정의(seq 오름차순)여야 한다.
     * <p>
     * 세트 정의의 문항은 풀의 원래 문항 그대로이고 seq만 1..10으로 새로 매긴다 - 세트를
     * 어느 순서로 응시하든 클라이언트는 현행과 같은 10문항 교차 목록을 받는다.
     *
     * @return 세트 번호 순(1..세트 수)의 세트 정의 목록
     */
    static List<TestDefinition> derive(TestDefinition pool) {
        List<TestDefinition.Item> voicePool = pool.items().stream()
                .filter(item -> item.type() == TestDefinition.ItemType.VOICE)
                .toList();
        List<TestDefinition.Item> vocabulary = pool.items().stream()
                .filter(item -> item.type() == TestDefinition.ItemType.VOCABULARY)
                .toList();
        int setCount = setCount(voicePool.size());

        List<TestDefinition> sets = new ArrayList<>(setCount);
        for (int set = 1; set <= setCount; set++) {
            List<TestDefinition.Item> voices = poolIndexes(voicePool.size(), set).stream()
                    .map(index -> voicePool.get(index - 1))
                    .toList();
            sets.add(new TestDefinition(pool.testVersion(), pool.scoreVersion(), pool.dialect(),
                    pool.estimatedDurationSec(), interleave(voices, vocabulary)));
        }
        return List.copyOf(sets);
    }

    /** v, w, v, w, ... 교차에 seq 1..10 부여 - 어휘가 5개라 음성 5개와 정확히 짝이 맞는다. */
    private static List<TestDefinition.Item> interleave(List<TestDefinition.Item> voices,
                                                       List<TestDefinition.Item> vocabulary) {
        List<TestDefinition.Item> items = new ArrayList<>(voices.size() + vocabulary.size());
        int seq = 1;
        for (int i = 0; i < Math.max(voices.size(), vocabulary.size()); i++) {
            if (i < voices.size()) {
                items.add(voices.get(i).withSeq(seq++));
            }
            if (i < vocabulary.size()) {
                items.add(vocabulary.get(i).withSeq(seq++));
            }
        }
        return List.copyOf(items);
    }

    private static void requirePoolSize(int poolSize) {
        if (poolSize < SET_SIZE) {
            throw new IllegalArgumentException(
                    "음성 문장 풀은 " + SET_SIZE + "개 이상이어야 한다: " + poolSize);
        }
    }
}
