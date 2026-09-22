#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""발행본 JSON과 Flyway 마이그레이션을 만든다 (KAN-182 콘텐츠 발행).

재료 셋을 합쳐 테스트 정의 하나를 낸다.

    가이드 곡선  KAN-17 산출물 (--guide-f0). 문장별 script_key, 대본, guideF0를 준다
    어휘 문항    vocabulary_gn.py의 WORDS
    버전 정보    --test-version, --score-version

**가이드 곡선을 갈아 끼우는 자리는 --guide-f0 하나다.** 새 산출물이 오면 경로만 바꿔 다시
돌리고, 나온 SQL을 새 testVersion의 마이그레이션으로 넣는다 - 발행본은 발행 후 불변이라
기존 정의를 UPDATE하지 않는다 (KAN-26). 이 스크립트는 발행본 JSON을 손으로 고치지 않게
하려고 있다.

**KAN-220 재베이스라인 (2026-09-21) 뒤의 파일 배치.** 아래 예시의 --out 파일(옛 V6, V8, V10, V11)은
전부 삭제됐고, 마지막 발행본 gn-2026.09.4의 INSERT 문은 backend/src/main/resources/db/migration/
V1__baseline.sql이 바이트 단위 그대로 품고 있다 (스키마 DDL 뒤). 예시는 각 발행본을 어떻게 만들었는지의
기록으로 남긴다 - 같은 재료로 다시 돌리면 V1 안의 INSERT와 같은 본문이 나와야 한다. 다음 발행은
V2__<version>.sql부터 다시 번호를 매긴다. V1 파일의 주석에는 달러 인용 구분자 문자열을 적지 않는다 -
앱, iOS, 웹의 발행본 전수 검사가 파일에서 처음 만나는 구분자 두 개 사이를 정의 JSON으로 읽는다.

사용 (첫 발행, 옛 V6)
    python3 build_definition.py --guide-f0 ~/Downloads/guide_f0_2026-09-04.json \\
        --test-version gn-2026.09.1 --out ../../backend/src/main/resources/db/migration/V6__gn_2026_09_1.sql

점수 버전만 바꾼 재발행 (KAN-200 - gn-2026.09.2 = gn-2026.09.1 본문 + sv-0.4)
    python3 build_definition.py --guide-f0 ~/Downloads/guide_f0_2026-09-04.json \\
        --test-version gn-2026.09.2 --score-version sv-0.4 --same-content-as gn-2026.09.1 \\
        --published-at 2026-09-10T00:00:00Z \\
        --out ../../backend/src/main/resources/db/migration/V8__gn_2026_09_2_sv_0_4.sql

--same-content-as는 선택지 섞기 시드를 그 버전으로 고정해 문항 본문이 바이트 단위로 같게
하고, 마이그레이션 머리말을 재발행용으로 바꾼다. 정의는 발행 후 불변이라(KAN-26) 점수
버전을 바꾸는 유일한 길이 새 testVersion 발행이다 (§5.4).

대본만 바꾼 재발행 (KAN-210 - gn-2026.09.3 = gn-2026.09.2 본문에 09-01 인계본 대본)
    python3 build_definition.py --guide-f0 ~/Downloads/guide_f0_2026-09-04.json \\
        --sentences ~/Desktop/handoff_sentences_2026-09-15.json \\
        --test-version gn-2026.09.3 --score-version sv-0.4 --choice-seed gn-2026.09.1 \\
        --sentences-from gn-2026.09.2 --published-at 2026-09-15T00:00:00Z \\
        --out ../../backend/src/main/resources/db/migration/V10__gn_2026_09_3_sentences.sql

인계본 2차 (KAN-210 - gn-2026.09.4 = 문장 3개가 빠진 인계본 + 곡선 결측 문장 3개 복귀로 145 유지)
    python3 build_definition.py --guide-f0 ~/Downloads/guide_f0_2026-09-04.json \\
        --sentences ~/Desktop/handoff_sentences_2026-09-15-2.json \\
        --include-excluded "2|43,2|71,2|79" \\
        --test-version gn-2026.09.4 --score-version sv-0.4 --choice-seed gn-2026.09.1 \\
        --sentences-from gn-2026.09.3 --published-at 2026-09-15T09:00:00Z \\
        --out ../../backend/src/main/resources/db/migration/V11__gn_2026_09_4_sentences.sql

--sentences는 KAN-159 전달본의 문장 목록 JSON이다. 가이드 곡선 문장의 대본을 script_key로
찾은 인계본 대본으로 덮어쓴다. 가이드 문장 중 인계본에 없는 키는 출시 문항에서 뺀다 - 인계본에서
빠진 문장은 더 이상 채점하지 않으므로(인계본 규칙) 남겨 둘 수 없다. 대본이 바뀌어도
scriptKey와 guideF0는 그대로다 - 인계본이 어절 수를 지키며 철자만 고쳤고 참조 본체도 그대로라서다.
새 대본의 띄어쓰기 어절 수가 인계본의 "어절" 값과 다르면 표준출력에 경고를 남긴다 (가이드 곡선이
어절당 20점 격자라 그 문장은 곡선과 대본의 어절 수가 어긋난다).

--include-excluded는 "어절이 통째로 빈 문장" 가운데 출시 문항으로 되돌릴 키다. 인계본이 문장을
빼서 음성 풀이 어휘 풀보다 작아졌을 때 두 풀을 같은 크기로 맞추는 용도다 (2026-09-15 결정). 그
문장은 곡선에 어절 하나가 빈 채로 실리고 프론트가 끊어 그린다 (KAN-102 AC3). 목록에 없는 키는
멈춘다. 자리는 가이드 곡선 파일의 순서다.

--choice-seed는 어휘 선택지 섞기 시드다. 생략하면 testVersion이다. 대본만 바꾼 재발행은 원본의
시드를 넘겨 어휘 문항(선택지 순서, 정답 choiceId)을 바이트 단위로 같게 한다 - e2e_smoke.py의
세트 1 정답 수 표를 그대로 쓰기 위해서다.

가이드 곡선 파일에 기대하는 것 (2026-09-04 전달본 기준)
    {"문장": [{"script_key": "1|1", "대본": "...", "어절": 11,
               "guideF0": {"unit": "semitone", "frameIntervalMs": 21.5, "values": [...]}}],
     "어절이 통째로 빈 문장": ["2|1", ...]}

"어절이 통째로 빈 문장"은 출시 문항에서 뺀다 (박재영 2026-09-04). 그 어절은 참조 화자
대부분이 대본과 다른 말을 해 자리가 비었고, 곡선에 구멍으로 남는다.

frameIntervalMs는 반올림해 싣는다 (2026-09-04 결정). 산출물의 값은 어절당 20점 정규화라
문장마다 다른 실수인데 발행본 스키마와 앱·웹이 이 필드를 정수로 읽는다. 가이드 레인은 절대
시간이 아니라 자기 길이로 폭 전체를 쓰므로(docs/wiki/pitch-curve.md §4) 반올림 오차가 화면에
드러나지 않는다. 오차는 이 스크립트가 실행할 때마다 표준출력에 찍는다.
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from vocabulary_gn import WORDS  # noqa: E402

#: 세트 하나가 각 풀에서 가져오는 문항 수 (VoiceSets.SET_SIZE와 같아야 한다)
SET_SIZE = 5

#: 선택지 라벨 - choiceId는 "<itemId><라벨>"이다 (기존 발행본 규칙)
CHOICE_LABELS = "abcd"

#: 예상 소요 시간 (초). 음성 5문항 × 약 18초(대본 읽기 + 녹음, 참조 발화 중앙 4.7초에
#: 화면 전환과 준비를 더한 값) + 어휘 5문항 × 약 12초 + 여유. 더미 정의의 240초는 음성
#: 문장이 "밥 뭇나?" 한 마디였을 때의 값이라 문장이 길어진 만큼 올렸다.
ESTIMATED_DURATION_SEC = 300


def load_guide(path: Path, include_excluded: list[str] | None = None) -> tuple[list[dict], list[str]]:
    """가이드 곡선 파일에서 출시 대상 문장만 뽑는다.

    include_excluded는 "어절이 통째로 빈 문장" 가운데 출시 문항으로 되돌릴 키다 (모듈 주석 참고).
    돌려주는 excluded는 되돌린 키를 뺀 나머지다.
    """
    data = json.loads(path.read_text(encoding="utf-8"))
    excluded = list(data.get("어절이 통째로 빈 문장", []))
    unknown = [k for k in (include_excluded or []) if k not in excluded]
    if unknown:
        raise SystemExit(f"--include-excluded의 키가 \"어절이 통째로 빈 문장\"에 없다: {' '.join(unknown)}")
    excluded = [k for k in excluded if k not in set(include_excluded or [])]
    sentences = [s for s in data["문장"] if s["script_key"] not in set(excluded)]
    return sentences, excluded


def apply_sentences(sentences: list[dict], path: Path) -> tuple[list[tuple[str, str, str]], list[str]]:
    """인계본(KAN-159 문장 목록)의 대본을 script_key로 덮어쓴다 (KAN-210).

    sentences를 제자리에서 고치고, 대본이 바뀐 문장의 (script_key, 이전 대본, 새 대본) 목록과
    인계본에 없어 뺀 키 목록을 돌려준다. 인계본 규칙상 인계본에 없는 키는 더 이상 채점하지
    않으므로 출시 문항에서 뺀다. 어절 수가 인계본의 "어절" 값과 어긋나면 경고만 남긴다 -
    곡선(어절당 20점)과 대본이 어긋나는 문장이라 사람이 봐야 하지만, 인계본이 정본이라
    스크립트가 고치지 않는다.
    """
    handoff = json.loads(path.read_text(encoding="utf-8"))
    by_key = {s["script_key"]: s for s in handoff["문장"]}
    dropped = [s["script_key"] for s in sentences if s["script_key"] not in by_key]
    sentences[:] = [s for s in sentences if s["script_key"] in by_key]
    changed: list[tuple[str, str, str]] = []
    for s in sentences:
        entry = by_key[s["script_key"]]
        new = entry["대본"]
        if len(new.split()) != entry["어절"]:
            print(f"[경고]   {s['script_key']} 대본 어절 {len(new.split())} != 인계본 어절 {entry['어절']}"
                  f" (곡선 격자와 어긋난다): {new}", file=sys.stderr)
        if new != s["대본"]:
            changed.append((s["script_key"], s["대본"], new))
            s["대본"] = new
    return changed, dropped


def build_items(sentences: list[dict], words: list[tuple], choice_seed: str) -> list[dict]:
    """풀 정의의 문항 목록 - 음성 N + 어휘 M, seq는 1..N+M 교차 연속이다.

    seq는 레지스트리가 풀 순서를 정하는 유일한 근거다(KAN-10 AC). 배열 순서와 seq가 같게
    두되, 세트를 만들 때 VoiceSets가 다시 매기므로 여기서는 풀 안의 자리만 정한다.

    choice_seed는 선택지 섞기의 시드다. 보통 testVersion이고, 점수 버전만 바꾼 재발행은
    원본 testVersion을 넘겨 선택지 순서(정답 choiceId)까지 같게 한다 (KAN-200).
    """
    if len(sentences) != len(words):
        raise SystemExit(
            f"음성 {len(sentences)}문항과 어휘 {len(words)}문항의 수가 다르다. "
            "세트가 한쪽 풀을 되풀이하게 되므로 두 풀을 같은 크기로 맞춰라")

    # 선택지 순서는 testVersion을 시드로 섞는다 - 정답이 늘 첫 자리면 찍어서 맞힐 수 있다.
    # 같은 시드면 언제 돌려도 같은 순서라 발행본이 재현된다.
    rng = random.Random(choice_seed)

    items: list[dict] = []
    seq = 1
    for index, (sentence, word) in enumerate(zip(sentences, words), start=1):
        items.append(voice_item(f"v{index}", seq, sentence))
        seq += 1
        items.append(vocabulary_item(f"w{index}", seq, word, rng))
        seq += 1
    return items


def topic_particle(word: str) -> str:
    """낱말 뒤에 붙일 보조사 - 받침이 있으면 "은", 없으면 "는".

    문항 문구가 "'구룸'는 표준어로..."처럼 나오면 눈에 걸린다. 145문항 중 30개가
    받침으로 끝나므로 손으로 적지 않고 여기서 갈라 붙인다.
    """
    last = word[-1]
    if not ("\uac00" <= last <= "\ud7a3"):
        return "는"
    return "은" if (ord(last) - 0xAC00) % 28 else "는"


def voice_item(item_id: str, seq: int, sentence: dict) -> dict:
    guide = sentence["guideF0"]
    return {
        "itemId": item_id,
        "seq": seq,
        "type": "VOICE",
        "prompt": sentence["대본"],
        "scriptKey": sentence["script_key"],
        "guideF0": {
            "unit": guide["unit"],
            # 반올림해 정수로 (모듈 주석 참고). 실수를 그대로 실으면 BE·앱 파싱이 깨진다.
            "frameIntervalMs": round(guide["frameIntervalMs"]),
            "values": guide["values"],
        },
    }


def vocabulary_item(item_id: str, seq: int, word: tuple, rng: random.Random) -> dict:
    dialect, answer, wrong, ask, _source, _confidence = word
    texts = [answer, *wrong]
    rng.shuffle(texts)
    choices = [{"choiceId": item_id + CHOICE_LABELS[i], "text": text}
               for i, text in enumerate(texts)]
    particle = topic_particle(dialect)
    prompt = (f"'{dialect}'{particle} 표준어로 무엇일까요?" if ask == "표준어"
              else f"'{dialect}'{particle} 무슨 뜻일까요?")
    return {
        "itemId": item_id,
        "seq": seq,
        "type": "VOCABULARY",
        "prompt": prompt,
        "choices": choices,
        "correctChoiceId": choices[texts.index(answer)]["choiceId"],
    }


def migration_sql(definition: dict, published_at: str, previous_version: str,
                  same_content_as: str | None = None, sentences_from: str | None = None,
                  sentences_note: str = "") -> str:
    """발행 마이그레이션 - 활성 전환은 담지 않는다 (2단계 롤아웃은 배포 순서로 지킨다).

    달러 인용($definition$)을 쓰는 것은 본문에 작은따옴표가 들어 있어서다 - 어휘 문항의
    "'정구지'는 표준어로 무엇일까요?" 같은 문구다 (V2와 같은 이유).
    """
    body = json.dumps(definition, ensure_ascii=False, indent=2)
    if "$definition$" in body:
        raise SystemExit("본문에 달러 인용 구분자가 들어 있다 - 다른 구분자를 써야 한다")
    version = definition["testVersion"]
    voices = sum(1 for item in definition["items"] if item["type"] == "VOICE")
    vocabulary = len(definition["items"]) - voices
    sets = (max(voices, vocabulary) + SET_SIZE - 1) // SET_SIZE
    if same_content_as:
        header = reissue_header(definition, same_content_as, voices, vocabulary, sets)
    elif sentences_from:
        header = sentences_header(definition, sentences_from, voices, vocabulary, sets, sentences_note)
    else:
        header = first_content_header(previous_version, voices, vocabulary, sets)

    return f"""\
{header}
insert into test_definition (test_version, dialect, score_version, body, published_at)
values ('{version}', '{definition["dialect"]}', '{definition["scoreVersion"]}', $definition${body}$definition$,
        timestamp with time zone '{published_at}');
"""


def reissue_header(definition: dict, same_content_as: str, voices: int, vocabulary: int, sets: int) -> str:
    """점수 버전만 바꾼 재발행의 머리말 (KAN-200)."""
    return f"""\
-- KAN-200: {same_content_as}의 본문 그대로 scoreVersion만 {definition["scoreVersion"]}로 바꾼 재발행 -
-- 음성 {voices}문항 + 어휘 {vocabulary}문항 = 세트 {sets}개.
--
-- 문항 본문(문장, scriptKey, guideF0, 어휘 선택지와 정답)은 {same_content_as}과 바이트 단위로
-- 같다. 선택지 섞기 시드를 {same_content_as}으로 고정해 만들었다. 이 파일은 손으로 쓰지 않는다 -
-- tools/content/build_definition.py --same-content-as {same_content_as} 가 만든다.
--
-- 새 testVersion을 발행하는 이유는 점수 버전 전환이다. 활성 점수 버전은 따로 지정하는 값이
-- 아니라 활성 정의가 선언한 scoreVersion을 따르고(§5.4, ScorePolicyRegistry), 정의는 발행 후
-- 불변이라(KAN-26) {definition["scoreVersion"]} 전환 = 새 정의 발행이다. 세션은 생성 시점의
-- scoreVersion에 고정되므로 전환 전 세션은 그대로 {same_content_as}의 점수 버전으로 집계된다.
--
-- 활성 전환은 이 파일이 하지 않는다. 2단계 롤아웃(KAN-26)이라 새 정의를 먼저 배포하고
-- 활성 전환은 그 다음 PUT /admin/v0/active-version 호출이다 - 순서가 뒤집히면 배포 중
-- 신규 버전 세션이 구 인스턴스에 닿아 404를 받는다 (KAN-101)."""


def sentences_header(definition: dict, sentences_from: str, voices: int, vocabulary: int,
                     sets: int, note: str) -> str:
    """음성 문장만 바꾼 재발행의 머리말 (KAN-210). note는 main이 만든 인계본 요약 줄이다."""
    return f"""\
-- KAN-210: {sentences_from}의 본문에 음성 문항만 KAN-159 전달본의 갱신 문장 목록(사람 선별 +
-- 읽기 쉬운 표준 철자)으로 바꾼 재발행 - 음성 {voices}문항 + 어휘 {vocabulary}문항 = 세트 {sets}개.
-- {note}
--
-- 남은 문항의 scriptKey와 guideF0, 어휘 문항(선택지 순서와 정답 choiceId)은 {sentences_from}과
-- 바이트 단위로 같다. 인계본이 어절 수를 지키며 철자만 고쳤고 참조 본체(08-31b)도 그대로라 곡선과
-- 참조는 다시 내지 않는다. 선택지 섞기 시드를 원본으로 고정해 만들었다. 이 파일은 손으로 쓰지
-- 않는다 - tools/content/build_definition.py --sentences 가 만든다.
--
-- 새 testVersion을 발행하는 이유는 정의가 발행 후 불변이라서다(KAN-26). 대본 한 글자를 고치는
-- 것도 새 정의 발행이다. scoreVersion은 {definition["scoreVersion"]} 그대로다.
--
-- 활성 전환은 이 파일이 하지 않는다. 2단계 롤아웃(KAN-26)이라 새 정의를 먼저 배포하고
-- 활성 전환은 그 다음 PUT /admin/v0/active-version 호출이다 - 순서가 뒤집히면 배포 중
-- 신규 버전 세션이 구 인스턴스에 닿아 404를 받는다 (KAN-101)."""


def first_content_header(previous_version: str, voices: int, vocabulary: int, sets: int) -> str:
    """첫 실콘텐츠 발행(KAN-182)의 머리말."""
    return f"""\
-- KAN-182: 정본 콘텐츠 발행 - 음성 {voices}문항 + 어휘 {vocabulary}문항 = 세트 {sets}개.
--
-- 더미 정의({previous_version})를 대신할 첫 실콘텐츠다. 음성 문장과 scriptKey는 KAN-159
-- 전달본, 가이드 곡선은 KAN-17 산출물(guide_f0_2026-09-04.json), 어휘는
-- tools/content/vocabulary_gn.py가 정본이다. 이 파일은 손으로 쓰지 않는다 -
-- tools/content/build_definition.py가 그 셋을 합쳐 만든다.
--
-- 가이드 곡선에 허용 밴드가 없다. KAN-17 산출물의 1안이 중앙선만 내기로 했고(박재영
-- 2026-09-04), 그에 맞춰 발행 검증의 bandLow·bandHigh를 optional로 되돌렸다
-- (TestDefinitionRegistry.validateVoice - 2026-08-09 확정을 뒤집은 것이다).
--
-- guideF0.frameIntervalMs는 산출물의 실수를 반올림한 값이다 (2026-09-04 결정). 발행본
-- 스키마와 앱·웹이 정수로 읽고, 가이드 레인은 자기 길이로 폭 전체를 쓰므로 오차가 화면에
-- 드러나지 않는다. values의 null은 무성 구간과 유효 발화 10명 미만인 칸이고 프론트가
-- 끊어 그린다 (KAN-102 AC3).
--
-- 활성 전환은 이 파일이 하지 않는다. 2단계 롤아웃(KAN-26)이라 새 정의를 먼저 배포하고
-- 활성 전환은 그 다음 PUT /admin/v0/active-version 호출이다 - 순서가 뒤집히면 배포 중
-- 신규 버전 세션이 구 인스턴스에 닿아 404를 받는다 (KAN-101)."""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--guide-f0", required=True, type=Path,
                        help="KAN-17 가이드 곡선 산출물 JSON. 갈아 끼우는 자리는 여기다")
    parser.add_argument("--test-version", default="gn-2026.09.1")
    parser.add_argument("--score-version", default="sv-0.3")
    parser.add_argument("--dialect", default="GYEONGNAM")
    parser.add_argument("--published-at", default="2026-09-04T00:00:00Z")
    parser.add_argument("--previous-version", default="gn-2026.08.1",
                        help="첫 실콘텐츠 머리말에서 '대신하는 더미 정의'로 적는 이름 (옛 V6 기록용). "
                             "--same-content-as, --sentences 재발행의 머리말에는 쓰이지 않는다")
    parser.add_argument("--same-content-as", metavar="TEST_VERSION",
                        help="점수 버전만 바꾼 재발행 - 이 버전의 선택지 순서를 그대로 쓰고 "
                             "머리말을 재발행용으로 바꾼다 (KAN-200)")
    parser.add_argument("--sentences", type=Path, metavar="HANDOFF_JSON",
                        help="KAN-159 전달본 문장 목록 - 가이드 곡선 문장의 대본을 script_key로 "
                             "덮어쓴다 (KAN-210)")
    parser.add_argument("--sentences-from", metavar="TEST_VERSION",
                        help="--sentences 재발행의 원본 testVersion - 머리말에 적는다 (KAN-210)")
    parser.add_argument("--include-excluded", metavar="KEY[,KEY...]", default="",
                        help="\"어절이 통째로 빈 문장\" 가운데 출시 문항으로 되돌릴 script_key - "
                             "음성 풀을 어휘 풀 크기에 맞출 때 쓴다 (KAN-210)")
    parser.add_argument("--choice-seed", metavar="TEST_VERSION",
                        help="어휘 선택지 섞기 시드 (생략하면 testVersion). 대본만 바꾼 재발행은 "
                             "원본 testVersion을 넘겨 어휘 문항을 그대로 둔다 (KAN-210)")
    parser.add_argument("--out", type=Path, help="마이그레이션 SQL 경로 (생략하면 표준출력)")
    parser.add_argument("--json-out", type=Path, help="발행본 JSON도 따로 남길 경로")
    args = parser.parse_args()

    included = [k.strip() for k in args.include_excluded.split(",") if k.strip()]
    sentences, excluded = load_guide(args.guide_f0, included)
    if args.same_content_as == args.test_version:
        raise SystemExit("--same-content-as는 다른 testVersion이어야 한다 - 정의는 발행 후 불변이다 (KAN-26)")
    if args.same_content_as and args.sentences:
        raise SystemExit("--same-content-as와 --sentences는 같이 쓸 수 없다 - 대본이 바뀌면 같은 본문이 아니다")
    if bool(args.sentences) != bool(args.sentences_from):
        raise SystemExit("--sentences와 --sentences-from은 같이 써야 한다 - 머리말이 원본 testVersion을 적는다")
    if args.sentences_from == args.test_version:
        raise SystemExit("--sentences-from은 다른 testVersion이어야 한다 - 정의는 발행 후 불변이다 (KAN-26)")
    changed, dropped = apply_sentences(sentences, args.sentences) if args.sentences else ([], [])
    note = ""
    if args.sentences:
        stamp = json.loads(args.sentences.read_text(encoding="utf-8")).get("stamp", "?")
        note = (f"인계본 {args.sentences.name}(stamp {stamp}): 대본이 바뀐 문항 {len(changed)}개, "
                f"인계본에서 빠져 뺀 문항 {len(dropped)}개({' '.join(dropped) or '없음'}), "
                f"곡선 결측이라 빼 뒀다가 되돌린 문항 {len(included)}개({' '.join(included) or '없음'}).")
    items = build_items(sentences, WORDS, args.choice_seed or args.same_content_as or args.test_version)
    definition = {
        "testVersion": args.test_version,
        "scoreVersion": args.score_version,
        "dialect": args.dialect,
        "estimatedDurationSec": ESTIMATED_DURATION_SEC,
        "items": items,
    }

    sql = migration_sql(definition, args.published_at, args.previous_version, args.same_content_as,
                        args.sentences_from, note)
    if args.out:
        args.out.write_text(sql, encoding="utf-8")
    else:
        print(sql)
    if args.json_out:
        args.json_out.write_text(json.dumps(definition, ensure_ascii=False, indent=2),
                                 encoding="utf-8")

    report(sentences, excluded, definition, args, sql, changed, dropped, included)


def report(sentences, excluded, definition, args, sql, changed, dropped, included) -> None:
    """반올림 오차와 크기를 표준출력에 남긴다 - 발행 전에 눈으로 확인할 값이다."""
    if args.sentences:
        print(f"[대본]   인계본으로 바뀐 음성 문항 {len(changed)}개 / {len(sentences)}", file=sys.stderr)
        print(f"[삭제]   인계본에서 빠져 뺀 문항 {len(dropped)}개: {' '.join(dropped)}", file=sys.stderr)
        print(f"[복귀]   곡선 결측 목록에서 되돌린 문항 {len(included)}개: {' '.join(included)}", file=sys.stderr)
        for key, old, new in changed:
            print(f"         {key}\n           전: {old}\n           후: {new}", file=sys.stderr)
    errors = []
    for s in sentences:
        interval = s["guideF0"]["frameIntervalMs"]
        count = len(s["guideF0"]["values"])
        exact = interval * (count - 1)
        rounded = round(interval) * (count - 1)
        errors.append(abs(rounded - exact) / exact * 100)
    errors.sort()

    voices = sum(1 for item in definition["items"] if item["type"] == "VOICE")
    vocabulary = len(definition["items"]) - voices
    print(f"[발행본] {definition['testVersion']}"
          f" 음성 {voices} + 어휘 {vocabulary} = 세트 {(max(voices, vocabulary) + 4) // 5}개",
          file=sys.stderr)
    print(f"[제외]   어절이 통째로 빈 문장 {len(excluded)}개: {' '.join(excluded)}", file=sys.stderr)
    print(f"[반올림] frameIntervalMs 곡선 길이 오차 중앙 {errors[len(errors) // 2]:.2f}%"
          f" · 최대 {errors[-1]:.2f}%", file=sys.stderr)
    nulls = sum(1 for s in sentences if any(v is None for v in s["guideF0"]["values"]))
    print(f"[결측]   values에 null이 있는 문장 {nulls}개 (프론트가 끊어 그린다)", file=sys.stderr)
    print(f"[크기]   마이그레이션 {len(sql.encode('utf-8')) / 1024:.0f}KB", file=sys.stderr)


if __name__ == "__main__":
    main()
