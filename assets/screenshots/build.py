#!/usr/bin/env python3
"""기기 캡처 한 장을 스토어 스크린샷 규격의 프레임에 앉힌다 (KAN-178 3단계).

원본은 `raw/android/<화면>.png`(에뮬레이터 캡처)와 `raw/ios/<화면>.png`(시뮬레이터 캡처)다. 그대로
올려도 규격은 맞지만 네 장이 무슨 화면인지 스토어에서 읽히지 않는다. 그래서 화면마다 같은 틀을
씌운다 — 크림 배경, 위쪽에 문구 두 줄(Jua), 아래에 캡처를 종이 조각처럼 앉힌 것(둥근 모서리 +
잉크 테두리 + 오프셋 그림자). `assets/characters/build.py`의 공유 카드와 같은 구성이다.

- Play 스토어      → `out/play/<화면>.png` (1080×1920) ← `raw/android/`
- App Store 6.7"   → `out/appstore-6.7/<화면>.png` (1290×2796) ← `raw/ios/`
- App Store 6.1"   → `out/appstore-6.1/<화면>.png` (1179×2556) ← `raw/ios/`

문구를 바꾸려면 아래 `CAPTIONS`만 고치고 다시 돌린다 — 생성물은 손으로 고치지 않는다. 캡처가 아직
없는 화면은 SKIP으로 찍고 넘어가므로, 캡처를 한 장씩 채워 가며 돌릴 수 있다.

실행:
    /Users/iseongju/accentury/.venv/bin/python assets/screenshots/build.py
    ... --raw <원본 폴더> --out <출력 폴더>   # 임시 캡처로 배치만 확인할 때

Pillow·numpy가 필요하다(위 venv에 있다). 끝에 규격 검증표를 찍는다 — 하나라도 FAIL이면 종료 코드 1,
SKIP만 있으면 0이다.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent  # assets/screenshots
REPO = ROOT.parent.parent

# 앱 디자인 토큰 (web/src/tokens.css · docs/wiki/design-tokens.md §2).
CREAM = (0xF3, 0xEC, 0xD9)  # --color-background — 프레임 바탕
INK = (0x1C, 0x1A, 0x17)  # --color-primary — 헤드라인·캡처 테두리
MUTED = (0x6B, 0x64, 0x59)  # --color-muted-foreground — 보조 문구
PAPER_SHADOW = (0xCF, 0xC5, 0xAA)  # 오프셋 그림자 (3px 4px 0)

# 화면 id → (헤드라인, 보조 문구). **여기만 고치면 네 규격에 한꺼번에 반영된다.**
CAPTIONS = {
    "01-intro": ("내 사투리, 몇 등급?", "3분이면 끝나는 경남 사투리 레벨 테스트"),
    "02-recording": ("말해보면 곡선이 그려져요", "억양을 실시간 피치 곡선으로"),
    "03-vocab": ("이 말, 무슨 뜻일까?", "경남 토박이만 아는 단어 문제"),
    "04-result": ("5등급 중 당신은?", "결과 카드로 친구에게 공유"),
}
SCREENS = list(CAPTIONS)

# (출력 폴더, 원본 폴더, 폭, 높이). iOS 두 규격은 같은 시뮬레이터 캡처를 쓴다.
OUTPUTS = [
    ("play", "android", 1080, 1920),
    ("appstore-6.7", "ios", 1290, 2796),
    ("appstore-6.1", "ios", 1179, 2556),
]
MAX_BYTES = 8 * 1024 * 1024  # Play 스크린샷 상한 (App Store는 더 넉넉하다)

# --- 배치 (전부 프레임 변 길이 대비 비율 — 규격이 달라도 같은 그림이 나온다) ------------------
TOP_PAD = 0.050  # H — 프레임 위 여백
CAPTION_GAP = 0.016  # H — 헤드라인 ↔ 보조 문구
BLOCK_GAP = 0.042  # H — 문구 블록 ↔ 캡처
BOTTOM_PAD = 0.035  # H — 캡처 아래 여백
SIDE_MARGIN = 0.06  # W — 캡처 좌우 최소 여백
TEXT_MAX_W = 0.88  # W — 문구가 이보다 넓으면 글자를 줄인다
HEADLINE_FRAC = 0.058  # W
SUB_FRAC = 0.032  # W
MIN_CAPTURE_W = 0.62  # W — 세로로 긴 캡처를 높이에 맞추면 너무 작아진다. 이보다 좁아질 바에는
#                            폭을 이 값으로 키우고 아래를 프레임 밖으로 흘린다(잘림).
CORNER_R = 0.05  # 캡처 폭 대비 둥근 모서리 반지름
OUTLINE_FRAC = 0.005  # W — 잉크 테두리 두께 (최소 4px)
SHADOW_DX, SHADOW_DY = 0.0080, 0.0107  # W — 오프셋 그림자 (토큰 3:4 비율)
SS = 4  # 마스크 안티에일리어싱 배수

# --- 캐릭터 스티커 -------------------------------------------------------------------------
# 결과 화면에만 "경남 토박이"를 겹쳐 놓는다. `assets/characters/build.py` 산출물이라 없으면 건너뛴다.
STICKER_SRC = REPO / "web" / "src" / "assets" / "characters" / "native.webp"
STICKER_SCREEN = "04-result"
STICKER_W = 0.28  # W 대비 폭
STICKER_RIGHT, STICKER_BOTTOM = 0.035, 0.028  # W·H 대비 오른쪽·아래 여백

FONT_JUA = REPO / "app" / "src" / "main" / "res" / "font" / "jua_regular.ttf"  # SIL OFL 1.1


# ==========================================================================================
# 1. 그리기 도구
# ==========================================================================================


def resize_rgba(img: Image.Image, w: int, h: int) -> Image.Image:
    """알파를 미리 곱해 두고 줄인다. 그냥 줄이면 투명 픽셀의 RGB가 경계로 번진다."""
    arr = np.asarray(img.convert("RGBA"), dtype=np.float32) / 255.0
    pre = np.dstack([arr[..., :3] * arr[..., 3:4], arr[..., 3:4]])
    small = Image.fromarray((pre * 255.0 + 0.5).astype(np.uint8), "RGBA").resize((w, h), Image.LANCZOS)
    sa = np.asarray(small, dtype=np.float32) / 255.0
    a = sa[..., 3:4]
    rgb = np.where(a > 1e-4, sa[..., :3] / np.where(a > 1e-4, a, 1.0), 0.0)
    return Image.fromarray((np.dstack([np.clip(rgb, 0, 1), a]) * 255.0 + 0.5).astype(np.uint8), "RGBA")


def rounded_mask(w: int, h: int, radius: int, inset: int = 0) -> Image.Image:
    """둥근 사각형 마스크. SS배로 그린 뒤 면적 평균으로 줄여 가장자리를 편다."""
    big = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        [inset * SS, inset * SS, w * SS - 1 - inset * SS, h * SS - 1 - inset * SS],
        radius=max(0, radius - inset) * SS,
        fill=255,
    )
    return big.resize((w, h), Image.BOX)


def fit_font(text: str, size: int, max_w: float) -> ImageFont.FreeTypeFont:
    """max_w를 넘으면 글자를 줄인다 — 잘린 문구가 스토어에 걸리는 게 최악이다."""
    if not FONT_JUA.exists():
        raise SystemExit(f"Jua 폰트가 없다: {FONT_JUA}")
    while True:
        font = ImageFont.truetype(str(FONT_JUA), size)
        if font.getlength(text) <= max_w or size <= 16:
            return font
        size -= 2


def line_height(font: ImageFont.FreeTypeFont) -> int:
    ascent, descent = font.getmetrics()
    return ascent + descent


# ==========================================================================================
# 2. 프레임 한 장
# ==========================================================================================


def place(cap_w: int, cap_h: int, top: int, W: int, H: int) -> tuple[int, int, int, int]:
    """캡처를 앉힐 (x, y, 폭, 높이). 기본은 전체가 다 보이게 맞추고, 그러면 폭이
    MIN_CAPTURE_W보다 좁아질 때만 폭을 키워 아래를 프레임 밖으로 흘린다."""
    dx, dy = round(W * SHADOW_DX), round(W * SHADOW_DY)
    max_w = W - 2 * round(W * SIDE_MARGIN) - dx
    avail_h = H - top - round(H * BOTTOM_PAD) - dy

    scale = min(max_w / cap_w, avail_h / cap_h)
    if scale * cap_w < W * MIN_CAPTURE_W:
        scale = min(max_w / cap_w, W * MIN_CAPTURE_W / cap_w)
    pw, ph = max(1, round(cap_w * scale)), max(1, round(cap_h * scale))
    return (W - pw - dx) // 2, top, pw, ph


def render(cap: Image.Image, screen: str, W: int, H: int, sticker: Image.Image | None) -> Image.Image:
    frame = Image.new("RGBA", (W, H), CREAM + (255,))
    draw = ImageDraw.Draw(frame)

    # 문구 두 줄. 가운데 정렬이고, 헤드라인 아래로 보조 문구가 붙는다.
    head, sub = CAPTIONS[screen]
    head_font = fit_font(head, round(W * HEADLINE_FRAC), W * TEXT_MAX_W)
    sub_font = fit_font(sub, round(W * SUB_FRAC), W * TEXT_MAX_W)
    y = round(H * TOP_PAD)
    draw.text((W // 2, y), head, font=head_font, fill=INK, anchor="ma")
    y += line_height(head_font) + round(H * CAPTION_GAP)
    draw.text((W // 2, y), sub, font=sub_font, fill=MUTED, anchor="ma")
    y += line_height(sub_font) + round(H * BLOCK_GAP)

    # 캡처 = 종이 조각. 그림자 → 캡처 → 잉크 테두리 순으로 얹는다.
    x, y, pw, ph = place(cap.width, cap.height, y, W, H)
    radius = max(1, round(pw * CORNER_R))
    outline = max(4, round(W * OUTLINE_FRAC))
    mask = rounded_mask(pw, ph, radius)
    ring = ImageChops.subtract(mask, rounded_mask(pw, ph, radius, inset=outline))

    dx, dy = round(W * SHADOW_DX), round(W * SHADOW_DY)
    frame.paste(Image.new("RGB", (pw, ph), PAPER_SHADOW), (x + dx, y + dy), mask)
    frame.paste(resize_rgba(cap, pw, ph).convert("RGB"), (x, y), mask)
    frame.paste(Image.new("RGB", (pw, ph), INK), (x, y), ring)

    # 캐릭터 스티커 — 캡처 오른쪽 아래 모서리에 걸치게 둔다.
    if sticker is not None and screen == STICKER_SCREEN:
        sw = round(W * STICKER_W)
        fig = resize_rgba(sticker, sw, max(1, round(sticker.height * sw / sticker.width)))
        frame.alpha_composite(fig, (W - round(W * STICKER_RIGHT) - fig.width, H - round(H * STICKER_BOTTOM) - fig.height))

    return frame.convert("RGB")


def save_png_under(img: Image.Image, path: Path, limit: int) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG", optimize=True)
    if path.stat().st_size > limit:
        # 캡처는 사진이 아니라 UI라 색 수가 적다 — 팔레트로 줄여도 눈에 띄지 않는다
        img.quantize(colors=256, method=Image.Quantize.MEDIANCUT).save(path, "PNG", optimize=True)
    return path.stat().st_size


# ==========================================================================================
# 3. 검증
# ==========================================================================================


def validate(path: Path, W: int, H: int) -> tuple[str, bool | None]:
    """(검증 내용, 판정). 판정 None = SKIP."""
    if not path.exists():
        return "원본 캡처 없음", None
    with Image.open(path) as im:
        dims, mode = im.size, im.mode
        corner = im.getpixel((2, 2))
    nbytes = path.stat().st_size
    detail = f"{dims[0]}x{dims[1]} {mode} 모서리=#{corner[0]:02x}{corner[1]:02x}{corner[2]:02x} {nbytes:,}B"
    return detail, dims == (W, H) and mode == "RGB" and corner == CREAM and nbytes <= MAX_BYTES


def main() -> None:
    ap = argparse.ArgumentParser(description="스토어 스크린샷 프레임 생성")
    ap.add_argument("--raw", type=Path, default=ROOT / "raw", help="기기 캡처 폴더 (기본 assets/screenshots/raw)")
    ap.add_argument("--out", type=Path, default=ROOT / "out", help="출력 폴더 (기본 assets/screenshots/out)")
    args = ap.parse_args()

    sticker = None
    if STICKER_SRC.exists():
        # 알파 여백을 잘라 낸다 — 그래야 "폭 28%"가 여백이 아니라 그림 크기를 뜻한다
        sticker = Image.open(STICKER_SRC).convert("RGBA")
        sticker = sticker.crop(sticker.getbbox())
    print(f"원본 {args.raw}  →  출력 {args.out}")
    print(f"캐릭터 스티커: {'native.webp' if sticker else '없음 (건너뜀)'}\n")

    rows: list[tuple[str, str, bool | None]] = []
    for out_name, raw_name, W, H in OUTPUTS:
        for screen in SCREENS:
            src = args.raw / raw_name / f"{screen}.png"
            dst = args.out / out_name / f"{screen}.png"
            if src.exists():
                with Image.open(src) as cap:
                    save_png_under(render(cap.convert("RGB"), screen, W, H, sticker), dst, MAX_BYTES)
            detail, ok = validate(dst if src.exists() else src, W, H)
            rows.append((f"{out_name}/{screen}", detail, ok))

    width = max(len(label) for label, _, _ in rows)
    for label, detail, ok in rows:
        verdict = "SKIP" if ok is None else ("OK" if ok else "FAIL")
        print(f"{label:<{width}}  {detail:<52s}  {verdict}")
    done = [ok for _, _, ok in rows if ok is not None]
    print(f"\n{sum(1 for ok in done if ok)}/{len(done)} PASS · {len(rows) - len(done)} SKIP")
    if not all(done):
        sys.exit(1)


if __name__ == "__main__":
    main()
