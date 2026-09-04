#!/usr/bin/env python3
"""웹 링크 미리보기·파비콘·manifest 자산을 만든다 (KAN-179).

앱 아이콘 도상(`assets/app-icon/source.png`)과 등급 캐릭터 원본(`assets/characters/*/source.png`)
하나에서 `web/public/` 아래 파일 전부를 만든다 — 웹에만 있는 원본은 없다. 도상이나 캐릭터를
바꾸려면 그쪽 원본을 갈아끼우고 저쪽 build.py를 돌린 다음 이 스크립트를 돌린다.

산출물:

- `favicon-<VERSION>.svg`          브라우저 탭
- `apple-touch-icon-<VERSION>.png` iOS Safari "홈 화면에 추가" (180×180)
- `icon-192-<VERSION>.png` · `icon-512-<VERSION>.png`   manifest 아이콘
- `og-card-<VERSION>.png`          링크 미리보기 카드 (1200×630)
- `manifest-<VERSION>.webmanifest` 위 아이콘을 가리키는 manifest

**파일명에 버전 토큰이 박히는 이유는 캐시다.** `web-deploy.yml`(KAN-127)은 index.html을 뺀
`dist/` 전부를 `Cache-Control: public, max-age=31536000, immutable`로 올리고, CloudFront 무효화
대상은 `/`와 `/index.html` 둘뿐이다. `public/`의 파일은 Vite가 이름을 그대로 두므로 내용을
바꿔도 브라우저와 CDN이 1년 동안 옛 파일을 준다. 그래서 **교체 = 파일명 변경**이다:
[VERSION]을 올리고 이 스크립트를 다시 돌린 뒤, index.html의 참조를 새 이름으로 바꾸고 옛
파일을 지운다. index.html만 no-cache라 참조가 바뀌는 즉시 새 이름이 나간다.
`manifest`도 같은 이유로 버전을 문다 — 아이콘 파일명이 그 안에 들어 있어서, manifest가 1년
캐시에 박히면 아이콘을 갈아도 옛 이름을 계속 가리킨다.

실행:
    /Users/iseongju/accentury/.venv/bin/python assets/web/build.py

Pillow·numpy·scipy가 필요하다(위 venv에 있다). 끝에 검증표를 찍고 하나라도 FAIL이면 종료 코드 1이다.
"""

from __future__ import annotations

import base64
import importlib.util
import json
import sys
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent  # assets/web
REPO = ROOT.parent.parent
OUT = REPO / "web" / "public"


def _load(name: str, path: Path):
    """옆 build.py를 모듈로 읽는다. 키잉·종이색 정규화를 세 번째로 베끼지 않으려는 것이다.

    두 스크립트의 키잉은 이름만 같고 알맹이가 다르다 — 아이콘 쪽은 배경이 흰색이고 종이가
    도상 안에만 있어 기준색을 도상 안 밝은 면에서 잡고(`normalize_paper`), 캐릭터 쪽은 배경이
    곧 종이색이라 휘도 램프로 민다. 그래서 아이콘은 아이콘 쪽, 캐릭터는 캐릭터 쪽 함수를 쓴다.
    둘 다 `if __name__ == "__main__"` 아래에서만 main()을 부르므로 읽어도 아무것도 만들지 않는다.
    """
    # 옆 폴더에 __pycache__를 남기지 않는다 — 자산 폴더에 생기면 커밋 대상에 섞인다
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"모듈을 읽을 수 없다: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


icon = _load("accentury_app_icon_build", REPO / "assets" / "app-icon" / "build.py")
chars = _load("accentury_characters_build", REPO / "assets" / "characters" / "build.py")

# ── 상수 ────────────────────────────────────────────────────────────────────────────────────

VERSION = "v1"

CREAM = icon.CREAM  # --color-background #F3ECD9
INK = icon.INK  # --color-primary #1C1A17
MUTED = icon.MUTED  # --color-muted-foreground #6B6459
PAPER_SHADOW = icon.PAPER_SHADOW  # 오프셋 그림자 #CFC5AA

FONT_JUA = icon.FONT_JUA
FONT_BODY = chars.FONT_BODY

# 정사각 아이콘 안에서 도상이 차지하는 폭. iOS 앱 아이콘과 같은 값이라 탭·홈 화면·앱 서랍에
# 놓인 같은 도상이 서로 다른 크기로 보이지 않는다.
MOTIF_W = icon.IOS_ICON_MOTIF_W  # 0.78

APPLE_TOUCH_PX = 180  # iOS 홈 화면 아이콘 기준 크기
MANIFEST_PX = (192, 512)
FAVICON_EMBED_PX = 192  # favicon.svg 안에 박는 래스터 변 길이
FAVICON_RADIUS = 0.1875  # 변 대비 모서리 반지름 — iOS 스퀴클에 가깝고 16px에서도 모서리가 보인다

# ── OG 카드 ─────────────────────────────────────────────────────────────────────────────────

OG_W, OG_H = 1200, 630  # og:image 표준 비율 1.91:1
OG_MAX_BYTES = 400 * 1024  # 카카오 상한은 5MB. 링크 미리보기가 1초 안에 뜨는 쪽이 중요하다
OG_PAD = 64
OG_FIGURE_W = 0.38  # 카드 폭 대비 캐릭터 최대 폭
OG_TIER = "wannabe"  # 사투리 호소인 — 손 마이크·확성기가 "말해서 재는 테스트"를 그림만으로 알린다
OG_TITLE = "사투리 좀 치나?"  # 인트로 히어로와 같은 문구 (web/src/intro/IntroScreen.tsx)
OG_BRAND = chars.BRAND_LINE  # "Accentury · 경남 사투리 레벨 테스트"

# ── manifest ────────────────────────────────────────────────────────────────────────────────

APP_NAME = "Accentury"  # 앱 표시 이름 (KAN-178 §1) — strings.xml·CFBundleDisplayName과 같은 값
MANIFEST_NAME = "Accentury · 경남 사투리 레벨 테스트"


def name(stem: str, ext: str) -> str:
    return f"{stem}-{VERSION}.{ext}"


# ==========================================================================================
# 1. 아이콘 — 앱 아이콘 도상 한 장에서
# ==========================================================================================


def icon_motif() -> Image.Image:
    """앱 아이콘 원본을 키잉·정규화·트림해 도상만 남긴다 (KAN-178 build.py와 같은 절차)."""
    with Image.open(icon.ROOT / "source.png") as src:
        cut = icon.key_background(src)
    normalized, _ = icon.normalize_paper(cut)
    return icon.trim(normalized)


def build_square(motif: Image.Image, size: int, path: Path) -> int:
    """크림으로 꽉 찬 정사각 PNG. 투명 배경을 쓰지 않는 이유는 얹히는 바탕을 모르기 때문이다 —
    iOS 홈 화면도 Android 런처도 이 그림 뒤에 자기 배경을 깔지 않는다."""
    canvas = icon.on_canvas(motif, size, size * MOTIF_W, CREAM)
    canvas.convert("RGB").save(path, "PNG", optimize=True)
    return path.stat().st_size


def build_favicon_svg(motif: Image.Image, path: Path) -> int:
    """크림 라운드 사각 + 도상 래스터를 품은 SVG.

    도상 정본이 래스터(생성 모델 산출물)라 벡터가 없다. 손으로 다시 그리면 앱 아이콘과 웹
    파비콘이 서로 다른 원본을 갖게 되고, 도상이 바뀔 때 한쪽만 따라가는 일이 생긴다 —
    KAN-178이 "원본 한 장에서 전부"로 정리한 것을 웹에서 되돌리는 셈이다. 그래서 SVG는 모서리와
    배경만 벡터로 갖고 도상은 원본에서 뽑은 래스터를 그대로 안는다.

    래스터를 data URI로 박는 이유는 참조 파일이 하나 더 늘면 그 파일도 버전 토큰과 캐시 규칙을
    따로 져야 하기 때문이다. 파비콘은 탭·즐겨찾기에서 최대 64px로 그려지므로 192px면 2배 이상 남는다.
    """
    raster = icon.on_canvas(motif, FAVICON_EMBED_PX, FAVICON_EMBED_PX * MOTIF_W, None)
    buf = BytesIO()
    raster.save(buf, "PNG", optimize=True)
    data = base64.b64encode(buf.getvalue()).decode("ascii")
    side = FAVICON_EMBED_PX
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {side} {side}" width="{side}" height="{side}">'
        f'<rect width="{side}" height="{side}" rx="{round(side * FAVICON_RADIUS)}" '
        f'fill="#{CREAM[0]:02x}{CREAM[1]:02x}{CREAM[2]:02x}"/>'
        f'<image x="0" y="0" width="{side}" height="{side}" href="data:image/png;base64,{data}"/>'
        "</svg>\n"
    )
    path.write_text(svg, encoding="utf-8")
    return path.stat().st_size


# ==========================================================================================
# 2. OG 카드
# ==========================================================================================


def draw_og(cut: Image.Image) -> Image.Image:
    """공유 카드(assets/characters/build.py draw_card)와 같은 배치를 1200×630으로 옮긴 것이다.

    다른 점은 오른쪽에 앉는 글자다. 저쪽은 받는 사람이 이미 결과를 아는 자리라 등급명을 넣지만,
    여기는 링크를 처음 본 사람이 보는 카드라 등급이 아니라 **테스트를 권하는 한 줄**이 들어간다.
    """
    card = Image.new("RGBA", (OG_W, OG_H), CREAM + (255,))

    fig = cut.crop(cut.getbbox())
    fig = icon.fit(fig, OG_W * OG_FIGURE_W, OG_H - 2 * OG_PAD)
    card.alpha_composite(fig, (OG_PAD, (OG_H - fig.height) // 2))

    draw = ImageDraw.Draw(card)
    left = OG_PAD + int(OG_W * OG_FIGURE_W) + 56
    avail = OG_W - OG_PAD - left

    # 제목 종이 조각 — 잉크 테두리 + 오프셋 그림자(KAN-161). 카드가 800×400의 1.5배라 그림자와
    # 테두리도 그만큼 굵게 잡는다. 조각이 오른쪽 여백을 넘으면 글자를 줄여 맞춘다.
    pad_x, pad_y = 32, 14
    size = 104
    while True:
        title_font = ImageFont.truetype(str(FONT_JUA), size)
        chip_w = int(draw.textlength(OG_TITLE, font=title_font) + 2 * pad_x)
        if chip_w + 5 <= avail or size <= 56:
            break
        size -= 4
    chip_h = size + 2 * pad_y
    x, y = left, OG_H // 2 - chip_h // 2
    draw.rectangle([x + 5, y + 6, x + chip_w + 5, y + chip_h + 6], fill=PAPER_SHADOW)
    draw.rectangle([x, y, x + chip_w, y + chip_h], fill=CREAM, outline=INK, width=4)
    draw.text((x + pad_x, y + chip_h // 2), OG_TITLE, font=title_font, fill=INK, anchor="lm")

    brand_font = (
        ImageFont.truetype(str(FONT_BODY), 30, index=0)
        if FONT_BODY.exists()
        else ImageFont.truetype(str(FONT_JUA), 30)
    )
    draw.text((left, OG_H - OG_PAD), OG_BRAND, font=brand_font, fill=MUTED, anchor="ls")
    return card.convert("RGB")


# ==========================================================================================
# 3. manifest
# ==========================================================================================


def build_manifest(path: Path) -> int:
    """`display: browser` — 설치형 PWA로 띄우지 않는다.

    이 서비스의 설치 대상은 스토어 앱(KAN-174·175)이다. manifest를 `standalone`으로 두면 크롬이
    "앱 설치" 배너를 띄워 스토어 설치와 두 갈래가 되고, 주소창 없는 창에서 열려 공유·새로고침
    경로까지 달라진다. 여기서 manifest가 하는 일은 홈 화면에 추가했을 때의 **이름과 아이콘**뿐이다.
    """
    manifest = {
        "name": MANIFEST_NAME,
        "short_name": APP_NAME,
        "lang": "ko",
        "start_url": "/",
        "display": "browser",
        "theme_color": f"#{CREAM[0]:02x}{CREAM[1]:02x}{CREAM[2]:02x}",
        "background_color": f"#{CREAM[0]:02x}{CREAM[1]:02x}{CREAM[2]:02x}",
        "icons": [
            {"src": f"/{name(f'icon-{px}', 'png')}", "sizes": f"{px}x{px}", "type": "image/png"}
            for px in MANIFEST_PX
        ],
    }
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path.stat().st_size


# ==========================================================================================


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    motif = icon_motif()

    rows: list[tuple[str, int, str, bool]] = []

    def record(path: Path, size_bytes: int, expect: tuple[int, int] | None, ok: bool) -> None:
        shape = f"{expect[0]}x{expect[1]}" if expect else "-"
        rows.append((path.name, size_bytes, shape, ok))

    # 파비콘
    svg_path = OUT / name("favicon", "svg")
    svg_bytes = build_favicon_svg(motif, svg_path)
    record(svg_path, svg_bytes, None, svg_bytes <= 64 * 1024 and svg_path.read_text().startswith("<svg"))

    # 정사각 래스터 아이콘 — 저장한 파일을 다시 열어 잰다(메모리가 아니라 디스크가 결과다)
    for px, stem in [(APPLE_TOUCH_PX, "apple-touch-icon"), *[(p, f"icon-{p}") for p in MANIFEST_PX]]:
        p = OUT / name(stem, "png")
        b = build_square(motif, px, p)
        with Image.open(p) as saved:
            ok = saved.size == (px, px) and saved.mode == "RGB" and saved.convert("RGB").getpixel((2, 2)) == CREAM
        record(p, b, (px, px), ok)

    # OG 카드
    with Image.open(chars.ROOT / OG_TIER / "source.png") as src:
        figure = chars.key_background(src)
    og_path = OUT / name("og-card", "png")
    og_bytes = chars.save_png_under(draw_og(figure), og_path, OG_MAX_BYTES)
    with Image.open(og_path) as saved:
        og_ok = saved.size == (OG_W, OG_H) and saved.convert("RGB").getpixel((4, 4)) == CREAM
    record(og_path, og_bytes, (OG_W, OG_H), og_ok)

    # manifest — 적힌 아이콘이 실제로 옆에 있는지까지 본다
    man_path = OUT / name("manifest", "webmanifest")
    man_bytes = build_manifest(man_path)
    listed = json.loads(man_path.read_text(encoding="utf-8"))["icons"]
    record(man_path, man_bytes, None, all((OUT / i["src"].lstrip("/")).exists() for i in listed))

    print(f"{'파일':32s} {'bytes':>9s} {'규격':>10s}  판정")
    for file_name, size_bytes, shape, ok in rows:
        print(f"{file_name:32s} {size_bytes:>9,} {shape:>10s}  {'OK' if ok else 'FAIL'}")
    print(f"\nweb/index.html이 가리켜야 할 버전: {VERSION}")
    if not all(ok for *_, ok in rows):
        sys.exit(1)


if __name__ == "__main__":
    main()
