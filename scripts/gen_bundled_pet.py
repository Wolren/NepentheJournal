"""Generates the bundled companion sheet: a forest wisp called Lumen.

96x104 per frame, 8 columns by 5 rows (idle, wave, run, fail, checkin). Shapes are drawn at 4x and
reduced. The soft glow is a Compose radial gradient at draw time, so the sheet stays flat and small.
"""
import base64
import math
import os
import tempfile

from PIL import Image, ImageDraw, ImageChops

S = 4
FW, FH = 96, 104
COLS = 8
OUT = os.path.join(tempfile.gettempdir(), "nepenthe-petgen")
os.makedirs(OUT, exist_ok=True)

CREAM = (246, 239, 223)
CREAM_DEEP = (226, 212, 182)
CREAM_HI = (255, 251, 240)
OUTLINE = (11, 43, 38)
JADE = (127, 213, 186)
JADE_DARK = (74, 163, 137)
AMBER = (230, 176, 92)
INK = (16, 40, 34)
DIM = (148, 160, 150)


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def leaf_layer(length, width, angle, color, edge, tint=0.0):
    """One ear or sprout leaf: an ellipse, outlined, rotated about its base."""
    w, h = int(width * S), int(length * S)
    pad = S
    img = Image.new("RGBA", (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([0, 0, w + 2 * pad - 1, h + 2 * pad - 1], fill=edge + (255,))
    fill = mix(color, DIM, tint)
    d.ellipse([pad, pad, pad + w - 1, pad + h - 1], fill=fill + (255,))
    d.ellipse([pad + 1.2 * S, pad + 1.4 * S, pad + w * 0.55, pad + h * 0.5], fill=mix(fill, CREAM_HI, 0.35) + (255,))
    return img.rotate(-angle, resample=Image.BICUBIC, expand=True)


def draw_wisp(
    bob=0.0,
    squash=1.0,
    tilt=0.0,
    antenna_left=-20.0,
    antenna_right=20.0,
    eyes="open",
    dim=0.0,
    tail=1.0,
    sparkle=0.0,
    speed=0.0,
    mote_shift=0.0,
):
    """A floating lantern spirit: egg body, amber core, two antennae, a smoke tail."""
    W, H = FW * S, FH * S
    frame = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    cx = W / 2
    cy = H * 0.56 + bob * S

    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sw = 15 * S
    sd.ellipse([cx - sw, H * 0.88 - 2 * S, cx + sw, H * 0.88 + 3 * S], fill=(0, 0, 0, 52))
    frame.alpha_composite(shadow)

    body = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)

    rx = 17 * S / max(0.65, squash ** 0.5)
    ry = 19 * S * squash
    top = cy - ry
    bottom = cy + ry
    skin = mix(CREAM, DIM, dim * 0.5)
    skin_deep = mix(CREAM_DEEP, DIM, dim * 0.5)

    # Smoke tail: three fading puffs trailing down and back.
    for i, (dx, dy, radius, alpha) in enumerate(((9, 14, 3.8, 85), (12, 19, 3.0, 62), (14, 24, 2.2, 42))):
        px = cx + dx * S * tail
        py = cy + dy * S + math.sin(mote_shift + i * 1.1) * 1.8 * S
        puff = Image.new("RGBA", (int(radius * 2 * S) + 2, int(radius * 2 * S) + 2), (0, 0, 0, 0))
        pd = ImageDraw.Draw(puff)
        pd.ellipse([0, 0, puff.width - 1, puff.height - 1], fill=JADE_DARK + (int(alpha * (1 - dim * 0.6)),))
        body.alpha_composite(puff, (int(px - puff.width / 2), int(py - puff.height / 2)))

    # Antennae: two curved stalks with amber tips.
    for angle, side in ((antenna_left, -1), (antenna_right, 1)):
        base_x = cx + side * 5 * S
        base_y = top + 4 * S
        tip_x = base_x + math.sin(math.radians(angle)) * 15 * S
        tip_y = base_y - math.cos(math.radians(angle)) * 15 * S
        ctrl_x = base_x + math.sin(math.radians(angle)) * 6 * S + side * 2 * S
        ctrl_y = base_y - math.cos(math.radians(angle)) * 12 * S
        points = []
        for step in range(9):
            u = step / 8
            px = (1 - u) ** 2 * base_x + 2 * (1 - u) * u * ctrl_x + u * u * tip_x
            py = (1 - u) ** 2 * base_y + 2 * (1 - u) * u * ctrl_y + u * u * tip_y
            points.append((px, py))
        d.line(points, fill=JADE_DARK + (255,), width=int(1.7 * S), joint="curve")
        bulb = 2.4 * S
        d.ellipse([tip_x - bulb, tip_y - bulb, tip_x + bulb, tip_y + bulb], fill=AMBER + (245,))
        d.ellipse([tip_x - bulb * 0.45, tip_y - bulb * 0.55, tip_x + bulb * 0.1, tip_y - bulb * 0.05],
                  fill=CREAM_HI + (240,))

    # Body: outline, fill, soft top light.
    pad = 1.2 * S
    d.ellipse([cx - rx - pad, top - pad, cx + rx + pad, bottom + pad], fill=OUTLINE + (255,))
    d.ellipse([cx - rx, top, cx + rx, bottom], fill=skin + (255,))
    d.ellipse([cx - rx * 0.86, top + ry * 0.12, cx + rx * 0.86, bottom - ry * 0.18], fill=skin_deep + (255,))
    d.ellipse([cx - rx * 0.88, top + ry * 0.18, cx + rx * 0.88, bottom - ry * 0.24], fill=skin + (255,))
    d.arc([cx - rx * 0.66, top + ry * 0.14, cx + rx * 0.16, top + ry * 0.62], 200, 320,
          fill=mix(skin, CREAM_HI, 0.8) + (255,), width=int(1.4 * S))

    # Two amber freckles low on the belly, the only warm mark on the body.
    for side in (-1, 1):
        fx = cx + side * 5 * S
        fy = cy + 11 * S
        d.ellipse([fx - 1.2 * S, fy - 1.2 * S, fx + 1.2 * S, fy + 1.2 * S], fill=AMBER + (int(150 * (1 - dim * 0.6)),))

    # Eyes.
    eye_y = cy - 1.5 * S
    for side in (-1, 1):
        ex = cx + side * 6.2 * S
        if eyes == "open":
            d.ellipse([ex - 2.8 * S, eye_y - 3.2 * S, ex + 2.8 * S, eye_y + 3.2 * S], fill=INK + (255,))
            d.ellipse([ex - 1.7 * S, eye_y - 2.9 * S, ex - 0.4 * S, eye_y - 1.4 * S], fill=CREAM_HI + (245,))
        elif eyes == "blink":
            d.arc([ex - 2.8 * S, eye_y - 2.0 * S, ex + 2.8 * S, eye_y + 2.8 * S], 200, 340,
                  fill=INK + (255,), width=int(1.8 * S))
        else:
            d.arc([ex - 2.8 * S, eye_y - 0.6 * S, ex + 2.8 * S, eye_y + 4.4 * S], 200, 340,
                  fill=INK + (255,), width=int(1.8 * S))

    if speed > 0:
        for i, dy in enumerate((-8, 1, 10)):
            y = cy + dy * S
            length = (14 + i * 5) * S * speed
            d.line([cx - 24 * S - length, y, cx - 22 * S, y], fill=(255, 255, 255, int(70 * speed)),
                   width=int(1.4 * S))

    if sparkle > 0:
        star_x, star_y = cx, top - 10 * S
        arm = 6 * S * sparkle
        d.polygon([(star_x, star_y - arm), (star_x + arm * 0.28, star_y - arm * 0.28), (star_x + arm, star_y),
                   (star_x + arm * 0.28, star_y + arm * 0.28), (star_x, star_y + arm),
                   (star_x - arm * 0.28, star_y + arm * 0.28), (star_x - arm, star_y),
                   (star_x - arm * 0.28, star_y - arm * 0.28)], fill=CREAM_HI + (245,))
        d.polygon([(star_x, star_y - arm * 0.5), (star_x + arm * 0.5, star_y), (star_x, star_y + arm * 0.5),
                   (star_x - arm * 0.5, star_y)], fill=AMBER + (210,))

    if abs(tilt) > 0.01:
        body = body.rotate(tilt, resample=Image.BICUBIC, center=(cx, cy))
    frame.alpha_composite(body)
    return frame.resize((FW, FH), Image.LANCZOS)

BUNDLED_CLIPS = [("idle", 6), ("wave", 4), ("run", 6), ("fail", 8), ("checkin", 6)]

# Sheet rows in declaration order, with the timing BundledPet.kt repeats.
CLIP_ROWS = [
    ("idle", 0, 6, 6.5, True, 3400),
    ("wave", 1, 4, 6.5, True, 2000),
    ("run", 2, 6, 11.0, True, 1400),
    ("fail", 3, 8, 6.0, False, 0),
    ("checkin", 4, 6, 6.0, False, 0),
]


def frames_for(clip):
    out = []
    if clip == "idle":
        for i in range(6):
            ph = i / 6 * 2 * math.pi
            out.append(dict(bob=math.sin(ph) * 2.4 + 1.2, antenna_left=-20 + math.sin(ph) * 7,
                            antenna_right=20 - math.sin(ph) * 7,
                            eyes="blink" if i == 4 else "open", mote_shift=i * 0.8))
    elif clip == "wave":
        for i in range(4):
            out.append(dict(bob=-0.8 + (0.4 if i % 2 else 0.0), antenna_left=-20,
                            antenna_right=[20, 64, 36, 20][i],
                            eyes="blink" if i == 2 else "open", mote_shift=i * 0.9))
    elif clip == "run":
        for i in range(6):
            ph = i / 6 * 2 * math.pi
            out.append(dict(bob=abs(math.sin(ph)) * 3.6 - 1.2, tilt=-12, squash=1.04,
                            antenna_left=-38 + math.sin(ph) * 6, antenna_right=-2 + math.sin(ph) * 6,
                            tail=1.5, speed=0.85, mote_shift=i * 1.7))
    elif clip == "fail":
        for i in range(8):
            t = i / 7
            out.append(dict(squash=1.0 - 0.18 * t, bob=3.2 * t, dim=0.55 * t,
                            antenna_left=-20 - 50 * t, antenna_right=20 + 50 * t,
                            eyes="sad", tail=1.0 - 0.6 * t, mote_shift=i * 0.4))
    elif clip == "checkin":
        for i in range(6):
            ph = i / 6 * 2 * math.pi
            out.append(dict(bob=math.sin(ph) * 1.8 + 1.2, tilt=math.sin(ph + 0.7) * 11,
                            sparkle=max(0.0, math.sin(ph)) ** 0.7,
                            antenna_left=-20 - math.sin(ph) * 4, antenna_right=20 + math.sin(ph) * 4,
                            mote_shift=i * 0.8))
    return out


KOTLIN_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "composeApp", "src", "commonMain",
    "kotlin", "app", "journal", "ui", "pet", "BundledPetData.kt",
)


def write_kotlin(payload):
    b64 = base64.b64encode(payload).decode()
    # A JVM class constant holds at most 65535 bytes, so each part stays under that and the
    # full string is joined at runtime instead of being folded into one constant.
    parts = [b64[i:i + 39000] for i in range(0, len(b64), 39000)]
    lines = [
        "package app.journal.ui.pet",
        "",
        "// Generated by scripts/gen_bundled_pet.py from the drawing code in that script; do not edit.",
        "// Lumen is original art owned by this project, so it ships under the app's own licence.",
        "",
    ]
    for index, part in enumerate(parts, 1):
        lines.append("internal const val LUMEN_SHEET_PART%d =" % index)
        chunks = [part[i:i + 120] for i in range(0, len(part), 120)]
        for position, chunk in enumerate(chunks):
            suffix = " +" if position < len(chunks) - 1 else ""
            lines.append('    "%s"%s' % (chunk, suffix))
        lines.append("")
    joined = " + ".join("LUMEN_SHEET_PART%d" % index for index in range(1, len(parts) + 1))
    lines.append("internal val LUMEN_SHEET_BASE64: String = " + joined)
    lines.append("")
    text = "\n".join(lines)
    with open(KOTLIN_PATH, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    return len(lines)

def main():
    sheet = Image.new("RGBA", (FW * COLS, FH * len(BUNDLED_CLIPS)), (0, 0, 0, 0))
    for row, (name, frames) in enumerate(BUNDLED_CLIPS):
        for col, params in enumerate(frames_for(name)[:frames]):
            sheet.alpha_composite(draw_wisp(**params), (col * FW, row * FH))

    preview = Image.new("RGBA", sheet.size, (10, 38, 33, 255))
    preview.alpha_composite(sheet)
    preview.convert("RGB").save(os.path.join(OUT, "bundled-pet-preview.png"))

    webp = os.path.join(OUT, "lumen.webp")
    sheet.save(webp, "WEBP", lossless=True, method=6, quality=100)
    payload = open(webp, "rb").read()
    lines = write_kotlin(payload)
    print("sheet", sheet.size, "webp", len(payload), "kotlin lines", lines)


if __name__ == "__main__":
    main()