"""Original Hollow pixel art; run here: python generate_hollow_skins.py.

Stdlib + Pillow only. Reference artwork is never loaded or copied.
Only the head has an overlay; both standard limb UV sets are painted.
The preview uses the model's mirrored right limbs.
"""

import sys

sys.dont_write_bytecode = True

from PIL import Image, ImageDraw, ImageFont
from generate_profession_skins import OUT, ROOT, SIDES, UV, Painter
from generate_hostile_skins import figure, overlay, validate_skin


PREVIEW = ROOT / "docs/hollow_skins_preview.png"
NAMES = ("THE PREACHER", "THE BUTCHER", "THE HOLLOW-EYED")
BONE, BLOOD = "e2d6b4", "6e1f1f"
INK, ASH, ASH_DARK, ASH_LIGHT = "1d2020", "999b8e", "656b63", "bfc0ac"
ROPE, ROPE_LIGHT = "76674d", "b0a080"
PALETTES = (
    ("424440", "292e2d", "686b60", "615749"),
    ("544c40", "333531", "7d7460", "6c5742"),
    ("75796e", "414a45", "a2a394", "5b5144"),
)


def cloth(p, part, palette):
    base, dark, light, mud = palette
    p.all_faces(part, base)
    for face in SIDES:
        w = UV[part][face][2]
        p.rect(part, face, 0, 0, 0, 11, dark)
        p.rect(part, face, 1, 0, w - 1, 0, light)
        p.rect(part, face, w - 2, 2, w - 2, 8, light)
        p.rect(part, face, 1, 5, 1, 10, dark)
        p.rect(part, face, 1, 9, w - 1, 11, mud)
        p.dot(part, face, w - 1, 9, base)
        p.dot(part, face, 1, 11, dark)
    p.rect(part, "bottom", 0, 0, UV[part]["bottom"][2] - 1, 3, dark)


def skin_arm(p, arm, start):
    p.side_band(arm, start, 11, ASH)
    p.rect(arm, "bottom", 0, 0, 3, 3, ASH_DARK)
    for side in SIDES:
        p.rect(arm, side, 0, start, 0, 11, ASH_DARK)
        p.rect(arm, side, 2, start, 2, min(start + 2, 11), ASH_LIGHT)
        p.rect(arm, side, 1, 10, 2, 10, ASH_LIGHT)
    p.rect(arm, "back", 1, 9, 2, 9, "777e74")


def feet(p, leg, wrapped):
    p.side_band(leg, 8, 11, ASH_DARK)
    p.rect(leg, "bottom", 0, 0, 3, 3, "49483d")
    for face in SIDES:
        p.rect(leg, face, 1, 8, 2, 10, ASH)
        if wrapped:
            for y in (8, 10):
                p.rect(leg, face, 0, y, 3, y, ROPE)
                p.rect(leg, face, 1, y, 2, y, ROPE_LIGHT)
        else:
            p.rect(leg, face, 1, 9, 2, 9, ASH_LIGHT)
    for x in (0, 2):
        p.dot(leg, "front", x, 11, ASH_LIGHT)


def charm(p):
    # Four pale corners around a dark opening: a tiny bone ring, not a tag.
    for x, y in ((2, 0), (5, 0), (2, 1), (5, 1), (3, 2), (4, 2)):
        p.dot("body", "front", x, y, ROPE_LIGHT)
    for x, y in ((3, 3), (2, 4), (4, 4), (3, 5)):
        p.dot("body", "front", x, y, BONE)
    p.dot("body", "front", 3, 4, INK)
    p.dot("body", "front", 3, 5, "b2a485")


def handprint(p, part, face, x, y):
    # Dry palm with staggered finger marks; no wet highlights.
    for dx, dy in ((0, 0), (1, 1), (2, 0), (0, 2), (1, 2),
                   (2, 2), (1, 3), (2, 3), (2, 4)):
        p.dot(part, face, x + dx, y + dy, BLOOD)
    p.dot(part, face, x + 1, y + 3, "4b2926")


def head(p, index):
    p.all_faces("head", ASH)
    p.rect("head", "bottom", 0, 0, 7, 7, ASH_DARK)
    for side in SIDES:
        p.rect("head", side, 0, 0, 0, 7, ASH_DARK)
        p.rect("head", side, 2, 1, 5, 2, ASH_LIGHT)
        p.rect("head", side, 1, 6, 6, 7, ASH_DARK)
    for x in (1, 5):
        p.rect("head", "front", x, 3, x + 1, 4, "444a44")
        p.dot("head", "front", x + 1, 4, INK)
    p.dot("head", "front", 3, 5, ASH_LIGHT)
    p.rect("head", "front", 3, 6, 4, 6, "47473e")
    if index == 0:
        p.rect("head", "front", 1, 1, 6, 6, "d3d2bc")
        p.rect("head", "front", 1, 3, 6, 4, INK)
        p.dot("head", "front", 2, 4, "777c6b")
        p.dot("head", "front", 5, 4, "777c6b")
        p.rect("head", "front", 3, 5, 4, 5, BONE)
        p.dot("head", "front", 2, 5, "858b7c")
        p.dot("head", "front", 5, 6, "858b7c")
        p.rect("head", "front", 3, 7, 4, 7, "b4b7a4")
        overlay(p, "top", (0, 0, 7, 7), "424440")
        overlay(p, "top", (2, 1, 5, 6), "595c53")
        for side in ("right", "left", "back"):
            overlay(p, side, (0, 0, 7, 7), "424440")
            overlay(p, side, (1, 1, 2, 5), "595c53")
            overlay(p, side, (5, 2, 6, 7), "292e2d")
            overlay(p, side, (3, 6, 4, 7), "686b60")
        for box, color in (
            ((0, 0, 7, 0), "686b60"), ((0, 1, 1, 1), "595c53"),
            ((6, 1, 7, 1), "595c53"), ((0, 2, 0, 7), "595c53"),
            ((7, 2, 7, 7), "292e2d"), ((1, 6, 1, 7), "424440"),
            ((6, 6, 6, 7), "424440"),
        ):
            overlay(p, "front", box, color)
    elif index == 1:
        p.rect("head", "top", 1, 1, 6, 5, ASH_LIGHT)
        # Healed, crooked scalp scar travels over the crown to the temple.
        for side, points in (
            ("top", ((5, 1), (5, 2), (4, 3), (4, 4), (4, 5), (3, 6), (3, 7))),
            ("front", ((3, 0), (3, 1), (4, 2))),
            ("right", ((2, 1), (3, 2), (3, 3))),
        ):
            for x, y in points:
                p.dot("head", side, x, y, "79685d")
        p.rect("head", "front", 1, 2, 2, 2, "5a6057")
        p.rect("head", "front", 5, 2, 6, 2, "5a6057")
        p.rect("head", "front", 2, 7, 5, 7, ASH_LIGHT)
        p.dot("head", "front", 6, 5, "7a8074")
        p.rect("head", "back", 2, 4, 5, 5, ASH_LIGHT)
    else:
        for x in (1, 5):
            p.rect("head", "front", x, 2, x + 1, 4, INK)
            p.dot("head", "front", x, 5, ASH_DARK)
        for side in SIDES:
            p.rect("head", side, 0, 5, 7, 7, "787462")
            p.rect("head", side, 1, 5, 6, 5, "b8b39c")
            p.rect("head", side, 0, 7, 7, 7, "56574c")
            p.rect("head", side, 3, 6, 6, 6, "999780")
        p.dot("head", "front", 1, 7, BLOOD)
        overlay(p, "top", (0, 0, 7, 7), "303531")
        overlay(p, "top", (2, 1, 3, 6), "596052")
        overlay(p, "top", (5, 2, 6, 5), "454c43")
        for side in SIDES:
            overlay(p, side, (0, 0, 7, 0), "303531")
            for x, length in ((0, 3), (2, 1), (4, 2), (7, 4)):
                overlay(p, side, (x, 0, x, length), "454c43")
            if side != "front":
                overlay(p, side, (1, 1, 6, 3), "303531")
                for x in (1, 4, 6):
                    overlay(p, side, (x, 2, x, 4 + x % 2), "596052")
                overlay(p, side, (2, 1, 2, 2), BONE)
                overlay(p, side, (5, 3, 5, 4), "b2a485")
        overlay(p, "front", (6, 0, 6, 1), BONE)
        overlay(p, "back", (6, 6, 7, 6), "b8b39c")
        overlay(p, "back", (7, 7, 7, 7), "999780")


def preacher(p):
    for arm in ("right_arm", "left_arm"):
        skin_arm(p, arm, 10)
        for side in SIDES:
            p.rect(arm, side, 1, 7, 3, 8, "777567")
            p.dot(arm, side, 2, 9, "424440")
    for leg in ("right_leg", "left_leg"):
        feet(p, leg, True)
        for side in SIDES:
            p.rect(leg, side, 0, 0, 0, 8, "292e2d")
            p.rect(leg, side, 2, 0, 2, 6, "686b60")
            p.dot(leg, side, 1, 8, "615749")
    for side in ("front", "back"):
        p.rect("body", side, 1, 0, 1, 7, "777567")
        p.rect("body", side, 6, 0, 6, 7, "777567")
        p.rect("body", side, 2, 6, 5, 6, "59594e")
    p.side_band("body", 8, 8, ROPE_LIGHT)
    p.side_band("body", 9, 9, ROPE)
    p.rect("body", "front", 5, 8, 5, 11, ROPE_LIGHT)
    p.dot("body", "front", 6, 10, ROPE_LIGHT)
    for x, y in ((1, 3), (6, 4), (1, 5), (6, 6)):
        p.dot("body", "front", x, y, BONE)
    handprint(p, "body", "back", 2, 2)
    p.rect("body", "back", 1, 10, 2, 11, "85806a")
    p.dot("body", "back", 1, 10, BONE)


def butcher(p):
    for arm in ("right_arm", "left_arm"):
        p.all_faces(arm, ASH)
        skin_arm(p, arm, 0)
        p.side_band(arm, 7, 8, "53493b")
        p.rect(arm, "front", 1, 7, 2, 7, ROPE_LIGHT)
        p.dot(arm, "front", 2, 10, BLOOD)
        p.rect(arm, "back", 1, 3, 2, 4, "81877b")
    p.all_faces("body", ASH_DARK)
    for side in ("front", "back"):
        p.rect("body", side, 0, 0, 7, 2, ASH)
        p.rect("body", side, 1, 0, 6, 0, ASH_LIGHT)
    for side in SIDES:
        w = UV["body"][side][2]
        p.rect("body", side, 0, 8, w - 1, 9, "352f29")
    # Wide unbroken apron and shoulder straps imply mass on the fixed model.
    p.rect("body", "front", 0, 3, 7, 11, "59412f")
    p.rect("body", "front", 1, 3, 6, 10, "7c6044")
    p.rect("body", "front", 2, 6, 5, 10, "6a4d36")
    for x in (1, 6):
        p.rect("body", "front", x, 0, x, 3, "59412f")
        p.dot("body", "front", x, 3, ROPE_LIGHT)
    for y in range(7):
        for x in (1 + y // 2, 6 - y // 2):
            p.dot("body", "back", x, y, "59412f")
    p.rect("body", "back", 3, 8, 4, 10, ROPE_LIGHT)
    handprint(p, "body", "front", 5, 5)
    p.rect("body", "front", 1, 9, 2, 10, BLOOD)
    for leg in ("right_leg", "left_leg"):
        feet(p, leg, False)
        for side in ("front", "right", "left"):
            p.rect(leg, side, 0, 0, 3, 4, "59412f")
            p.rect(leg, side, 1, 0, 2, 3, "7c6044")
            p.dot(leg, side, 2, 4, BLOOD)
        # A dull rectangular blade sits inside a dark leather thigh holster.
        p.rect(leg, "right", 0, 2, 2, 6, "302e29")
        p.rect(leg, "right", 1, 0, 1, 2, "493d30")
        p.rect(leg, "right", 0, 3, 2, 3, "9b9d8a")
        p.dot(leg, "right", 0, 4, "777f75")
        p.dot(leg, "right", 1, 6, ROPE_LIGHT)
        p.rect(leg, "front", 0, 3, 0, 6, "302e29")
        p.dot(leg, "front", 0, 3, "9b9d8a")


def hollow_eyed(p):
    for arm in ("right_arm", "left_arm"):
        skin_arm(p, arm, 3)
        p.rect(arm, "front", 0, 2, 0, 5, "414a45")
        p.dot(arm, "front", 2, 3, "75796e")
        p.side_band(arm, 8, 9, "696452")
        p.rect(arm, "front", 1, 8, 3, 8, ROPE_LIGHT)
        p.dot(arm, "back", 1, 6, BLOOD)
    for side in ("front", "back"):
        # Ragged openings reveal shaded skin; the base remains fully opaque.
        p.rect("body", side, 0, 2, 0, 10, "303a35")
        p.rect("body", side, 7, 2, 7, 10, "303a35")
        for y in (5, 7, 9):
            p.rect("body", side, 4, y, 6, y, ASH_DARK)
            p.rect("body", side, 4, y, 5, y, ASH_LIGHT)
        p.rect("body", side, 1, 9, 2, 11, ASH_DARK)
        p.dot("body", side, 2, 9, ASH_LIGHT)
        p.rect("body", side, 3, 10, 3, 11, "a2a394")
    handprint(p, "body", "back", 1, 2)
    p.rect("body", "front", 1, 6, 1, 7, BLOOD)
    for leg in ("right_leg", "left_leg"):
        feet(p, leg, True)
        p.rect(leg, "front", 1, 4, 2, 6, ASH_DARK)
        p.rect(leg, "front", 1, 4, 2, 4, ASH_LIGHT)
        p.dot(leg, "front", 2, 5, ASH)
        p.rect(leg, "back", 1, 2, 3, 4, "6b5e4b")
        p.dot(leg, "back", 1, 2, ROPE_LIGHT)
        p.dot(leg, "back", 3, 4, ROPE_LIGHT)


def make_skin(index):
    p = Painter()
    for part in ("body", "right_arm", "left_arm", "right_leg", "left_leg"):
        palette = PALETTES[index]
        if index == 2 and "leg" in part:
            palette = ("49483f", "2f3531", "717365", "605542")
        cloth(p, part, palette)
    head(p, index)
    (preacher, butcher, hollow_eyed)[index](p)
    charm(p)
    return p.image


def preview_sheet(skins):
    sheet = Image.new("RGB", (948, 410), "#171d24")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    draw.fontmode = "1"
    draw.text((18, 12), "LAZARUS / THE HOLLOW", fill="#" + BONE, font=font)
    draw.text((18, 30), "Ash on skin. Bone on cord. Voices after dark.",
              fill="#aeb2a3", font=font)
    for index, skin in enumerate(skins):
        x = 12 + index * 312
        draw.rectangle((x, 52, x + 300, 394), fill="#272e30", outline="#4b514b")
        draw.rectangle((x + 1, 53, x + 299, 55), fill="#" + BLOOD)
        draw.text((x + 12, 65), f"0{index + 1} / {NAMES[index]}",
                  fill="#" + BONE, font=font)
        for view, offset in (("front", 16), ("back", 158)):
            rendered = figure(skin, view).resize((128, 256), Image.Resampling.NEAREST)
            sheet.paste(rendered, (x + offset, 86), rendered)
            draw.text((x + offset + 48, 350), view, fill="#b5bbae", font=font)
        draw.text((x + 12, 377), f"hollow_{index + 1}.png", fill="#aeb2a3", font=font)
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW)
    with Image.open(PREVIEW) as saved:
        assert saved.size == sheet.size and saved.tobytes() == sheet.tobytes()


def main():
    skins = [make_skin(index) for index in range(3)]
    assert len({skin.tobytes() for skin in skins}) == 3, "skins must be distinct"
    for view in ("front", "back"):
        assert len({figure(skin, view).tobytes() for skin in skins}) == 3
    OUT.mkdir(parents=True, exist_ok=True)
    for index, skin in enumerate(skins):
        name = f"hollow_{index + 1}.png"
        validate_skin(skin, name)
        assert skin.tobytes() == make_skin(index).tobytes(), f"{name}: nondeterministic"
        skin.save(OUT / name)
        with Image.open(OUT / name) as saved:
            validate_skin(saved, name)
            assert saved.tobytes() == skin.tobytes(), f"{name}: save mismatch"
    preview_sheet(skins)
    print(f"Generated and validated 3 deterministic Hollow skins and {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
