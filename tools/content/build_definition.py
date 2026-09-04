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

사용
    python3 build_definition.py --guide-f0 ~/Downloads/guide_f0_2026-09-04.json \\
        --test-version gn-2026.09.1 --out ../../backend/src/main/resources/db/migration/V6__gn_2026_09_1.sql

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


def load_guide(path: Path) -> tuple[list[dict], list[str]]:
    """가이드 곡선 파일에서 출시 대상 문장만 뽑는다."""
    data = json.loads(path.read_text(encoding="utf-8"))
    excluded = list(data.get("어절이 통째로 빈 문장", []))
    sentences = [s for s in data["문장"] if s["script_key"] not in set(excluded)]
    return sentences, excluded


def build_items(sentences: list[dict], words: list[tuple], test_version: str) -> list[dict]:
    """풀 정의의 문항 목록 - 음성 N + 어휘 M, seq는 1..N+M 교차 연속이다.

    seq는 레지스트리가 풀 순서를 정하는 유일한 근거다(KAN-10 AC). 배열 순서와 seq가 같게
    두되, 세트를 만들 때 VoiceSets가 다시 매기므로 여기서는 풀 안의 자리만 정한다.
    """
    if len(sentences) != len(words):
        raise SystemExit(
            f"음성 {len(sentences)}문항과 어휘 {len(words)}문항의 수가 다르다. "
            "세트가 한쪽 풀을 되풀이하게 되므로 두 풀을 같은 크기로 맞춰라")

    # 선택지 순서는 testVersion을 시드로 섞는다 - 정답이 늘 첫 자리면 찍어서 맞힐 수 있다.
    # 같은 testVersion이면 언제 돌려도 같은 순서라 발행본이 재현된다.
    rng = random.Random(test_version)

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


def migration_sql(definition: dict, published_at: str, previous_version: str) -> str:
    """발행과 활성 전환을 한 파일에 담는다 - 2단계 롤아웃은 배포 순서로 지킨다.

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
-- 신규 버전 세션이 구 인스턴스에 닿아 404를 받는다 (KAN-101).
insert into test_definition (test_version, dialect, score_version, body, published_at)
values ('{version}', '{definition["dialect"]}', '{definition["scoreVersion"]}', $definition${body}$definition$,
        timestamp with time zone '{published_at}');
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--guide-f0", required=True, type=Path,
                        help="KAN-17 가이드 곡선 산출물 JSON. 갈아 끼우는 자리는 여기다")
    parser.add_argument("--test-version", default="gn-2026.09.1")
    parser.add_argument("--score-version", default="sv-0.3")
    parser.add_argument("--dialect", default="GYEONGNAM")
    parser.add_argument("--published-at", default="2026-09-04T00:00:00Z")
    parser.add_argument("--previous-version", default="gn-2026.08.1")
    parser.add_argument("--out", type=Path, help="마이그레이션 SQL 경로 (생략하면 표준출력)")
    parser.add_argument("--json-out", type=Path, help="발행본 JSON도 따로 남길 경로")
    args = parser.parse_args()

    sentences, excluded = load_guide(args.guide_f0)
    items = build_items(sentences, WORDS, args.test_version)
    definition = {
        "testVersion": args.test_version,
        "scoreVersion": args.score_version,
        "dialect": args.dialect,
        "estimatedDurationSec": ESTIMATED_DURATION_SEC,
        "items": items,
    }

    sql = migration_sql(definition, args.published_at, args.previous_version)
    if args.out:
        args.out.write_text(sql, encoding="utf-8")
    else:
        print(sql)
    if args.json_out:
        args.json_out.write_text(json.dumps(definition, ensure_ascii=False, indent=2),
                                 encoding="utf-8")

    report(sentences, excluded, definition, args, sql)


def report(sentences, excluded, definition, args, sql) -> None:
    """반올림 오차와 크기를 표준출력에 남긴다 - 발행 전에 눈으로 확인할 값이다."""
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
