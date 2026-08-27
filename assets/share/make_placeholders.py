#!/usr/bin/env python3
"""등급별 공유 카드 자리표시자 5장을 만든다 (KAN-30 3단계).

확정 디자인이 아니다. 카카오 피드 템플릿은 `image-url`이 https로 열려 있어야 카드가 그려지므로,
디자인이 나오기 전에도 그 자리를 채울 그림이 필요하다 — 없으면 공유 카드가 이미지 없이 나가고
"카드가 왜 이래 보이지"를 실물로 확인할 수 없다.

스크립트로 두는 이유는 재생성이다. 규격(800x400)·색·문구가 바뀌면 PNG를 손으로 다시 그리는 대신
여기를 고쳐 다시 돌린다. 사람이 카드에서 바로 "임시"임을 알 수 있게 워터마크를 박는 것도 같은
이유다 — 자리표시자가 확정 자산인 척하고 배포로 새어 나가는 것이 가장 나쁜 결말이다.

실행:
    /Users/iseongju/accentury/.venv/bin/python assets/share/make_placeholders.py

산출물은 이 파일과 같은 디렉터리의 `<tier>.png` 5장이다. 파일명은 백엔드 `application.yml`의
`image-url` 파일명과 같다 — 업로드(KAN-132)가 이름을 다시 정하지 않아도 되게 맞춰 둔다.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# 카카오 피드 카드 권장 비율 2:1. 규격을 여기에 박아 두면 5장이 서로 어긋날 수 없다.
WIDTH, HEIGHT = 800, 400

# 웹 토큰(web/src/tokens.css)에서 가져온 값이다. 카드와 랜딩이 다른 파랑을 쓰면
# 공유 → 유입 한 흐름이 두 브랜드처럼 보인다.
BACKGROUND = "#eff6ff"  # --color-background
FOREGROUND = "#1e3a5f"  # 등급명
MUTED = "#4d6f96"  # 하단 브랜드 줄
BRAND = "#2563eb"  # 좌측 세로 띠
WATERMARK = "#dc2626"  # 자리표시자 표시 — 빨강이어야 눈에 걸린다

BAND_WIDTH = 24
PADDING = 56

BRAND_LINE = "Accentury · 경남 사투리 레벨 테스트"
WATERMARK_TEXT = "PLACEHOLDER"

# 등급 code(소문자)와 화면에 뜨는 이름. 순서가 곧 rank이고 카드 우하단의 "N / 5"가 그 값이다.
# 점수는 싣지 않는다 — 수신자는 남의 결과를 보는 게 아니라 자기 테스트를 응시한다 (KAN-30 요구).
TIERS = [
    ("outsider", "외지인"),
    ("traveler", "여행객"),
    ("wannabe", "사투리 호소인"),
    ("honorary", "명예주민"),
    ("native", "경남 토박이"),
]

# 한글이 나오는 폰트만 쓴다. Pillow 기본 비트맵 폰트는 한글을 네모로 그리므로 마지막 폴백은
# "그래도 그림은 나온다" 수준이고, 그때는 경고를 띄워 사람이 알아채게 한다.
FONT_CANDIDATES = [
    # (경로, Bold 인덱스, Regular 인덱스) — .ttc는 여러 굵기가 든 묶음이라 인덱스로 고른다
    ("/System/Library/Fonts/AppleSDGothicNeo.ttc", 6, 0),
    ("/System/Library/Fonts/Supplemental/AppleGothic.ttf", 0, 0),
]


def load_fonts() -> tuple[ImageFont.ImageFont, ImageFont.ImageFont, ImageFont.ImageFont]:
    """(등급명, 브랜드 줄, 워터마크) 폰트를 고른다. 셋 다 같은 가족이어야 카드가 한 장으로 보인다."""
    for path, bold_index, regular_index in FONT_CANDIDATES:
        if not Path(path).exists():
            continue
        return (
            ImageFont.truetype(path, 84, index=bold_index),
            ImageFont.truetype(path, 26, index=regular_index),
            ImageFont.truetype(path, 20, index=bold_index),
        )

    print(
        "경고: 한글 폰트를 찾지 못해 기본 폰트로 그린다 — 등급명이 네모로 나올 수 있다.",
        file=sys.stderr,
    )
    default = ImageFont.load_default()
    return default, default, default


def draw_card(name: str, rank: int, fonts: tuple[ImageFont.ImageFont, ...]) -> Image.Image:
    """카드 한 장. 좌측 정렬인 이유는 등급명 길이가 2~7자로 갈려서다 — 가운데 정렬하면 장마다 무게중심이 움직인다."""
    tier_font, brand_font, mark_font = fonts

    card = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
    draw = ImageDraw.Draw(card)

    draw.rectangle([0, 0, BAND_WIDTH, HEIGHT], fill=BRAND)

    # 등급명은 세로 가운데보다 살짝 위다. 아래에 브랜드 줄이 붙으므로 정가운데에 두면 아래로 쏠려 보인다.
    draw.text((BAND_WIDTH + PADDING, HEIGHT // 2 - 40), name, font=tier_font, fill=FOREGROUND, anchor="lm")

    draw.text((BAND_WIDTH + PADDING, HEIGHT - PADDING), BRAND_LINE, font=brand_font, fill=MUTED, anchor="ls")
    draw.text((WIDTH - PADDING, HEIGHT - PADDING), f"{rank} / {len(TIERS)}", font=brand_font, fill=MUTED, anchor="rs")

    draw.text((WIDTH - PADDING, PADDING), WATERMARK_TEXT, font=mark_font, fill=WATERMARK, anchor="rt")

    return card


def main() -> None:
    out_dir = Path(__file__).resolve().parent
    fonts = load_fonts()

    for rank, (code, name) in enumerate(TIERS, start=1):
        path = out_dir / f"{code}.png"
        # optimize=True는 팔레트가 단순한 이 그림에서 파일을 크게 줄인다 — 카카오 상한(5MB)까지
        # 갈 일은 없지만, 자리표시자가 저장소에서 자리를 많이 차지할 이유도 없다.
        draw_card(name, rank, fonts).save(path, "PNG", optimize=True)
        print(f"{path.name}  {path.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
