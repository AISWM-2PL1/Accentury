#!/usr/bin/env python3
"""정본 ↔ 사본 토큰 대조 (KAN-148).

`docs/wiki/design-tokens.md`의 §2 색 표와 §3 타이포 표를 정본으로 삼아, 네이티브
(`Color.kt`·`Type.kt`)와 웹(`tokens.css`)이 같은 값을 들고 있는지 확인한다.

    python3 tools/check_tokens.py

갱신 절차(정본 §1)를 지켰는지 기계로 확인하는 자리다 - 문서에 절차만 적어 두면
한쪽만 고친 커밋이 리뷰를 통과하는 날이 온다. 어긋나면 종료 코드 1.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOC = ROOT / "docs/wiki/design-tokens.md"
KOTLIN = ROOT / "app/src/main/java/com/accentury/app/ui/theme/Color.kt"
KOTLIN_TYPE = ROOT / "app/src/main/java/com/accentury/app/ui/theme/Type.kt"
CSS = ROOT / "web/src/tokens.css"


def normalize(value: str) -> str:
    """`#2563eb`와 `rgba(37, 99, 235, 0.13)`를 비교 가능한 한 꼴(#rrggbbaa)로 만든다."""
    value = value.strip().lower()
    m = re.fullmatch(r"#([0-9a-f]{6})", value)
    if m:
        return f"#{m.group(1)}ff"
    m = re.fullmatch(r"rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)", value)
    if m:
        r, g, b = (int(m.group(i)) for i in (1, 2, 3))
        a = round(float(m.group(4) or 1) * 255)
        return f"#{r:02x}{g:02x}{b:02x}{a:02x}"
    raise ValueError(f"알 수 없는 색 표기: {value!r}")


def kotlin_color(literal: str) -> str:
    """`Color(0xFF2563EB)` → `#2563ebff` (Compose는 AARRGGBB, 표준 표기는 RRGGBBAA)."""
    h = literal.lower()
    return f"#{h[2:]}{h[0:2]}"


def parse_doc():
    """§2의 라이트·다크 표를 읽는다. 반환: {'light': {토큰: 값}, 'dark': {...}}"""
    text = DOC.read_text(encoding="utf-8")
    section = text.split("## 2. 색", 1)[1].split("## 3.", 1)[0]
    light_part, dark_part = section.split("### 다크", 1)
    light_part = light_part.split("### 라이트", 1)[1]

    row = re.compile(r"^\|\s*`([a-z0-9-]+)`\s*\|\s*`([^`]+)`\s*\|")

    def rows(chunk):
        out = {}
        for line in chunk.splitlines():
            m = row.match(line.strip())
            if m:
                out[m.group(1)] = normalize(m.group(2))
        return out

    return {"light": rows(light_part), "dark": rows(dark_part)}


def parse_kotlin():
    text = KOTLIN.read_text(encoding="utf-8")
    found = {"light": {}, "dark": {}}
    for name, literal in re.findall(r"^val\s+(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)", text, re.M):
        if name.startswith("Light"):
            scheme, rest = "light", name[len("Light"):]
        elif name.startswith("Dark"):
            scheme, rest = "dark", name[len("Dark"):]
        else:
            continue
        # PascalCase → kebab-case (PromptCardStart → prompt-card-start)
        token = re.sub(r"(?<!^)(?=[A-Z])", "-", rest).lower()
        found[scheme][token] = kotlin_color(literal)
    return found


def parse_css():
    text = CSS.read_text(encoding="utf-8")
    # 다크는 @media (prefers-color-scheme: dark) 블록 안에만 있다
    dark_start = text.index("prefers-color-scheme: dark")
    light_text, dark_text = text[:dark_start], text[dark_start:]

    decl = re.compile(r"--color-([a-z0-9-]+)\s*:\s*([^;]+);")

    def rows(chunk):
        return {name: normalize(value) for name, value in decl.findall(chunk)}

    return {"light": rows(light_text), "dark": rows(dark_text)}


# 정본 §3의 스타일 이름 → (Type.kt의 M3 슬롯, tokens.css의 변수 접미사)
TYPE_SLOTS = {
    "display": ("displayLarge", "display"),
    "headline": ("headlineMedium", "headline"),
    "title": ("titleLarge", "title"),
    "titleSmall": ("titleMedium", "title-sm"),
    "body": ("bodyLarge", "body"),
    "bodySmall": ("bodyMedium", "body-sm"),
    "label": ("labelLarge", "label"),
    "caption": ("labelSmall", "caption"),
}


def parse_doc_type_sizes():
    """§3 표에서 스타일별 크기(px/sp 숫자)를 읽는다."""
    text = DOC.read_text(encoding="utf-8")
    section = text.split("## 3. 타이포", 1)[1].split("## 4.", 1)[0]
    row = re.compile(r"^\|\s*`(\w+)`\s*\|\s*(\d+)sp\s*\|")
    out = {}
    for line in section.splitlines():
        m = row.match(line.strip())
        if m:
            out[m.group(1)] = int(m.group(2))
    return out


def parse_kotlin_type_sizes():
    """Type.kt의 슬롯별 fontSize."""
    text = KOTLIN_TYPE.read_text(encoding="utf-8")
    out = {}
    for slot, body in re.findall(r"(\w+) = TextStyle\((.*?)\n    \)", text, re.S):
        m = re.search(r"fontSize = (\d+)\.sp", body)
        if m:
            out[slot] = int(m.group(1))
    return out


def parse_css_type_sizes():
    text = CSS.read_text(encoding="utf-8")
    return {
        name: int(value)
        for name, value in re.findall(r"--text-([a-z-]+)\s*:\s*(\d+)px;", text)
    }


def check_typography():
    """정본 §3 · Type.kt · tokens.css의 글자 크기가 같은지."""
    doc = parse_doc_type_sizes()
    kotlin = parse_kotlin_type_sizes()
    css = parse_css_type_sizes()

    problems = []
    if set(doc) != set(TYPE_SLOTS):
        problems.append(f"정본 §3의 스타일 목록이 스크립트의 TYPE_SLOTS와 다르다: {sorted(set(doc) ^ set(TYPE_SLOTS))}")
        return problems, 0

    for style, want in sorted(doc.items()):
        slot, css_name = TYPE_SLOTS[style]
        if kotlin.get(slot) != want:
            problems.append(f"타이포/{style}: Type.kt {slot}={kotlin.get(slot)} ≠ 정본={want}")
        if css.get(css_name) != want:
            problems.append(f"타이포/{style}: tokens.css --text-{css_name}={css.get(css_name)} ≠ 정본={want}")

    # 대사 카드는 ux-ui.md §5가 24 이상을 요구한다 - 값이 바뀌어도 이 선은 지켜야 한다
    if doc.get("headline", 0) < 24:
        problems.append(f"타이포/headline: 대사 카드가 {doc.get('headline')}sp - ux-ui.md §5의 24sp 최소선 미달")

    return problems, len(doc)


def main() -> int:
    doc = parse_doc()
    kotlin = parse_kotlin()
    css = parse_css()

    problems = []
    for scheme in ("light", "dark"):
        expected = doc[scheme]
        if not expected:
            problems.append(f"{scheme}: 정본 문서에서 색 표를 못 읽었다")
            continue
        for token, want in sorted(expected.items()):
            for label, actual in (("Color.kt", kotlin[scheme]), ("tokens.css", css[scheme])):
                got = actual.get(token)
                if got is None:
                    problems.append(f"{scheme}/{token}: {label}에 없다 (정본 {want})")
                elif got != want:
                    problems.append(f"{scheme}/{token}: {label}={got} ≠ 정본={want}")

        for label, actual in (("Color.kt", kotlin[scheme]), ("tokens.css", css[scheme])):
            for token in sorted(set(actual) - set(expected)):
                problems.append(f"{scheme}/{token}: {label}에만 있고 정본에 없다")

    type_problems, type_count = check_typography()
    problems += type_problems

    if problems:
        print(f"{len(problems)}건 어긋남:", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        print("\n정본(docs/wiki/design-tokens.md §2)을 고치고 두 사본을 함께 맞춘다.", file=sys.stderr)
        return 1

    total = len(doc["light"]) + len(doc["dark"])
    print(f"색 토큰 {total}개가 정본·Color.kt·tokens.css 세 곳에서 일치한다.")
    print(f"타이포 {type_count}개가 정본·Type.kt·tokens.css 세 곳에서 일치한다 (대사 카드 24sp 이상).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
