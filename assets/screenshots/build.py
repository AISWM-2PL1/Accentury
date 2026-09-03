#!/usr/bin/env python3
"""기기 캡처 한 장을 스토어 스크린샷 규격의 프레임에 앉힌다 (KAN-178 3단계).

원본은 `raw/android/<화면>.png`(에뮬레이터 캡처)와 `raw/ios/<화면>.png`(시뮬레이터 캡처)다. 그대로
올려도 규격은 맞지만 네 장이 무슨 화면인지 스토어에서 읽히지 않는다. 그래서 화면마다 같은 틀을
씌운다 — 크림 배경, 위쪽에 문구 두 줄(Jua), 아래에 캡처를 실제 기기 실루엣에 끼운 것(베젤 + 옆면
버튼 + 오프셋 그림자). `assets/characters/build.py`의 공유 카드와 같은 Papercut 구성이다.

실루엣은 플랫폼마다 다르다 — iOS 캡처는 iPhone 17 Pro(네 변 같은 얇은 베젤, 크게 둥근 모서리,
왼쪽 3 + 오른쪽 2 버튼), Android 캡처는 Galaxy S25(더 각진 모서리, 펀치홀, 오른쪽 2 버튼).
치수는 전부 `DEVICE_IPHONE_17_PRO` · `DEVICE_GALAXY_S25` 딕셔너리에 비율로 들어 있고,
`PLATFORM_DEVICE`가 원본 폴더를 거기에 잇는다. **기기를 바꾸려면 딕셔너리만 고친다.**

- Play 스토어      → `out/play/<화면>.png` (1080×1920) ← `raw/android/`
- App Store 6.7"   → `out/appstore-6.7/<화면>.png` (1290×2796) ← `raw/ios/`
- App Store 6.1"   → `out/appstore-6.1/<화면>.png` (1179×2556) ← `raw/ios/`

문구를 바꾸려면 아래 `CAPTIONS`만 고치고 다시 돌린다 — 생성물은 손으로 고치지 않는다. 캡처가 아직
없는 화면은 SKIP으로 찍고 넘어가므로, 캡처를 한 장씩 채워 가며 돌릴 수 있다.

실행:
    /Users/iseongju/accentury/.venv/bin/python assets/screenshots/build.py
    ... --raw <원본 폴더> --out <출력 폴더>   # 임시 캡처로 배치만 확인할 때

Pillow·numpy가 필요하다(위 venv에 있다). 끝에 규격 검증표를 찍는다 — 규격 12줄 + 플랫폼별 버튼
여백 2줄. 하나라도 FAIL이면 종료 코드 1, SKIP만 있으면 0이다.
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
MIN_DEVICE_W = 0.62  # W — 세로로 긴 기기를 높이에 맞추면 너무 작아진다. 실루엣 폭이 이보다 좁아질
#                          바에는 폭을 이 값으로 키우고 아래를 프레임 밖으로 흘린다(잘림).
SHADOW_DX, SHADOW_DY = 0.0080, 0.0107  # W — 오프셋 그림자 (토큰 3:4 비율)
SS = 4  # 마스크 안티에일리어싱 배수

# --- 기기 실루엣 ----------------------------------------------------------------------------
# 캡처를 그냥 둥근 네모에 넣으면 "어느 폰인지" 안 읽힌다. 아래 두 딕셔너리가 실루엣을 정한다 —
# 화면 모서리 반지름·베젤 두께·옆면 버튼 위치. **기기를 갈아끼우려면 딕셔너리만 고친다.**
# 값은 전부 비율이라 세 출력 규격에서 같은 그림이 나온다.
BUTTON_THICK = 0.012  # 본체 폭 대비 — 버튼이 본체 밖으로 튀어나온 두께
BUTTON_R = 0.45  # 버튼 두께 대비 모서리 반지름
RAIL_DEPTH = 0.34  # 베젤 두께 대비 — 금속 테 띠가 앉는 깊이 (바깥에 잉크 테두리를 남긴다)
RAIL_W = 0.0026  # 화면 폭 대비 테 띠 두께

# iPhone 17 Pro — 네 변 베젤이 같고, 화면 모서리가 크게 둥글다. 왼쪽 3개(액션·볼륨 업·볼륨 다운)
# 오른쪽 2개(전원·카메라 컨트롤). 버튼 값은 (변, 본체 높이 대비 시작, 본체 높이 대비 길이).
DEVICE_IPHONE_17_PRO = {
    "label": "iPhone 17 Pro",
    "screen_radius": 0.135,  # 화면 폭 대비
    "bezel": {"left": 0.026, "right": 0.026, "top": 0.026, "bottom": 0.026},  # 화면 폭 대비
    "punch_hole": None,  # 다이내믹 아일랜드는 캡처 안에 이미 있다 — 그리지 않는다
    "buttons": [
        ("left", 0.160, 0.035),  # 액션 버튼
        ("left", 0.220, 0.075),  # 볼륨 업
        ("left", 0.310, 0.075),  # 볼륨 다운
        ("right", 0.240, 0.110),  # 전원(사이드) 버튼
        ("right", 0.580, 0.050),  # 카메라 컨트롤
    ],
}

# Galaxy S25 — 평평한 알루미늄 테라 모서리가 훨씬 각지고, 베젤은 더 얇되 아래턱이 조금 두껍다.
# 앞면 카메라는 화면을 뚫은 펀치홀이라 직접 그린다. 버튼은 오른쪽 두 개뿐이다.
DEVICE_GALAXY_S25 = {
    "label": "Galaxy S25",
    "screen_radius": 0.070,
    "bezel": {"left": 0.016, "right": 0.016, "top": 0.016, "bottom": 0.020},
    "punch_hole": (0.026, 0.023),  # (화면 폭 대비 지름, 화면 높이 대비 중심 y)
    "buttons": [
        ("right", 0.190, 0.090),  # 볼륨 로커 (한 덩어리)
        ("right", 0.310, 0.045),  # 전원(사이드) 키
    ],
}

# 원본 폴더 이름 → 기기. iOS 두 규격은 같은 시뮬레이터 캡처를 쓰니 같은 실루엣을 받는다.
PLATFORM_DEVICE = {"android": DEVICE_GALAXY_S25, "ios": DEVICE_IPHONE_17_PRO}

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
# 2. 기기 실루엣
# ==========================================================================================


def geometry(device: dict, sw: int, sh: int) -> dict:
    """화면 크기(sw × sh)에서 실루엣 치수를 전부 뽑는다. 길이는 모두 화면 폭 기준이라
    출력 규격이 달라도 비율이 같다. 캔버스는 본체 + 좌우 버튼 돌출분이다."""
    b = device["bezel"]
    bl, br = max(1, round(sw * b["left"])), max(1, round(sw * b["right"]))
    bt, bb = max(1, round(sw * b["top"])), max(1, round(sw * b["bottom"]))
    body_w, body_h = sw + bl + br, sh + bt + bb
    out = max(2, round(body_w * BUTTON_THICK))
    screen_r = max(1, round(sw * device["screen_radius"]))
    return {
        "sw": sw,
        "sh": sh,
        "body_w": body_w,
        "body_h": body_h,
        "body_r": screen_r + bl,  # 동심 — 바깥 반지름 = 화면 반지름 + 베젤
        "screen_r": screen_r,
        "out": out,
        "canvas_w": body_w + 2 * out,
        "canvas_h": body_h,
        "screen_xy": (out + bl, bt),
        "bezel": bl,
        "rail": max(1, round(sw * RAIL_W)),
    }


def silhouette(device: dict, g: dict) -> Image.Image:
    """본체 + 버튼을 한 덩어리로 그린 마스크. 그림자는 이걸 그대로 옮겨 찍는다 —
    따로 그리면 버튼 그림자가 어긋난다."""
    W, H = g["canvas_w"], g["canvas_h"]
    big = Image.new("L", (W * SS, H * SS), 0)
    d = ImageDraw.Draw(big)
    x0, x1 = g["out"] * SS, (g["out"] + g["body_w"]) * SS - 1
    d.rounded_rectangle([x0, 0, x1, H * SS - 1], radius=g["body_r"] * SS, fill=255)
    br = max(1, round(g["out"] * BUTTON_R)) * SS
    for side, top_f, h_f in device["buttons"]:
        y0 = round(top_f * g["body_h"]) * SS
        y1 = y0 + max(1, round(h_f * g["body_h"])) * SS
        span = [0, y0, x0 + g["out"] * SS, y1] if side == "left" else [x1 - g["out"] * SS, y0, W * SS - 1, y1]
        d.rounded_rectangle(span, radius=br, fill=255)  # 본체 안으로 겹쳐 그려 이음매를 없앤다
    return big.resize((W, H), Image.BOX)


def button_extent(device: dict, g: dict) -> tuple[int, int] | None:
    """버튼이 실제로 차지하는 x 범위(캔버스 기준). 버튼이 없는 변은 빼고 잰다 —
    검증표에서 "가장 바깥 버튼 픽셀이 프레임 안에 있나"를 이 값으로 본다."""
    xs: list[int] = []
    for side, _, _ in device["buttons"]:
        xs.append(0 if side == "left" else g["canvas_w"] - 1)
    return (min(xs), max(xs)) if xs else None


def draw_device(cap: Image.Image, device: dict, g: dict) -> tuple[Image.Image, Image.Image]:
    """(기기 그림 RGBA, 실루엣 마스크). 본체는 잉크 한 색이고, 베젤 안쪽에 종이 그림자색 띠를
    한 줄 넣어 금속 테를 암시한다. 화면에는 캡처를 그대로 앉힌다."""
    W, H = g["canvas_w"], g["canvas_h"]
    mask = silhouette(device, g)
    dev = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    dev.paste(Image.new("RGB", (W, H), INK), (0, 0), mask)

    # 금속 테 띠 — 베젤 안쪽 34% 깊이에 한 줄. 크림으로 넣으면 베젤이 두 겹으로 갈라져 보여서
    # 종이 그림자색을 쓴다. 바깥에는 잉크가 남아 Papercut 테두리가 끊기지 않는다.
    inset = max(1, round(g["bezel"] * RAIL_DEPTH))
    ring = ImageChops.subtract(
        rounded_mask(g["body_w"], g["body_h"], g["body_r"], inset),
        rounded_mask(g["body_w"], g["body_h"], g["body_r"], inset + g["rail"]),
    )
    dev.paste(Image.new("RGB", (g["body_w"], g["body_h"]), PAPER_SHADOW), (g["out"], 0), ring)

    # 화면 = 캡처. 상태바·홈 인디케이터·다이내믹 아일랜드는 캡처 안에 이미 있다.
    sw, sh = g["sw"], g["sh"]
    dev.paste(resize_rgba(cap, sw, sh).convert("RGB"), g["screen_xy"], rounded_mask(sw, sh, g["screen_r"]))

    # 펀치홀(Galaxy) — 화면을 뚫은 앞면 카메라라 캡처 위에 찍는다.
    if device["punch_hole"]:
        dia_f, cy_f = device["punch_hole"]
        dia = max(2, round(sw * dia_f))
        cx = g["screen_xy"][0] + sw // 2
        cy = g["screen_xy"][1] + round(sh * cy_f)
        hole = rounded_mask(dia, dia, dia // 2)
        dev.paste(Image.new("RGB", (dia, dia), INK), (cx - dia // 2, cy - dia // 2), hole)

    return dev, mask


# ==========================================================================================
# 3. 프레임 한 장
# ==========================================================================================


def place(cap_w: int, cap_h: int, device: dict, top: int, W: int, H: int) -> tuple[int, int]:
    """기기를 앉힐 화면 크기 (폭, 높이). 기본은 실루엣 전체가 다 보이게 맞추고, 그러면 폭이
    MIN_DEVICE_W보다 좁아질 때만 폭을 키워 아래를 프레임 밖으로 흘린다.

    베젤·버튼도 화면 폭에 비례하니, 실루엣 폭 = 화면 폭 × kw, 실루엣 높이 = 화면 폭 × kh 로
    한 번에 풀린다. 버튼이 잘리지 않게 여백은 실루엣(=버튼 포함) 기준으로 잡는다."""
    b = device["bezel"]
    kw = (1 + b["left"] + b["right"]) * (1 + 2 * BUTTON_THICK)  # 화면 폭 → 실루엣 폭
    kh = cap_h / cap_w + b["top"] + b["bottom"]  # 화면 폭 → 실루엣 높이
    dx, dy = round(W * SHADOW_DX), round(W * SHADOW_DY)
    max_w = W - 2 * round(W * SIDE_MARGIN) - dx
    avail_h = H - top - round(H * BOTTOM_PAD) - dy

    sw = min(max_w / kw, avail_h / kh)
    if sw * kw < W * MIN_DEVICE_W:
        sw = min(max_w / kw, W * MIN_DEVICE_W / kw)
    return max(1, round(sw)), max(1, round(sw * cap_h / cap_w))


def render(
    cap: Image.Image, screen: str, device: dict, W: int, H: int, sticker: Image.Image | None
) -> tuple[Image.Image, float | None]:
    """(프레임, 가장 바깥 버튼 픽셀의 좌우 여백 중 작은 쪽 / W). 여백은 검증표가 본다."""
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

    # 기기 = 종이 조각. 그림자(실루엣을 통째로 옮긴 것) → 기기 순으로 얹는다.
    sw, sh = place(cap.width, cap.height, device, y, W, H)
    g = geometry(device, sw, sh)
    dev, mask = draw_device(cap, device, g)
    dx, dy = round(W * SHADOW_DX), round(W * SHADOW_DY)
    x = (W - g["canvas_w"] - dx) // 2

    frame.paste(Image.new("RGB", (g["canvas_w"], g["canvas_h"]), PAPER_SHADOW), (x + dx, y + dy), mask)
    frame.alpha_composite(dev, (x, y))

    span = button_extent(device, g)
    margin = min(x + span[0], W - 1 - (x + span[1])) / W if span else None

    # 캐릭터 스티커 — 캡처 오른쪽 아래 모서리에 걸치게 둔다.
    if sticker is not None and screen == STICKER_SCREEN:
        sw = round(W * STICKER_W)
        fig = resize_rgba(sticker, sw, max(1, round(sticker.height * sw / sticker.width)))
        frame.alpha_composite(fig, (W - round(W * STICKER_RIGHT) - fig.width, H - round(H * STICKER_BOTTOM) - fig.height))

    return frame.convert("RGB"), margin


def save_png_under(img: Image.Image, path: Path, limit: int) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG", optimize=True)
    if path.stat().st_size > limit:
        # 캡처는 사진이 아니라 UI라 색 수가 적다 — 팔레트로 줄여도 눈에 띄지 않는다
        img.quantize(colors=256, method=Image.Quantize.MEDIANCUT).save(path, "PNG", optimize=True)
    return path.stat().st_size


# ==========================================================================================
# 4. 검증
# ==========================================================================================

BUTTON_MARGIN_MIN = 0.03  # W — 가장 바깥 버튼 픽셀이 프레임 변에서 이만큼은 떨어져 있어야 한다


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
    margins: dict[str, list[float]] = {}
    for out_name, raw_name, W, H in OUTPUTS:
        device = PLATFORM_DEVICE[raw_name]
        for screen in SCREENS:
            src = args.raw / raw_name / f"{screen}.png"
            dst = args.out / out_name / f"{screen}.png"
            if src.exists():
                with Image.open(src) as cap:
                    frame, margin = render(cap.convert("RGB"), screen, device, W, H, sticker)
                save_png_under(frame, dst, MAX_BYTES)
                if margin is not None:
                    margins.setdefault(raw_name, []).append(margin)
            detail, ok = validate(dst if src.exists() else src, W, H)
            rows.append((f"{out_name}/{screen}", detail, ok))

    # 플랫폼마다 한 줄 — 버튼이 프레임 밖으로 나가거나 변에 붙지 않았나. 실루엣이 넓어지면
    # 여기부터 FAIL이 뜨니 MIN_DEVICE_W·SIDE_MARGIN을 줄이라는 신호다.
    for raw_name, device in PLATFORM_DEVICE.items():
        got = margins.get(raw_name)
        label = f"버튼 여백/{raw_name}"
        if not got:
            rows.append((label, f"{device['label']} 원본 캡처 없음", None))
            continue
        worst = min(got)
        detail = f"{device['label']} 가장 바깥 버튼 여백 {worst * 100:.1f}% (≥{BUTTON_MARGIN_MIN * 100:.0f}%)"
        rows.append((label, detail, worst >= BUTTON_MARGIN_MIN))

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
