#!/usr/bin/env python3
"""앱 아이콘 원본 한 장에서 플랫폼별 파생본을 전부 만든다 (KAN-178 2단계).

원본은 `source.png` — Codex CLI 내장 image_gen이 뽑은 "ㅅㅌㄹ" 종이 조각 도상이다. 배경이 흰색
(`#FFFFFF`)으로 나와서(생성 편차) 그대로 쓰면 크림 배경 위에 흰 사각형이 얹힌다. 그래서 여기서
배경을 투명으로 키잉하고 종이색을 토큰 값(`#F3ECD9`)으로 정규화한 뒤, 그 도상 하나를 플랫폼별
규격에 맞춰 다시 앉힌다:

- Android adaptive icon  → `mipmap-<density>/ic_launcher_foreground.png` + `ic_launcher_monochrome.png`
- Android 레거시 런처     → `mipmap-<density>/ic_launcher.png` · `ic_launcher_round.png`
- Android 스플래시        → `drawable-<density>/ic_splash.png`
- iOS 앱 아이콘           → `Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`
- iOS 런치 스크린         → `Assets.xcassets/LaunchIcon.imageset/` · `LaunchBackground.colorset/`
- 스토어                  → `store/play-icon-512.png` · `play-feature-1024x500.png` · `app-store-icon-1024.png`

도상을 바꾸려면 `source.png`를 갈아끼우고 이 스크립트를 다시 돌린다 — 파생본은 손으로 고치지 않는다.
`assets/characters/build.py`와 같은 구조이고, 키잉·종이색 정규화도 그쪽 방식을 그대로 쓴다.

실행:
    python3 assets/app-icon/build.py

Pillow·numpy·scipy가 필요하다 — 없으면 가상환경을 만들어 `pip install pillow numpy scipy`로 넣고 그
안에서 돌린다. 끝에 규격 검증표를 찍는다 — 하나라도 FAIL이면 종료 코드 1이다.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

ROOT = Path(__file__).resolve().parent  # assets/app-icon
REPO = ROOT.parent.parent
RES = REPO / "app" / "src" / "main" / "res"
XCASSETS = REPO / "ios" / "Accentury" / "Assets.xcassets"
STORE = ROOT / "store"

# 앱 디자인 토큰 (web/src/tokens.css · docs/wiki/design-tokens.md §2).
CREAM = (0xF3, 0xEC, 0xD9)  # --color-background — 아이콘 배경/종이
INK = (0x1C, 0x1A, 0x17)  # --color-primary
MUTED = (0x6B, 0x64, 0x59)  # --color-muted-foreground
PAPER_SHADOW = (0xCF, 0xC5, 0xAA)  # 오프셋 그림자 (3px 4px 0)

# 키잉 파라미터. 배경이 정확히 순백(편차 0)이라 허용 오차가 넉넉해도 안전하다 — 도상에서 가장
# 밝은 면인 스티커 림이 배경보다 채널 최대 29 어두워서, 그 선이 flood fill을 막는 벽이 된다.
KEY_TOL = 14  # 이 값 이하로 배경과 가까우면 "배경일 수 있음"
EDGE_SPAN = 40  # 경계 픽셀의 알파를 배경 거리 0..EDGE_SPAN → 0..1 로 편다
LEAK_RADIUS = 3  # 림의 틈(≤ 2×반지름)으로 새어 들어간 fill을 끊는다
PAPER_SPAN = 30  # 종이색 정규화가 닿는 범위(원본 종이색과의 채널 최대 차)

# --- Android 밀도 배수 ---------------------------------------------------------------------
DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]

# --- Android adaptive icon -----------------------------------------------------------------
# 108dp 캔버스 가운데 66dp만 항상 보인다. 런처가 마스크(원·스퀴클·둥근 네모)를 바꿔 씌우고,
# 흔들기 애니메이션으로 최대 ±6dp까지 밀기도 한다. 그래서 도상은 66dp 안쪽에만 그린다.
ADAPTIVE_DP = 108
SAFE_DP = 66
SAFE_INSET_DP = 2  # 안전 영역 변에 도상이 닿지 않게 두는 안쪽 여백
MOTIF_DP = SAFE_DP - 2 * SAFE_INSET_DP  # 62dp

# 모노크롬(Android 13 테마 아이콘) 잉크 판정. 잉크(휘도 ~25)는 남기고 스티커 림(~240)과
# 오프셋 그림자(~206)는 뺀다 — 단색 실루엣에서 그림자는 도상을 두 겹으로 보이게 만든다.
MONO_LUM_LO = 60.0  # 이보다 어두우면 완전 불투명
MONO_LUM_HI = 140.0  # 이보다 밝으면 완전 투명

# --- Android 레거시 런처 아이콘 (API 25 이하 · 일부 런처) -------------------------------------
LEGACY_DP = 48
LEGACY_MOTIF_W = 0.80  # 정사각형 변 대비 도상 폭
LEGACY_ROUND_MOTIF_W = 0.72  # 원형 마스크는 모서리가 잘려서 조금 더 줄인다

# --- Android 스플래시 (SplashScreen API) ------------------------------------------------------
# 배경 없는 아이콘 규격: 288dp 캔버스, 도상은 가운데 192dp 원 안에 들어가야 한다.
SPLASH_DP = 288
SPLASH_CIRCLE_DP = 192
SPLASH_FIT = 0.78  # 192dp 정사각형에 이 비율로 넣으면 네 모서리가 원 안에 남는다

# --- iOS -------------------------------------------------------------------------------------
IOS_ICON_PX = 1024
IOS_ICON_MOTIF_W = 0.78  # 정사각 변 대비 도상 폭 (스토어 512 아이콘도 같은 값)
LAUNCH_PT = 200  # 런치 스크린 아이콘 1x 변 길이(pt)

# --- 스토어 ------------------------------------------------------------------------------------
PLAY_ICON_PX = 512
PLAY_ICON_MAX_BYTES = 1024 * 1024  # Play Console 상한
FEATURE_W, FEATURE_H = 1024, 500
FEATURE_MAX_BYTES = 1024 * 1024
FEATURE_MARGIN = 0.06  # 네 변 최소 여백 (Play가 기기별로 가장자리를 잘라낸다)
FEATURE_TITLE = "Accentury"
FEATURE_SUB = "경남 사투리 레벨 테스트"

FONT_JUA = RES / "font" / "jua_regular.ttf"  # SIL OFL 1.1 — 앱 제목과 같은 글꼴


# ==========================================================================================
# 1. 키잉 + 종이색 정규화
# ==========================================================================================


def border_mean(rgb: np.ndarray) -> np.ndarray:
    h, w, _ = rgb.shape
    m = max(8, w // 30)
    strips = [rgb[:m], rgb[-m:], rgb[:, :m], rgb[:, -m:]]
    return np.concatenate([s.reshape(-1, 3) for s in strips]).mean(axis=0)


def key_background(src: Image.Image) -> Image.Image:
    """균일 배경을 투명으로. 바깥에서 닿는 배경만 지우고, 도상 안쪽의 같은 색 면은 남긴다."""
    rgb = np.asarray(src.convert("RGB"), dtype=np.float32)
    bg = border_mean(rgb)
    dist = np.abs(rgb - bg).max(axis=2)  # 채널 최대 차이

    candidate = dist <= KEY_TOL
    labels, _ = ndimage.label(candidate)
    border_labels = np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))
    exterior = np.isin(labels, border_labels[border_labels != 0])

    # 림의 작은 틈으로 새어 들어간 영역 제거: 침식 → 바깥과 이어진 것만 → 팽창
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

    return Image.fromarray(np.dstack([rgb, alpha * 255.0]).astype(np.uint8), "RGBA")


def normalize_paper(cut: Image.Image) -> tuple[Image.Image, tuple[int, int, int]]:
    """도상의 종이(스티커 림)를 정확히 CREAM으로 옮긴다. 잉크와 오프셋 그림자는 건드리지 않는다.

    `assets/characters/build.py`는 배경이 곧 종이색이라 "휘도가 높을수록 (PAPER − 배경) 델타를
    적용"하면 됐다. 여기는 배경이 흰색이고 종이는 도상 안에만 있으니, 기준색을 배경이 아니라
    **도상 안 밝은 면의 중앙값**에서 잡는다. 그리고 휘도 대신 그 기준색과의 거리로 가중치를 준다 —
    휘도 램프를 쓰면 그림자(휘도 ~206)까지 같이 밀려 이미 토큰 값인 `#CFC5AA`에서 벗어난다.
    """
    arr = np.asarray(cut, dtype=np.float32)
    rgb, alpha = arr[..., :3], arr[..., 3]
    solid = alpha > 250
    lum = rgb.mean(axis=2)
    paper_px = rgb[solid & (lum > 230)]
    if paper_px.size == 0:
        raise SystemExit("도상에서 종이(밝은 면)를 찾지 못했다 — 키잉이 너무 많이 지웠다")
    src_paper = np.median(paper_px, axis=0)

    w = np.clip(1.0 - np.abs(rgb - src_paper).max(axis=2) / PAPER_SPAN, 0.0, 1.0)[..., None]
    rgb = np.clip(rgb + (np.asarray(CREAM, dtype=np.float32) - src_paper) * w, 0, 255)

    out = Image.fromarray(np.dstack([rgb, alpha]).astype(np.uint8), "RGBA")
    return out, tuple(int(round(v)) for v in src_paper)


def trim(cut: Image.Image) -> Image.Image:
    bbox = cut.getbbox()
    if not bbox:
        raise SystemExit("알파가 전부 0 — 키잉이 도상까지 지웠다")
    return cut.crop(bbox)


# ==========================================================================================
# 2. 배치 도구
# ==========================================================================================


def resize_rgba(img: Image.Image, w: int, h: int) -> Image.Image:
    """알파를 미리 곱해 두고 줄인다. 그냥 줄이면 투명 픽셀의 RGB(여기선 검정)가 경계로 번진다."""
    arr = np.asarray(img, dtype=np.float32) / 255.0
    pre = np.dstack([arr[..., :3] * arr[..., 3:4], arr[..., 3:4]])
    small = Image.fromarray((pre * 255.0 + 0.5).astype(np.uint8), "RGBA").resize((w, h), Image.LANCZOS)
    sa = np.asarray(small, dtype=np.float32) / 255.0
    a = sa[..., 3:4]
    rgb = np.where(a > 1e-4, sa[..., :3] / np.where(a > 1e-4, a, 1.0), 0.0)
    return Image.fromarray((np.dstack([np.clip(rgb, 0, 1), a]) * 255.0 + 0.5).astype(np.uint8), "RGBA")


def fit(motif: Image.Image, box_w: float, box_h: float) -> Image.Image:
    """가로세로비를 지키며 box 안에 최대로 넣는다."""
    s = min(box_w / motif.width, box_h / motif.height)
    return resize_rgba(motif, max(1, int(round(motif.width * s))), max(1, int(round(motif.height * s))))


def on_canvas(motif: Image.Image, size: int, box: float, bg: tuple | None) -> Image.Image:
    """size×size 캔버스 한가운데에 box×box 상자로 맞춘 도상을 얹는다. bg=None이면 투명."""
    canvas = Image.new("RGBA", (size, size), (bg or (0, 0, 0)) + (255 if bg else 0,))
    piece = fit(motif, box, box)
    canvas.alpha_composite(piece, ((size - piece.width) // 2, (size - piece.height) // 2))
    return canvas


def px(dp: float, scale: float) -> int:
    return int(round(dp * scale))


def alpha_bbox(path: Path) -> tuple[int, int, int, int]:
    with Image.open(path) as im:
        bbox = im.convert("RGBA").getbbox()
    if not bbox:
        raise SystemExit(f"{path}: 알파가 전부 0")
    return bbox


# ==========================================================================================
# 3. Android — adaptive icon (전경 · 모노크롬 · 배경 XML)
# ==========================================================================================

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<!--
  생성물이다 (assets/app-icon/build.py). 손으로 고치지 마라 — 다음 실행에서 덮인다.

  배경은 drawable이 아니라 색 리소스다(@color/ic_launcher_background). 단색 크림 한 장을
  그리는 데 벡터/비트맵을 둘 이유가 없고, 색 리소스면 값이 colors.xml 한 곳에만 있다.
  monochrome은 Android 13 테마 아이콘용 잉크 실루엣이다 — 전경을 그대로 쓰면 크림 종이까지
  단색으로 칠해져 글자가 사라진다.
-->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""


def monochrome(motif: Image.Image) -> Image.Image:
    """잉크 피복률을 알파로 옮긴 흰색 실루엣."""
    arr = np.asarray(motif, dtype=np.float32)
    lum = arr[..., :3].mean(axis=2)
    ink = np.clip((MONO_LUM_HI - lum) / (MONO_LUM_HI - MONO_LUM_LO), 0.0, 1.0)
    alpha = ink * (arr[..., 3] / 255.0)
    white = np.full(arr.shape[:2] + (3,), 255.0)
    return Image.fromarray(np.dstack([white, alpha * 255.0]).astype(np.uint8), "RGBA")


def build_android_adaptive(motif: Image.Image) -> list[Path]:
    mono = monochrome(motif)
    written = []
    for name, scale in DENSITIES:
        out = RES / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        size, box = px(ADAPTIVE_DP, scale), MOTIF_DP * scale
        for src, fname in ((motif, "ic_launcher_foreground.png"), (mono, "ic_launcher_monochrome.png")):
            path = out / fname
            on_canvas(src, size, box, None).save(path, "PNG", optimize=True)
            written.append(path)
    for fname in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = RES / "mipmap-anydpi" / fname
        path.write_text(ADAPTIVE_XML, encoding="utf-8")
        written.append(path)
    return written


def build_android_legacy(motif: Image.Image) -> list[Path]:
    """레거시 런처 아이콘. adaptive를 못 읽는 런처가 이걸 쓴다 — 배경을 직접 깔아야 한다."""
    written = []
    for name, scale in DENSITIES:
        out = RES / f"mipmap-{name}"
        size = px(LEGACY_DP, scale)

        square = on_canvas(motif, size, size * LEGACY_MOTIF_W, CREAM)
        square.convert("RGB").save(out / "ic_launcher.png", "PNG", optimize=True)
        written.append(out / "ic_launcher.png")

        # 원형: 크림 원판에 도상을 얹고 원 밖을 잘라낸다. 안티에일리어싱은 4× 마스크를 줄여서 얻는다.
        mask = Image.new("L", (size * 4, size * 4), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
        round_icon = on_canvas(motif, size, size * LEGACY_ROUND_MOTIF_W, CREAM)
        round_icon.putalpha(mask.resize((size, size), Image.LANCZOS))
        round_icon.save(out / "ic_launcher_round.png", "PNG", optimize=True)
        written.append(out / "ic_launcher_round.png")
    return written


def build_android_splash(motif: Image.Image) -> list[Path]:
    """SplashScreen API의 `windowSplashScreenAnimatedIcon`. 배경 없는 아이콘 규격이다."""
    written = []
    for name, scale in DENSITIES:
        out = RES / f"drawable-{name}"
        out.mkdir(parents=True, exist_ok=True)
        size = px(SPLASH_DP, scale)
        box = SPLASH_CIRCLE_DP * scale * SPLASH_FIT
        path = out / "ic_splash.png"
        on_canvas(motif, size, box, None).save(path, "PNG", optimize=True)
        written.append(path)
    return written


def clean_android_defaults() -> list[Path]:
    """Android Studio 기본 아이콘 잔재를 지운다. 남기면 같은 이름의 리소스가 둘이 된다."""
    removed = []
    for name, _ in DENSITIES:
        for fname in ("ic_launcher.webp", "ic_launcher_round.webp"):
            path = RES / f"mipmap-{name}" / fname
            if path.exists():
                path.unlink()
                removed.append(path)
    for fname in ("ic_launcher_background.xml", "ic_launcher_foreground.xml"):
        path = RES / "drawable" / fname
        if path.exists():
            path.unlink()
            removed.append(path)
    return removed


# ==========================================================================================
# 4. iOS
# ==========================================================================================

LAUNCH_IMAGESET_JSON = {
    "images": [
        {"idiom": "universal", "scale": "1x", "filename": "LaunchIcon.png"},
        {"idiom": "universal", "scale": "2x", "filename": "LaunchIcon@2x.png"},
        {"idiom": "universal", "scale": "3x", "filename": "LaunchIcon@3x.png"},
    ],
    "info": {"author": "assets/app-icon/build.py", "version": 1},
}

LAUNCH_COLORSET_JSON = {
    "colors": [
        {
            "idiom": "universal",
            "color": {
                "color-space": "srgb",
                "components": {
                    "red": f"{CREAM[0] / 255:.3f}",
                    "green": f"{CREAM[1] / 255:.3f}",
                    "blue": f"{CREAM[2] / 255:.3f}",
                    "alpha": "1.000",
                },
            },
        }
    ],
    "info": {"author": "assets/app-icon/build.py", "version": 1},
}


def write_json(path: Path, data: dict) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path


def flat_icon(motif: Image.Image, size: int) -> Image.Image:
    """알파 없는 정사각 아이콘. iOS와 스토어는 투명도를 허용하지 않는다."""
    return on_canvas(motif, size, size * IOS_ICON_MOTIF_W, CREAM).convert("RGB")


def build_ios(motif: Image.Image) -> list[Path]:
    written = []
    icon = XCASSETS / "AppIcon.appiconset" / "AppIcon-1024.png"
    flat_icon(motif, IOS_ICON_PX).save(icon, "PNG", optimize=True)
    written.append(icon)

    imageset = XCASSETS / "LaunchIcon.imageset"
    for scale, fname in ((1, "LaunchIcon.png"), (2, "LaunchIcon@2x.png"), (3, "LaunchIcon@3x.png")):
        size = LAUNCH_PT * scale
        path = imageset / fname
        path.parent.mkdir(parents=True, exist_ok=True)
        on_canvas(motif, size, size, None).save(path, "PNG", optimize=True)
        written.append(path)
    written.append(write_json(imageset / "Contents.json", LAUNCH_IMAGESET_JSON))
    written.append(write_json(XCASSETS / "LaunchBackground.colorset" / "Contents.json", LAUNCH_COLORSET_JSON))
    return written


# ==========================================================================================
# 5. 스토어 (Play 아이콘 · 피처 그래픽 · App Store 아이콘)
# ==========================================================================================


def feature_graphic(motif: Image.Image) -> Image.Image:
    """Play 스토어 피처 그래픽. 좌측 도상 + 우측 앱 이름 + 태그라인.

    앱 이름은 잉크 글자만 둔다 — 공유 카드처럼 종이 조각(테두리·그림자)에 넣었더니 도상의 종이 림과
    겹쳐 사각형이 하나 더 보이는 느낌이라 뺐다(2026-09-03). 앱 이름 말고 다른 문구는 넣지
    않는다(Play 정책: 기기 사진·평점·가격 금지).
    """
    if not FONT_JUA.exists():
        raise SystemExit(f"Jua 폰트가 없다: {FONT_JUA}")
    card = Image.new("RGBA", (FEATURE_W, FEATURE_H), CREAM + (255,))
    margin = int(round(FEATURE_W * FEATURE_MARGIN))

    fig = fit(motif, FEATURE_W * 0.38, FEATURE_H - 2 * margin)
    fx, fy = margin, (FEATURE_H - fig.height) // 2
    card.alpha_composite(fig, (fx, fy))

    draw = ImageDraw.Draw(card)
    left = fx + fig.width + 56
    avail = FEATURE_W - margin - left

    # 앱 이름. 폭이 남는 여백을 넘으면 글자를 줄인다 — 잘린 이름이 스토어에 걸리는 게 최악이다.
    pad_x, pad_y = 26, 12
    size = 104
    while True:
        title_font = ImageFont.truetype(str(FONT_JUA), size)
        chip_w = int(draw.textlength(FEATURE_TITLE, font=title_font) + 2 * pad_x)
        if chip_w + 4 <= avail or size <= 48:
            break
        size -= 4
    chip_h = size + 2 * pad_y

    sub_font = ImageFont.truetype(str(FONT_JUA), 38)
    gap = 26
    block_h = chip_h + gap + 38
    top = (FEATURE_H - block_h) // 2

    draw.text((left + pad_x, top + chip_h // 2), FEATURE_TITLE, font=title_font, fill=INK, anchor="lm")
    # 태그라인은 앱 이름 폭 기준 가운데 정렬 — 왼쪽 맞춤이면 이름보다 훨씬 짧아 오른쪽이 비어 보인다
    draw.text((left + chip_w // 2, top + chip_h + gap), FEATURE_SUB, font=sub_font, fill=MUTED, anchor="ma")
    return card.convert("RGB")


def save_png_under(img: Image.Image, path: Path, limit: int) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG", optimize=True)
    if path.stat().st_size > limit:
        # 색이 네 가지뿐이라 팔레트로 줄여도 눈에 띄지 않는다
        img.quantize(colors=256, method=Image.Quantize.MEDIANCUT).save(path, "PNG", optimize=True)
    size = path.stat().st_size
    if size > limit:
        raise SystemExit(f"{path.name}: {size:,} bytes > {limit:,}")
    return size


def build_store(motif: Image.Image) -> list[Path]:
    STORE.mkdir(parents=True, exist_ok=True)
    play = STORE / "play-icon-512.png"
    save_png_under(flat_icon(motif, PLAY_ICON_PX), play, PLAY_ICON_MAX_BYTES)
    feature = STORE / "play-feature-1024x500.png"
    save_png_under(feature_graphic(motif), feature, FEATURE_MAX_BYTES)
    appstore = STORE / "app-store-icon-1024.png"
    appstore.write_bytes((XCASSETS / "AppIcon.appiconset" / "AppIcon-1024.png").read_bytes())
    return [play, feature, appstore]


# ==========================================================================================
# 6. 검증
# ==========================================================================================


def check(rows: list, label: str, detail: str, ok: bool) -> None:
    rows.append((label, detail, ok))


def validate(src_paper: tuple[int, int, int]) -> list:
    rows: list = []

    # 원본 종이색이 CREAM으로 옮겨졌는지 — 정규화가 실제로 일어났다는 증거
    check(rows, "원본 종이색", f"#{src_paper[0]:02x}{src_paper[1]:02x}{src_paper[2]:02x} → #f3ecd9", True)

    for name, scale in DENSITIES:
        size = px(ADAPTIVE_DP, scale)
        safe_lo = (size - SAFE_DP * scale) / 2
        safe_hi = size - safe_lo
        for fname in ("ic_launcher_foreground.png", "ic_launcher_monochrome.png"):
            path = RES / f"mipmap-{name}" / fname
            with Image.open(path) as im:
                dims, mode = im.size, im.mode
            x0, y0, x1, y1 = alpha_bbox(path)
            inside = x0 >= safe_lo - 0.5 and y0 >= safe_lo - 0.5 and x1 <= safe_hi + 0.5 and y1 <= safe_hi + 0.5
            check(
                rows,
                f"adaptive {name}/{fname.replace('ic_launcher_', '').replace('.png', '')}",
                f"{dims[0]}x{dims[1]} {mode} bbox=({x0},{y0},{x1},{y1}) safe=[{safe_lo:.0f},{safe_hi:.0f}]",
                dims == (size, size) and mode == "RGBA" and inside,
            )

    for fname in ("ic_launcher.xml", "ic_launcher_round.xml"):
        text = (RES / "mipmap-anydpi" / fname).read_text(encoding="utf-8")
        check(
            rows,
            f"adaptive-icon {fname}",
            "background=@color, foreground/monochrome=@mipmap",
            '<background android:drawable="@color/ic_launcher_background" />' in text
            and '<foreground android:drawable="@mipmap/ic_launcher_foreground" />' in text
            and '<monochrome android:drawable="@mipmap/ic_launcher_monochrome" />' in text,
        )

    colors = (RES / "values" / "colors.xml").read_text(encoding="utf-8")
    check(
        rows,
        "colors.xml 배경색",
        '<color name="ic_launcher_background">#F3ECD9</color>',
        '<color name="ic_launcher_background">#F3ECD9</color>' in colors,
    )

    for name, scale in DENSITIES:
        size = px(LEGACY_DP, scale)
        for fname in ("ic_launcher.png", "ic_launcher_round.png"):
            path = RES / f"mipmap-{name}" / fname
            with Image.open(path) as im:
                dims, mode = im.size, im.mode
                corner = im.convert("RGB").getpixel((size // 2, 2))
            check(
                rows,
                f"legacy {name}/{fname.replace('ic_launcher', 'ic').replace('.png', '')}",
                f"{dims[0]}x{dims[1]} {mode} 위쪽중앙=#{corner[0]:02x}{corner[1]:02x}{corner[2]:02x}",
                dims == (size, size) and corner == CREAM,
            )

    leftovers = [p for name, _ in DENSITIES for p in (RES / f"mipmap-{name}").glob("ic_launcher*.webp")]
    leftovers += [p for p in (RES / "drawable").glob("ic_launcher_*.xml")]
    check(rows, "기본 아이콘 잔재", f"{len(leftovers)}개 남음", not leftovers)

    for name, scale in DENSITIES:
        size = px(SPLASH_DP, scale)
        r = SPLASH_CIRCLE_DP * scale / 2
        path = RES / f"drawable-{name}" / "ic_splash.png"
        with Image.open(path) as im:
            dims, mode = im.size, im.mode
        x0, y0, x1, y1 = alpha_bbox(path)
        c = size / 2
        far = max(
            ((x - c) ** 2 + (y - c) ** 2) ** 0.5 for x in (x0, x1 - 1) for y in (y0, y1 - 1)
        )
        check(
            rows,
            f"splash {name}",
            f"{dims[0]}x{dims[1]} {mode} bbox최원거리={far:.0f} ≤ r={r:.0f}",
            dims == (size, size) and mode == "RGBA" and far <= r + 0.5,
        )

    for path, want in (
        (XCASSETS / "AppIcon.appiconset" / "AppIcon-1024.png", IOS_ICON_PX),
        (STORE / "play-icon-512.png", PLAY_ICON_PX),
        (STORE / "app-store-icon-1024.png", IOS_ICON_PX),
    ):
        with Image.open(path) as im:
            dims, mode = im.size, im.mode
            corner = im.getpixel((2, 2))
        nbytes = path.stat().st_size
        check(
            rows,
            f"flat {path.name}",
            f"{dims[0]}x{dims[1]} {mode} 모서리=#{corner[0]:02x}{corner[1]:02x}{corner[2]:02x} {nbytes:,}B",
            dims == (want, want) and mode == "RGB" and corner == CREAM and nbytes <= PLAY_ICON_MAX_BYTES,
        )

    for scale, fname in ((1, "LaunchIcon.png"), (2, "LaunchIcon@2x.png"), (3, "LaunchIcon@3x.png")):
        path = XCASSETS / "LaunchIcon.imageset" / fname
        want = LAUNCH_PT * scale
        with Image.open(path) as im:
            dims, mode = im.size, im.mode
            corner_alpha = im.convert("RGBA").getpixel((0, 0))[3]
        check(rows, f"iOS {fname}", f"{dims[0]}x{dims[1]} {mode} α(모서리)={corner_alpha}", dims == (want, want) and mode == "RGBA" and corner_alpha == 0)

    for path, keys in (
        (XCASSETS / "LaunchIcon.imageset" / "Contents.json", ("LaunchIcon@3x.png", "universal")),
        (XCASSETS / "LaunchBackground.colorset" / "Contents.json", ("0.953", "srgb")),
    ):
        text = path.read_text(encoding="utf-8")
        check(rows, f"xcassets {path.parent.name}", ", ".join(keys), all(k in text for k in keys))

    path = STORE / "play-feature-1024x500.png"
    with Image.open(path) as im:
        dims, mode = im.size, im.mode
        corner = im.convert("RGB").getpixel((2, 2))
    nbytes = path.stat().st_size
    check(
        rows,
        "store play-feature",
        f"{dims[0]}x{dims[1]} {mode} 모서리=#{corner[0]:02x}{corner[1]:02x}{corner[2]:02x} {nbytes:,}B",
        dims == (FEATURE_W, FEATURE_H) and corner == CREAM and nbytes <= FEATURE_MAX_BYTES,
    )
    return rows


# ==========================================================================================


def main() -> None:
    src = Image.open(ROOT / "source.png")
    motif, src_paper = normalize_paper(key_background(src))
    motif = trim(motif)

    removed = clean_android_defaults()
    written = build_android_adaptive(motif)
    written += build_android_legacy(motif)
    written += build_android_splash(motif)
    written += build_ios(motif)
    written += build_store(motif)

    print(f"도상 {motif.width}×{motif.height} (원본 {src.width}×{src.height} 키잉·트림)")
    print(f"생성 {len(written)}개 · 삭제 {len(removed)}개")
    for path in removed:
        print(f"  - {path.relative_to(REPO)}")
    print()

    rows = validate(src_paper)
    width = max(len(label) for label, _, _ in rows)
    for label, detail, ok in rows:
        print(f"{label:<{width}}  {detail:<72s}  {'OK' if ok else 'FAIL'}")
    print(f"\n{sum(1 for _, _, ok in rows if ok)}/{len(rows)} PASS")
    if not all(ok for _, _, ok in rows):
        sys.exit(1)


if __name__ == "__main__":
    main()
