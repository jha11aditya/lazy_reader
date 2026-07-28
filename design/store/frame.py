#!/usr/bin/env python3
"""Frame Lazy Reader screenshots for the Play Store listing.

The phone captures at 467x1040 (ratio 2.23). Play rejects any screenshot whose
long side exceeds twice its short side, so the raw files cannot be uploaded.
Canvas is therefore 800x1400 (ratio 1.75), chosen so the screenshot sits at its
NATIVE size -- upscaling a 467px-wide source would visibly soften the text.
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SRC = os.path.dirname(os.path.abspath(__file__))
ORIG = os.path.join(SRC, "orig")
OUT = os.path.join(SRC, "store")
MOSS = "/home/adi/Documents/apps/lazy_reader/app/src/main/res/drawable-nodpi/dashboard_background.jpg"

W, H = 800, 1400
SHOT_W, SHOT_H = 467, 1040
TOP = 232                      # caption band height
X = (W - SHOT_W) // 2
FOREST = (30, 77, 43)
BROWN = (139, 94, 60)

SHOTS = [
    ("163230.png", "Turn pages with your voice"),
    ("163002.png", "Pick up exactly where you left off"),
    ("163632.png", "Say “stop” to lock the screen"),
    ("163307.png", "PDFs and EPUBs — in any language"),
    ("163014.png", "100% offline — enforced by Android"),
]


def font(size, bold=False):
    names = (
        ["DejaVuSans-Bold.ttf", "LiberationSans-Bold.ttf", "NotoSans-Bold.ttf"]
        if bold else
        ["DejaVuSans.ttf", "LiberationSans-Regular.ttf", "NotoSans-Regular.ttf"]
    )
    for root, _, files in os.walk("/usr/share/fonts"):
        for n in names:
            if n in files:
                return ImageFont.truetype(os.path.join(root, n), size)
    return ImageFont.load_default()


def background():
    """Moss photo, blurred and darkened so it reads as brand texture, not detail."""
    bg = Image.open(MOSS).convert("RGB")
    scale = max(W / bg.width, H / bg.height)
    bg = bg.resize((int(bg.width * scale) + 1, int(bg.height * scale) + 1), Image.LANCZOS)
    bg = bg.crop((0, 0, W, H)).filter(ImageFilter.GaussianBlur(18))
    # Darken toward forest green for consistent caption contrast.
    tint = Image.new("RGB", (W, H), FOREST)
    bg = Image.blend(bg, tint, 0.55)
    return Image.blend(bg, Image.new("RGB", (W, H), (0, 0, 0)), 0.30)


def rounded(img, radius=26):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1],
                                           radius=radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def wrap(draw, text, fnt, maxw):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if draw.textlength(t, font=fnt) <= maxw:
            cur = t
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def build(name, caption, out_path):
    canvas = background()
    d = ImageDraw.Draw(canvas)

    f = font(40, bold=True)
    lines = wrap(d, caption, f, W - 110)
    lh = 52
    y = (TOP - len(lines) * lh) // 2 - 6
    for ln in lines:
        x = (W - d.textlength(ln, font=f)) / 2
        d.text((x + 1, y + 2), ln, font=f, fill=(0, 0, 0, 120))   # soft drop shadow
        d.text((x, y), ln, font=f, fill=(255, 255, 255))
        y += lh

    # Warm-brown accent rule, echoing the dashboard card borders.
    d.rounded_rectangle([W // 2 - 44, TOP - 40, W // 2 + 44, TOP - 35], radius=3, fill=BROWN)

    shot = Image.open(os.path.join(ORIG, name)).convert("RGB")
    if shot.size != (SHOT_W, SHOT_H):
        shot = shot.resize((SHOT_W, SHOT_H), Image.LANCZOS)
    shot = rounded(shot)

    # Drop shadow behind the device plate.
    sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle(
        [X - 4, TOP + 6, X + SHOT_W + 4, TOP + SHOT_H + 10], radius=30, fill=(0, 0, 0, 130))
    canvas = Image.alpha_composite(canvas.convert("RGBA"), sh.filter(ImageFilter.GaussianBlur(14)))

    canvas.paste(shot, (X, TOP), shot)
    ImageDraw.Draw(canvas).rounded_rectangle(
        [X, TOP, X + SHOT_W - 1, TOP + SHOT_H - 1], radius=26,
        outline=(255, 255, 255, 55), width=2)

    canvas.convert("RGB").save(out_path, "PNG", optimize=True)


os.makedirs(OUT, exist_ok=True)
for i, (name, cap) in enumerate(SHOTS, 1):
    p = os.path.join(OUT, f"{i:02d}_{name.replace('.png','')}.png")
    build(name, cap, p)
    im = Image.open(p)
    r = max(im.size) / min(im.size)
    print(f"{os.path.basename(p):22s} {im.size[0]}x{im.size[1]}  ratio {r:.2f}  "
          f"{'OK' if r <= 2.0 and min(im.size) >= 320 else 'FAIL'}")
