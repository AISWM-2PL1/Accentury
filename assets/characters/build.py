#!/usr/bin/env python3
"""5등급 캐릭터 원본에서 화면용 파생본을 만든다 (KAN-162 1단계).

원본은 `<code>/source.png` — Codex CLI 내장 image_gen이 뽑은 균일 크림 배경 PNG다. 배경을 직접
그린 채로 화면에 올리면 앱 배경(`#F3ECD9`)과 미세하게 다른 사각형이 캐릭터 둘레에 남고, 다크
모드의 크림 halo(KAN-161 규칙)도 붙일 자리가 없다. 그래서 여기서 배경을 투명으로 키잉하고
종이색을 토큰 값으로 정규화한 뒤 두 가지 파생본을 만든다:

- 결과 화면용 WebP  → `web/src/assets/characters/<code>.webp` (폭 780px = 390 CSS px의 2x, 4:5, 투명)
- 카카오 공유 카드   → `assets/share/<code>.png` (800×400, 배경 포함, 좌측 캐릭터 + 우측 등급명)

수작업 리사이즈는 하지 않는다 — 규격·색·문구가 바뀌면 이 파일을 고치고 다시 돌린다.

실행:
    /Users/iseongju/accentury/.venv/bin/python assets/characters/build.py

Pillow·numpy·scipy가 필요하다(위 venv에 있다). 끝에 규격 검증표를 찍는다 — 하나라도 FAIL이면
종료 코드 1이다.
"""

from __future__ import annotations

import sys
from io import BytesIO
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

ROOT = Path(__file__).resolve().parent  # assets/characters
REPO = ROOT.parent.parent
WEB_OUT = REPO / "web" / "src" / "assets" / "characters"
SHARE_OUT = REPO / "assets" / "share"

# 앱 디자인 토큰 (web/src/tokens.css · docs/wiki/design-tokens.md §2). 캐릭터 종이가 화면 종이와
# 같은 값이어야 캐릭터가 "화면 위에 오려 붙인 종이"로 읽힌다.
PAPER = (0xF3, 0xEC, 0xD9)  # --color-background
INK = (0x1C, 0x1A, 0x17)  # --color-primary
MUTED = (0x6B, 0x64, 0x59)  # --color-muted-foreground
PAPER_SHADOW = (0xCF, 0xC5, 0xAA)  # PAPER_SHADOW — 오프셋 그림자 (3px 4px 0)

# 등급 code(소문자) = 백엔드 `application.yml`의 image-url 파일명.
TIERS = [
    ("outsider", "외지인"),
    ("traveler", "여행객"),
    ("wannabe", "사투리 호소인"),
    ("honorary", "명예주민"),
    ("native", "경남 토박이"),
]

# --- 결과 화면용 ---------------------------------------------------------------------------
WEB_WIDTH = 780  # 390 CSS px × 2
WEB_RATIO = (4, 5)
WEB_MARGIN = 0.03  # 캐릭터 바깥 여백 (변 길이 대비)
WEB_MAX_BYTES = 150 * 1024

# --- 공유 카드 -----------------------------------------------------------------------------
CARD_W, CARD_H = 800, 400
CARD_MAX_BYTES = 300 * 1024
CARD_PAD = 40
BRAND_LINE = "Accentury · 경남 사투리 레벨 테스트"

# 키잉 파라미터. 배경은 생성 단계에서 균일(편차 ≤2)이라 허용 오차를 작게 둔다 — 크림 림의
# 종이 두께 선은 배경보다 약 30 어둡고, 그 선이 flood fill을 막는 벽이다.
KEY_TOL = 10  # 이 값 이하로 배경과 가까우면 "배경일 수 있음"
EDGE_SPAN = 40  # 경계 픽셀의 알파를 배경 거리 0..EDGE_SPAN → 0..1 로 편다
LEAK_RADIUS = 3  # 두께 선의 틈(≤ 2×반지름)으로 새어 들어간 fill을 끊는다

FONT_JUA = REPO / "app" / "src" / "main" / "res" / "font" / "jua_regular.ttf"  # SIL OFL 1.1
FONT_BODY = Path("/System/Library/Fonts/AppleSDGothicNeo.ttc")


# ==========================================================================================
# 1. 키잉 + 정규화
# ==========================================================================================


def border_mean(rgb: np.ndarray) -> np.ndarray:
    h, w, _ = rgb.shape
    m = max(8, w // 30)
    strips = [rgb[:m], rgb[-m:], rgb[:, :m], rgb[:, -m:]]
    return np.concatenate([s.reshape(-1, 3) for s in strips]).mean(axis=0)


def key_background(src: Image.Image) -> Image.Image:
    """균일 배경을 투명으로. 바깥에서 닿는 배경만 지우고, 캐릭터 안쪽의 같은 색 면은 남긴다."""
    rgb = np.asarray(src.convert("RGB"), dtype=np.float32)
    bg = border_mean(rgb)
    dist = np.abs(rgb - bg).max(axis=2)  # 채널 최대 차이

    candidate = dist <= KEY_TOL
    labels, _ = ndimage.label(candidate)
    border_labels = np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))
    exterior = np.isin(labels, border_labels[border_labels != 0])

    # 두께 선의 작은 틈으로 새어 들어간 영역 제거: 침식 → 바깥과 이어진 것만 → 팽창
    struct = ndimage.generate_binary_structure(2, 1)
    eroded = ndimage.binary_erosion(exterior, struct, iterations=LEAK_RADIUS, border_value=1)
    lab2, _ = ndimage.label(eroded)
    keep = np.unique(np.concatenate([lab2[0], lab2[-1], lab2[:, 0], lab2[:, -1]]))
    core = np.isin(lab2, keep[keep != 0])
    exterior = ndimage.binary_dilation(core, struct, iterations=LEAK_RADIUS, border_value=1) & exterior

    # 알파: 바깥 = 0, 바깥에 맞닿은 첫 픽셀 띠는 배경 거리로 편다(안티에일리어싱), 나머지 = 1
    ring = ndimage.binary_dilation(exterior, struct, iterations=1) & ~exterior
    alpha = np.ones(dist.shape, dtype=np.float32)
    alpha[exterior] = 0.0
    alpha[ring] = np.clip(dist[ring] / EDGE_SPAN, 0.0, 1.0)

    # 경계 픽셀은 배경과 섞인 색이므로 되돌린다: p = a·c + (1−a)·bg → c = (p − (1−a)·bg)/a
    a3 = alpha[..., None]
    safe = np.where(a3 > 0.02, a3, 1.0)
    unmixed = np.where(a3 > 0.02, (rgb - (1 - a3) * bg) / safe, rgb)
    rgb = np.clip(unmixed, 0, 255)

    # 종이색 정규화: 밝은 픽셀일수록 (PAPER − bg) 델타를 온전히 적용, 잉크(어두움)는 그대로
    lum = rgb.mean(axis=2)
    k = np.clip((lum - 120.0) / 80.0, 0.0, 1.0)[..., None]
    rgb = np.clip(rgb + (np.asarray(PAPER, dtype=np.float32) - bg) * k, 0, 255)

    out = np.dstack([rgb, alpha * 255.0]).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


# ==========================================================================================
# 2. 결과 화면용 WebP
# ==========================================================================================


def fit_canvas(cut: Image.Image) -> Image.Image:
    """알파 bbox로 트림한 뒤 4:5 투명 캔버스 가운데에 놓는다 (여백 WEB_MARGIN)."""
    bbox = cut.getbbox()
    assert bbox, "알파가 전부 0 — 키잉이 캐릭터까지 지웠다"
    cut = cut.crop(bbox)
    cw, ch = cut.size
    rw, rh = WEB_RATIO
    # 캐릭터를 감싸는 최소 4:5 상자 + 여백
    box_w = max(cw, ch * rw / rh)
    box_h = box_w * rh / rw
    box_w, box_h = int(round(box_w * (1 + 2 * WEB_MARGIN))), int(round(box_h * (1 + 2 * WEB_MARGIN)))
    canvas = Image.new("RGBA", (box_w, box_h), (0, 0, 0, 0))
    canvas.paste(cut, ((box_w - cw) // 2, (box_h - ch) // 2), cut)
    return canvas


def save_webp_under(img: Image.Image, path: Path, limit: int) -> tuple[int, int]:
    """quality를 내려가며 상한 아래 첫 값으로 저장. (quality, bytes) 반환."""
    for q in (90, 85, 80, 75, 70, 65, 60):
        buf = BytesIO()
        img.save(buf, "WEBP", quality=q, method=6)
        if buf.tell() <= limit:
            path.write_bytes(buf.getvalue())
            return q, buf.tell()
    raise SystemExit(f"{path.name}: quality 60에서도 {limit:,} bytes 초과")


def build_web(code: str, cut: Image.Image) -> dict:
    canvas = fit_canvas(cut)
    scale = WEB_WIDTH / canvas.width
    web = canvas.resize((WEB_WIDTH, int(round(canvas.height * scale))), Image.LANCZOS)
    WEB_OUT.mkdir(parents=True, exist_ok=True)
    path = WEB_OUT / f"{code}.webp"
    q, size = save_webp_under(web, path, WEB_MAX_BYTES)
    corner_alpha = max(web.getpixel((0, 0))[3], web.getpixel((web.width - 1, web.height - 1))[3])
    return {"path": path, "size": web.size, "bytes": size, "quality": q, "corner_alpha": corner_alpha}


# ==========================================================================================
# 3. 공유 카드
# ==========================================================================================


def load_fonts() -> tuple[ImageFont.FreeTypeFont, ImageFont.FreeTypeFont, ImageFont.FreeTypeFont]:
    """(등급명, 순위 줄, 브랜드 줄). 등급명은 앱과 같은 Jua, 보조 줄은 본문 고딕."""
    if not FONT_JUA.exists():
        raise SystemExit(f"Jua 폰트가 없다: {FONT_JUA}")
    title = ImageFont.truetype(str(FONT_JUA), 76)
    if FONT_BODY.exists():
        brand = ImageFont.truetype(str(FONT_BODY), 22, index=0)
    else:
        brand = ImageFont.truetype(str(FONT_JUA), 22)
    return title, brand


def draw_card(name: str, cut: Image.Image, fonts) -> Image.Image:
    title_font, brand_font = fonts
    card = Image.new("RGBA", (CARD_W, CARD_H), PAPER + (255,))

    # 좌측: 캐릭터. 카드 높이에 맞추되 폭은 카드의 40%를 넘지 않는다(토박이처럼 옆으로 넓은 그림).
    bbox = cut.getbbox()
    fig = cut.crop(bbox)
    max_h = CARD_H - 2 * CARD_PAD
    max_w = int(CARD_W * 0.40)
    s = min(max_h / fig.height, max_w / fig.width)
    fig = fig.resize((int(fig.width * s), int(fig.height * s)), Image.LANCZOS)
    fx = CARD_PAD + (max_w - fig.width) // 2
    fy = (CARD_H - fig.height) // 2
    card.paste(fig, (fx, fy), fig)

    draw = ImageDraw.Draw(card)
    left = CARD_PAD + max_w + 36

    # 우측: 등급명 종이 조각 — 잉크 테두리 + 오프셋 그림자(3px 4px, KAN-161 PAPER_SHADOW).
    # 등급명은 2~7자로 길이가 갈린다("사투리 호소인"). 조각이 카드 오른쪽 여백을 넘으면 글자를
    # 줄여서 맞춘다 — 조각이 잘린 카드가 카톡에 나가는 것이 가장 나쁘다.
    chip_pad_x, chip_pad_y = 22, 10
    avail = CARD_W - CARD_PAD - left
    size = title_font.size
    while True:
        tw = draw.textlength(name, font=title_font)
        chip_w = int(tw + 2 * chip_pad_x)
        if chip_w + 3 <= avail or size <= 40:
            break
        size -= 4
        title_font = ImageFont.truetype(str(FONT_JUA), size)
    chip_h = size + 2 * chip_pad_y
    chip_x, chip_y = left, CARD_H // 2 - chip_h // 2
    draw.rectangle([chip_x + 3, chip_y + 4, chip_x + chip_w + 3, chip_y + chip_h + 4], fill=PAPER_SHADOW)
    draw.rectangle([chip_x, chip_y, chip_x + chip_w, chip_y + chip_h], fill=PAPER, outline=INK, width=3)
    draw.text((chip_x + chip_pad_x, chip_y + chip_h // 2), name, font=title_font, fill=INK, anchor="lm")

    draw.text((left, CARD_H - CARD_PAD), BRAND_LINE, font=brand_font, fill=MUTED, anchor="ls")
    return card.convert("RGB")


def save_png_under(img: Image.Image, path: Path, limit: int) -> int:
    buf = BytesIO()
    img.save(buf, "PNG", optimize=True)
    if buf.tell() > limit:
        # 색이 4가지뿐이라 팔레트로 줄여도 눈에 띄지 않는다
        buf = BytesIO()
        img.quantize(colors=256, method=Image.Quantize.MEDIANCUT).save(buf, "PNG", optimize=True)
    if buf.tell() > limit:
        raise SystemExit(f"{path.name}: {buf.tell():,} bytes > {limit:,}")
    path.write_bytes(buf.getvalue())
    return buf.tell()


# ==========================================================================================


def main() -> None:
    fonts = load_fonts()
    rows = []
    for code, name in TIERS:
        src = Image.open(ROOT / code / "source.png")
        cut = key_background(src)
        web = build_web(code, cut)
        SHARE_OUT.mkdir(parents=True, exist_ok=True)
        card_path = SHARE_OUT / f"{code}.png"
        card_bytes = save_png_under(draw_card(name, cut, fonts), card_path, CARD_MAX_BYTES)
        # 저장된 파일을 다시 열어 잰다 — 메모리의 이미지가 아니라 디스크의 결과가 규격이다
        with Image.open(card_path) as saved:
            card_size = saved.size
            card_corner = saved.convert("RGB").getpixel((4, 4))
        rows.append((code, web, card_bytes, card_size, card_corner))

    ok = True
    print(f"{'code':10s} {'web.webp':>10s} {'size':>9s} {'q':>3s} {'α(모서리)':>8s} {'share.png':>10s}  카드 규격·종이색  판정")
    for code, web, card_bytes, card_size, card_corner in rows:
        checks = [
            web["size"][0] == WEB_WIDTH,
            abs(web["size"][0] / web["size"][1] - 0.8) < 0.01,
            web["bytes"] <= WEB_MAX_BYTES,
            web["corner_alpha"] == 0,
            card_bytes <= CARD_MAX_BYTES,
            # 카카오 피드 카드 규격과 앱 종이색 — 용량만 보면 잘못된 캔버스도 통과한다
            card_size == (CARD_W, CARD_H),
            card_corner == PAPER,
        ]
        ok &= all(checks)
        print(
            f"{code:10s} {web['size'][0]}x{web['size'][1]:<5d} {web['bytes']:>9,} {web['quality']:>3d}"
            f" {web['corner_alpha']:>8d} {card_bytes:>10,}  {card_size[0]}x{card_size[1]}"
            f" #{card_corner[0]:02x}{card_corner[1]:02x}{card_corner[2]:02x}  {'OK' if all(checks) else 'FAIL'}"
        )
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()
