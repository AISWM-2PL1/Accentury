#!/usr/bin/env python3
"""디자인 토큰 대비 검증 (KAN-148, Papercut 팔레트 KAN-161).

`docs/wiki/design-tokens.md` §6 표를 생성하고, 문서가 낡았는지 검사한다.

    python3 tools/check_contrast.py            # 검사 - 미달이 있거나 문서가 낡았으면 종료 코드 1
    python3 tools/check_contrast.py --write     # §6 표를 다시 쓴다

기준: 텍스트 4.5:1(1.4.3), 그래픽 오브젝트 3:1(1.4.11).

표는 손으로 고치지 않는다 - 문서의 `check_contrast:begin`/`end` 사이를 이 스크립트가
통째로 갈아 끼운다. 손으로 고치면 다음 실행에서 "문서가 낡았다"로 걸린다.
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOC = ROOT / "docs/wiki/design-tokens.md"
BEGIN = "<!-- check_contrast:begin -->"
END = "<!-- check_contrast:end -->"

# WCAG 2.1 AA — 일반 텍스트 1.4.3, 그래픽 오브젝트 1.4.11
MIN_TEXT_RATIO = 4.5
MIN_GRAPHIC_RATIO = 3.0

# 기준 미달을 감수한 항목 (정본 §7).
# KAN-148에서 여덟 자리를 감수했는데 Papercut 팔레트(KAN-161)가 전부 해소했다 -
# 잉크와 크림만 남으니 미달이 나올 조합 자체가 사라졌다. 비워 둔 채로 유지한다:
# 여기에 이름을 하나 넣는 순간 그 화면은 검사에서 빠지는 것과 같다.
WAIVED: set[str] = set()

# Papercut 팔레트 (정본 §2). 값을 바꾸면 여기와 정본을 함께 고친다.
INK = "#1c1a17"
CREAM = "#f3ecd9"
PAPER_SHADOW = "#cfc5aa"
MUTED_INK = "#6b6459"


def _luminance(hex_color: str) -> float:
    h = hex_color.lstrip("#")
    channels = (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))

    def linear(c: float) -> float:
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = (linear(c) for c in channels)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(fg: str, bg: str) -> float:
    lighter, darker = sorted((_luminance(fg), _luminance(bg)), reverse=True)
    return (lighter + 0.05) / (darker + 0.05)


# (표시 이름, 전경, 배경) — design-tokens.md §2 의 값과 일치해야 한다.
# 다크 행은 라이트와 값이 같다 (KAN-161: 다크 분기를 라이트로 고정). 그래도 남겨 둔다 -
# 정본 §2의 다크 표가 살아 있는 한 그 표의 조합도 검사한 적이 있어야 한다.
PAIRS = [
    ("`foreground` / `background` (라이트)", INK, CREAM),
    ("`foreground` / `card` (라이트)", INK, CREAM),
    ("`muted-foreground` / `background` (라이트)", MUTED_INK, CREAM),
    ("`muted-foreground` / `card` (라이트)", MUTED_INK, CREAM),
    ("`primary-foreground` / `primary` (라이트)", CREAM, INK),
    ("`secondary-foreground` / `secondary` (라이트)", INK, CREAM),
    ("`accent-foreground` / `accent` (라이트)", CREAM, INK),
    ("`destructive-foreground` / `destructive`", CREAM, INK),
    ("`destructive-on-surface` / `destructive-surface` (라이트)", INK, CREAM),
    ("`success-foreground` / `success`", CREAM, INK),
    ("`success-on-surface` / `success-surface` (라이트)", INK, CREAM),
    ("`prompt-card-foreground` / `prompt-card-start`", INK, CREAM),
    ("`prompt-card-muted` / `prompt-card-start`", MUTED_INK, CREAM),
    ("`prompt-card-foreground` / `prompt-card-end`", INK, CREAM),
    ("`prompt-card-muted` / `prompt-card-end`", MUTED_INK, CREAM),
    ("`foreground` / `background` (다크)", INK, CREAM),
    ("`foreground` / `card` (다크)", INK, CREAM),
    ("`muted-foreground` / `background` (다크)", MUTED_INK, CREAM),
    ("`muted-foreground` / `card` (다크)", MUTED_INK, CREAM),
    ("`primary-foreground` / `primary` (다크)", CREAM, INK),
    ("`secondary-foreground` / `secondary` (다크)", INK, CREAM),
    ("`accent-foreground` / `accent` (다크)", CREAM, INK),
    ("`success-on-surface` / `success-surface` (다크)", INK, CREAM),
    ("`destructive-on-surface` / `destructive-surface` (다크)", INK, CREAM),
    ("`prompt-card-muted` / `prompt-card-start` (다크)", MUTED_INK, CREAM),
    ("`prompt-card-muted` / `prompt-card-end` (다크)", MUTED_INK, CREAM),
]

# F0 곡선과 레인 테두리는 텍스트가 아니라 3:1이 기준이다 (WCAG 1.4.11).
# 반투명 합성값을 쓰던 자리가 없어졌다 - Papercut에는 알파가 하나도 없다.
GRAPHIC_PAIRS = [
    # 곡선은 레인 안쪽 면(curve-lane-surface) 위에 그려진다 - 카드가 아니다
    ("`guide-curve` / `curve-lane-surface` (라이트)", INK, CREAM),
    ("`user-curve` / `curve-lane-surface` (라이트)", INK, CREAM),
    ("`guide-curve` / `curve-lane-surface` (다크)", INK, CREAM),
    ("`user-curve` / `curve-lane-surface` (다크)", INK, CREAM),
    ("`control-border` / `background` (라이트)", INK, CREAM),
    ("`control-border` / `card` (라이트)", INK, CREAM),
    ("`control-border` / `background` (다크)", INK, CREAM),
    ("`control-border` / `card` (다크)", INK, CREAM),
]

# 상태를 나르지 않는 장식 면. 기준을 재는 대상이 아니라, 왜 기준 대상이 아닌지를
# 문서에 남기려고 값만 재 둔다 (정본 §7).
DECORATIVE = [
    ("`muted` / `background`", PAPER_SHADOW, CREAM),
]


def _table(pairs, minimum, out):
    """표를 out에 찍고 (미달 항목, 감수 항목)을 돌려준다."""
    failures = []
    waived = []
    out.append("| 조합 | 비율 | |")
    out.append("|---|---|---|")
    for name, fg, bg in pairs:
        r = ratio(fg, bg)
        if r >= minimum:
            note = ""
        elif name in WAIVED:
            note = f"기준 {minimum} 미달 — 감수 (§7)"
            waived.append((name, fg, bg, r, minimum))
        else:
            note = f"**기준 {minimum} 미달**"
            failures.append((name, fg, bg, r, minimum))
        out.append(f"| {name} | {r:.2f} | {note} |")
    return failures, waived


def render():
    """§6 블록 본문을 만든다. 반환: (본문, 미달 항목, 감수 항목)"""
    out = []
    failures, waived = _table(PAIRS, MIN_TEXT_RATIO, out)

    total = len(PAIRS) + len(GRAPHIC_PAIRS)
    passed = total - len(waived) - len(failures)
    if waived:
        lead = f"{passed}건이 기준을 넘고 {len(waived)}건은 감수한다 (§7)."
    else:
        lead = f"{total}건 전부 기준을 넘는다 — 감수한 자리는 없다."
    head = [
        "WCAG 2.1 AA 일반 텍스트 기준 4.5:1. `python3 tools/check_contrast.py --write`가 쓴 표이고,",
        lead,
        "",
    ]

    out += ["", "### 그래픽 오브젝트 (3:1)", ""]
    out += [
        "F0 곡선·컨트롤 경계는 텍스트가 아니라 WCAG 2.1 **1.4.11 비텍스트 대비 3:1**이 기준이다.",
        "",
    ]
    more_failures, more_waived = _table(GRAPHIC_PAIRS, MIN_GRAPHIC_RATIO, out)

    out += ["", "### 기준 대상이 아닌 장식 면", ""]
    out += [
        "`muted`는 진척도의 남은 구간처럼 \"없는 것\"을 그리는 면이다. 여기에 상태를 실으면",
        "아래 비율 그대로 안 보이게 되므로, 상태는 잉크로만 알린다 (정본 §7).",
        "",
    ]
    _table(DECORATIVE, 0.0, out)

    return "\n".join(head + out), failures + more_failures, waived + more_waived


def main(argv) -> int:
    block, failures, waived = render()
    write = "--write" in argv[1:]

    text = DOC.read_text(encoding="utf-8")
    head, rest = text.split(BEGIN, 1)
    _stale, tail = rest.split(END, 1)
    fresh = f"{head}{BEGIN}\n{block}\n{END}{tail}"

    if write:
        DOC.write_text(fresh, encoding="utf-8")
        print(f"{DOC.relative_to(ROOT)} §6 표를 다시 썼다.")

    if failures:
        print(f"{len(failures)}건 미달:", file=sys.stderr)
        for name, fg, bg, r, minimum in failures:
            print(f"  {r:.2f} < {minimum}  {name}  ({fg} on {bg})", file=sys.stderr)
        return 1

    if not write and fresh != text:
        print("문서 §6 표가 낡았다 - `python3 tools/check_contrast.py --write`로 다시 쓴다.",
              file=sys.stderr)
        return 1

    total = len(PAIRS) + len(GRAPHIC_PAIRS)
    print(f"{total - len(waived)}건 통과, {len(waived)}건 감수 (총 {total}건).")
    if waived:
        print("감수 항목 (정본 §7):")
        for name, fg, bg, r, minimum in waived:
            print(f"  {r:.2f} < {minimum}  {name}  ({fg} on {bg})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
