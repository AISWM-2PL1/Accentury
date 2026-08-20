#!/usr/bin/env python3
"""디자인 토큰 대비 검증 (KAN-148).

docs/wiki/design-tokens.md §6 표를 재생성한다. 색을 바꿨으면 이 스크립트의 PAIRS를
같이 고치고 돌린 뒤, 출력한 표를 문서 §6에 반영한다.

    python3 tools/check_contrast.py

기준: 텍스트 4.5:1(1.4.3), 그래픽 오브젝트 3:1(1.4.11). 하나라도 미달이면 종료 코드 1.
"""
import sys

# WCAG 2.1 AA — 일반 텍스트 1.4.3, 그래픽 오브젝트 1.4.11
MIN_TEXT_RATIO = 4.5
MIN_GRAPHIC_RATIO = 3.0

# 시안 색을 그대로 쓰기로 하고 기준 미달을 감수한 항목 (정본 §7).
# 통과로 세지 않고 "감수"로 따로 찍는다 - 목록에서 빼 버리면 검사가 이 화면들을
# 검사한 적 없는 것처럼 보이고, 실패로 두면 CI가 상시 빨간불이라 아무도 안 본다.
# 색을 다시 손볼 때 여기부터 지우면 된다.
WAIVED = {
    "`guide-curve` / `curve-lane-surface` (라이트)",
    "`user-curve` / `curve-lane-surface` (라이트)",
    "`prompt-card-foreground` / `prompt-card-start`",
    "`prompt-card-muted` / `prompt-card-start`",
}


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


# (표시 이름, 전경, 배경) — design-tokens.md §2 의 값과 일치해야 한다
PAIRS = [
    ("`foreground` / `background` (라이트)", "#1e3a5f", "#eff6ff"),
    ("`foreground` / `card` (라이트)", "#1e3a5f", "#ffffff"),
    ("`muted-foreground` / `background` (라이트)", "#4d6f96", "#eff6ff"),
    ("`muted-foreground` / `card` (라이트)", "#4d6f96", "#ffffff"),
    ("`primary-foreground` / `primary` (라이트)", "#ffffff", "#2563eb"),
    ("`secondary-foreground` / `secondary` (라이트)", "#1d4ed8", "#dbeafe"),
    ("`accent-foreground` / `accent` (라이트)", "#78350f", "#fcd34d"),
    ("`destructive-foreground` / `destructive`", "#ffffff", "#dc2626"),
    ("`destructive-on-surface` / `destructive-surface` (라이트)", "#b91c1c", "#fef2f2"),
    ("`success-foreground` / `success`", "#ffffff", "#047857"),
    ("`success-on-surface` / `success-surface` (라이트)", "#047857", "#ecfdf5"),
    ("`prompt-card-foreground` / `prompt-card-start`", "#ffffff", "#3b82f6"),
    ("`prompt-card-muted` / `prompt-card-start`", "#eff6ff", "#3b82f6"),
    ("`prompt-card-foreground` / `prompt-card-end`", "#ffffff", "#1d4ed8"),
    ("`prompt-card-muted` / `prompt-card-end`", "#eff6ff", "#1d4ed8"),
    ("`foreground` / `background` (다크)", "#e2f0ff", "#0f172a"),
    ("`foreground` / `card` (다크)", "#e2f0ff", "#1e293b"),
    ("`muted-foreground` / `background` (다크)", "#7ea8d0", "#0f172a"),
    ("`muted-foreground` / `card` (다크)", "#7ea8d0", "#1e293b"),
    ("`primary-foreground` / `primary` (다크)", "#0f172a", "#3b82f6"),
    ("`secondary-foreground` / `secondary` (다크)", "#93c5fd", "#1e3a5f"),
    ("`accent-foreground` / `accent` (다크)", "#451a03", "#f59e0b"),
    ("`success-on-surface` / `success-surface` (다크)", "#6ee7b7", "#052e23"),
    ("`destructive-on-surface` / `destructive-surface` (다크)", "#fca5a5", "#3f1414"),
    ("`prompt-card-muted` / `prompt-card-start` (다크)", "#eff6ff", "#2563eb"),
    ("`prompt-card-muted` / `prompt-card-end` (다크)", "#eff6ff", "#1e3a8a"),
]

# F0 곡선과 레인 테두리는 텍스트가 아니라 3:1이 기준이다 (WCAG 1.4.11)
GRAPHIC_PAIRS = [
    # 곡선은 레인 안쪽 면(curve-lane-surface) 위에 그려진다 - 카드가 아니다
    ("`guide-curve` / `curve-lane-surface` (라이트)", "#93c5fd", "#ecf4ff"),
    ("`user-curve` / `curve-lane-surface` (라이트)", "#fb923c", "#ecf4ff"),
    ("`guide-curve` / `curve-lane-surface` (다크)", "#7ea8d0", "#182338"),
    ("`user-curve` / `curve-lane-surface` (다크)", "#fb923c", "#182338"),
    ("`control-border` / `background` (라이트)", "#5b7fa8", "#eff6ff"),
    ("`control-border` / `card` (라이트)", "#5b7fa8", "#ffffff"),
    ("`control-border` / `background` (다크)", "#7ea8d0", "#0f172a"),
    ("`control-border` / `card` (다크)", "#7ea8d0", "#1e293b"),
]


def _table(pairs, minimum):
    """표를 찍고 (미달 항목, 감수 항목)을 돌려준다."""
    failures = []
    waived = []
    print("| 조합 | 비율 | |")
    print("|---|---|---|")
    for name, fg, bg in pairs:
        r = ratio(fg, bg)
        if r >= minimum:
            note = ""
        elif name in WAIVED:
            note = f"기준 {minimum} 미달 — 시안 채택으로 감수"
            waived.append((name, fg, bg, r, minimum))
        else:
            note = f"**기준 {minimum} 미달**"
            failures.append((name, fg, bg, r, minimum))
        print(f"| {name} | {r:.2f} | {note} |")
    return failures, waived


def main() -> int:
    failures, waived = _table(PAIRS, MIN_TEXT_RATIO)
    print("\n### 그래픽 오브젝트 (3:1)\n")
    more_failures, more_waived = _table(GRAPHIC_PAIRS, MIN_GRAPHIC_RATIO)
    failures += more_failures
    waived += more_waived

    if failures:
        print(f"\n{len(failures)}건 미달:", file=sys.stderr)
        for name, fg, bg, r, minimum in failures:
            print(f"  {r:.2f} < {minimum}  {name}  ({fg} on {bg})", file=sys.stderr)
        return 1

    total = len(PAIRS) + len(GRAPHIC_PAIRS)
    print(f"\n{total - len(waived)}건 통과, {len(waived)}건 감수 (총 {total}건).")
    if waived:
        print("감수 항목 — 시안 색을 그대로 쓰기로 한 자리다 (정본 §7):")
        for name, fg, bg, r, minimum in waived:
            print(f"  {r:.2f} < {minimum}  {name}  ({fg} on {bg})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
