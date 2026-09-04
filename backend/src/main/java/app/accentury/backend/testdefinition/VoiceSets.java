package app.accentury.backend.testdefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 문항 풀을 세트로 나누는 규칙 (KAN-182, 2026-09-01 확정 · 2026-09-04 어휘 풀 확장).
 * <p>
 * 세트는 발행본에 손으로 나열하지 않고 여기서 유도한다 - 풀 크기가 바뀌어도 규칙만 따르면
 * 되고, 사람이 나누다 빠뜨리거나 겹치는 실수가 없다. 규칙은 순수 산술이라 같은 풀이면
 * 어느 인스턴스에서 유도해도 같은 세트다 (다중 인스턴스에서 세트 번호가 어긋나지 않는다).
 * <p>
 * 풀은 <b>둘</b>이다 - 음성 풀(N개)과 어휘 풀(M개). 세트 하나는 각 풀에서 5개씩 가져온다.
 * 어휘도 나누는 것은 2026-09-04 결정이다: 세트가 29개인데 어휘 5문항이 고정이면 어느 세트를
 * 응시하든 같은 어휘를 본다. 클래스와 API 이름이 {@code voiceSet}인 것은 KAN-182가 이미
 * 명세와 클라이언트에 그 이름으로 나간 뒤라서다 - 지금은 음성만이 아니라 세트 번호 자체를
 * 가리킨다.
 * <ul>
 *   <li>풀 = 정의의 VOICE 문항 목록과 VOCABULARY 문항 목록, 각각 seq 오름차순이 풀 순서다
 *       (poolIndex 1..N). 발행본의 배열 순서가 아니라 seq를 쓰는 것은 레지스트리가 모든 순서를
 *       seq로 고정하기 때문이다 (KAN-10 AC - 배열 순서에 의존하지 않는다).</li>
 *   <li>세트 수 = ceil(max(N, M) / 5). 큰 쪽을 기준으로 잡아야 큰 풀의 문항이 어느 세트에도
 *       실리지 못하고 남는 일이 없다.</li>
 *   <li>세트 k가 어느 풀에서 가져오는 자리는 (k-1)*5부터 5칸이고, 풀 크기를 넘으면 풀의
 *       처음으로 돌아간다(순환). 그래서 작은 풀은 세트마다 되풀이되고, 5의 배수인 풀은 세트마다
 *       겹치지 않는 5개가 나온다. 가져온 문항은 풀의 원래 문항 그대로다 (사본 없음).</li>
 *   <li>풀이 5개 미만이면 발행 거부다 - 순환해도 한 세트 안에 같은 문항이 두 번 들어간다.
 *       M = 5면 어휘는 세트마다 같은 5문항이라 현행과 같다 (하위 호환).</li>
 *   <li>세트 안 출제 순서는 현행대로 음성과 어휘를 교차한다 (v, w, v, w, ...). seq 1..10은
 *       세트를 만들 때 부여한다.</li>
 * </ul>
 * 순환은 09-01 규칙("마지막 세트가 모자라면 풀의 처음부터 채운다")과 같은 결과를 낸다 -
 * N = 34의 세트 7은 양쪽 규칙 모두 poolIndex 31, 32, 33, 34, 1이다. 나머지 연산으로 적으면
 * 두 풀에 같은 식을 쓸 수 있어 규칙이 하나로 준다.
 * <p>
 * 예: N = 34, M = 5면 세트 7개이고 세트 7의 음성은 poolIndex 31, 32, 33, 34, 1, 어휘는 매
 * 세트가 1, 2, 3, 4, 5다. N = M = 145면 세트 29개이고 양쪽 다 순환 없이 딱 나뉜다.
 */
final class VoiceSets {

    /** 세트 하나가 각 풀에서 가져오는 문항 수이자 풀의 최소 크기 (문항 구성 확정 2026-07-27). */
    static final int SET_SIZE = 5;

    private VoiceSets() {
    }

    /**
     * 두 풀에서 유도되는 세트 수 = ceil(max(N, M) / 5).
     *
     * @param voicePoolSize      음성 풀 크기 N
     * @param vocabularyPoolSize 어휘 풀 크기 M
     * @throws IllegalArgumentException 어느 한쪽이라도 5개 미만일 때
     */
    static int setCount(int voicePoolSize, int vocabularyPoolSize) {
        requirePoolSize(voicePoolSize, "음성 문장");
        requirePoolSize(vocabularyPoolSize, "어휘");
        int larger = Math.max(voicePoolSize, vocabularyPoolSize);
        return (larger + SET_SIZE - 1) / SET_SIZE;
    }

    /**
     * 세트 k(1부터)가 크기 {@code poolSize}인 풀에서 가져오는 poolIndex(1부터) 5개.
     * <p>
     * 자리는 (k-1)*5부터 5칸이고 풀 크기를 넘으면 풀의 처음으로 돌아간다. 세트 번호가 그 풀
     * 하나만으로 셈한 세트 수를 넘어도 된다 - 세트 수는 <b>큰 쪽</b> 풀이 정하므로, 작은 풀이
     * 되풀이해 채우는 것이 정상 동작이다.
     *
     * @throws IllegalArgumentException 풀이 5개 미만이거나 세트 번호가 1 미만일 때
     */
    static List<Integer> poolIndexes(int poolSize, int set) {
        requirePoolSize(poolSize, "문항");
        if (set < 1) {
            throw new IllegalArgumentException("세트 번호는 1부터다: " + set);
        }
        List<Integer> indexes = new ArrayList<>(SET_SIZE);
        // long으로 셈한다 - set이 커도 (set-1)*5가 int를 넘어 음수 인덱스가 되지 않는다.
        long first = (long) (set - 1) * SET_SIZE;
        for (int offset = 0; offset < SET_SIZE; offset++) {
            indexes.add((int) ((first + offset) % poolSize) + 1);
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
        List<TestDefinition.Item> vocabularyPool = pool.items().stream()
                .filter(item -> item.type() == TestDefinition.ItemType.VOCABULARY)
                .toList();
        int setCount = setCount(voicePool.size(), vocabularyPool.size());

        List<TestDefinition> sets = new ArrayList<>(setCount);
        for (int set = 1; set <= setCount; set++) {
            sets.add(new TestDefinition(pool.testVersion(), pool.scoreVersion(), pool.dialect(),
                    pool.estimatedDurationSec(),
                    interleave(pick(voicePool, set), pick(vocabularyPool, set))));
        }
        return List.copyOf(sets);
    }

    /** 세트 k가 이 풀에서 가져오는 문항 5개 - 풀의 원래 문항 그대로다. */
    private static List<TestDefinition.Item> pick(List<TestDefinition.Item> pool, int set) {
        return poolIndexes(pool.size(), set).stream()
                .map(index -> pool.get(index - 1))
                .toList();
    }

    /** v, w, v, w, ... 교차에 seq 1..10 부여 - 양쪽 다 5개라 정확히 짝이 맞는다. */
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

    private static void requirePoolSize(int poolSize, String what) {
        if (poolSize < SET_SIZE) {
            throw new IllegalArgumentException(
                    what + " 풀은 " + SET_SIZE + "개 이상이어야 한다: " + poolSize);
        }
    }
}
