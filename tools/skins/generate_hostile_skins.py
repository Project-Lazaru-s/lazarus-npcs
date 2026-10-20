"""Original deserter pixel art; run: python generate_hostile_skins.py.

Uses only stdlib and Pillow. Reference textures are never loaded or copied.
The preview reflects HumanoidModel's mirrored right limbs. Both standard limb
regions are populated; a left-only armband is impossible on this renderer.
"""

import sys

sys.dont_write_bytecode = True

from PIL import Image, ImageDraw, ImageFont
from generate_profession_skins import OUT, ROOT, SIDES, UV, Painter, face_crop, rgb, validate


PREVIEW = ROOT / "docs/hostile_skins_preview.png"
NAMES = ("THE SERGEANT", "THE MASKED ONE", "THE WOUNDED RUNAWAY")
COLORS = (
    ("596047", "30392d", "7d8060", "665540"),
    ("454f3a", "282f28", "707758", "5e503f"),
    ("667055", "394333", "909278", "75624c"),
)
PEOPLE = (
    ("b58462", "76523f", "39332c", "d1a07b"),
    ("80553e", "553d31", "242725", "a57754"),
    ("c3a28a", "856f62", "514337", "ddbaa0"),
)
RED, RED_DARK, RED_LIGHT = "a63830", "652b28", "d05a43"
METAL, DARK = "b0b5a5", "262b27"


def overlay(p, face, box, color):
    x, y, w, h = UV["head"][face]
    x0, y0, x1, y1 = box
    assert 0 <= x0 <= x1 < w and 0 <= y0 <= y1 < h
    p.draw.rectangle((x + 32 + x0, y + y0, x + 32 + x1, y + y1), fill=rgb(color))


def uniform(p, index):
    base, shade, light, earth = COLORS[index]
    skin, skin_dark, _, skin_light = PEOPLE[index]
    for part in ("body", "right_arm", "left_arm", "right_leg", "left_leg"):
        p.all_faces(part, base)
        for face_index, face in enumerate(SIDES):
            w = UV[part][face][2]
            p.rect(part, face, 0, 0, w - 1, 0, light)
            p.rect(part, face, 0, 1, 0, 11, shade)
            # Sparse connected woodland shapes, not single-pixel random noise.
            for row, color in ((2, earth), (5, shade), (8, light)):
                x = (face_index + row + index) % (w - 1)
                p.rect(part, face, x, row, x + 1, row, color)
                p.dot(part, face, x, row + 1, color)
    for arm in ("right_arm", "left_arm"):
        p.side_band(arm, 9 if index == 0 else 5, 11, skin)
        p.rect(arm, "bottom", 0, 0, 3, 3, skin_dark)
        for face in SIDES:
            p.rect(arm, face, 0, 7 if index else 10, 0, 11, skin_dark)
            p.dot(arm, face, 2, 10, skin_light)
        if index == 0:
            p.side_band(arm, 8, 9, shade)
        elif index == 1:
            p.side_band(arm, 4, 5, light)
            p.side_band(arm, 5, 5, earth)
            p.rect(arm, "front", 1, 7, 2, 7, skin_dark)
        else:
            p.side_band(arm, 6, 8, "b4ad90")
            p.side_band(arm, 7, 7, "e0d4b4")
            p.rect(arm, "front", 1, 6, 2, 7, "834238")
            p.dot(arm, "front", 1, 8, "a5614d")
            p.dot(arm, "front", 2, 5, shade)
        # Missing unit badge: faded cloth, dark ripped edge, remaining stitches.
        p.rect(arm, "right", 1, 0, 3, 2, earth)
        p.dot(arm, "right", 1, 0, "b1a084")
        p.dot(arm, "right", 3, 1, shade)
        p.dot(arm, "right", 2, 2, light)
        # Populate both UV sets: runtime mirrors right_arm onto the left arm.
        p.side_band(arm, 3, 4, RED_DARK)
        for face in SIDES:
            p.rect(arm, face, 0, 3, 3, 3, RED)
        p.rect(arm, "front", 1, 3, 2, 3, RED_LIGHT)
        p.dot(arm, "right", 2, 4, RED_LIGHT)
        p.dot(arm, "right", 2, 5, RED)
        p.dot(arm, "back", 1, 5, RED)
    for leg in ("right_leg", "left_leg"):
        p.rect(leg, "front", 1, 5, 3, 7, earth)
        p.rect(leg, "front", 1, 5, 3, 5, light)
        p.dot(leg, "front", 2, 7, "a19170")
        p.rect(leg, "back", 1, 6, 3, 6, shade)
        p.side_band(leg, 9, 11, "39362f")
        p.side_band(leg, 11, 11, "222722")
        p.rect(leg, "bottom", 0, 0, 3, 3, "222722")
        p.rect(leg, "front", 1, 9, 2, 9, "8b8066")
        p.dot(leg, "front", 2, 10, "645d4c")
        p.rect(leg, "back", 0, 10, 2, 10, earth)
    for face in SIDES:
        w = UV["body"][face][2]
        p.rect("body", face, 0, 10, w - 1, 10, DARK)
    p.rect("body", "front", 3, 10, 4, 10, "958b6b")


def face(p, index):
    skin, shade, hair, light = PEOPLE[index]
    p.all_faces("head", skin)
    p.rect("head", "top", 0, 0, 7, 7, hair)
    p.rect("head", "bottom", 0, 0, 7, 7, shade)
    for side in ("right", "left", "back"):
        p.rect("head", side, 0, 0, 7, 3, hair)
        p.rect("head", side, 0, 4, 1, 7, shade)
        p.dot("head", side, 4, 5, light)
    p.rect("head", "front", 0, 0, 7, 1, hair)
    p.rect("head", "front", 0, 6, 7, 7, shade)
    for x in (1, 5):
        p.rect("head", "front", x, 3, x + 1, 3, hair)
        p.dot("head", "front", x + 1, 4, DARK)
        p.dot("head", "front", x + 1, 5, shade)
    p.dot("head", "front", 3, 5, light)
    p.rect("head", "front", 3, 6, 4, 6, "573e34")
    p.dot("head", "front", 1, 7, hair)
    p.dot("head", "front", 6, 7, hair)
    if index == 0:
        p.rect("head", "front", 6, 3, 6, 5, "d3ab8a")
        p.dot("head", "front", 7, 5, "9d5b49")
        overlay(p, "top", (0, 0, 7, 7), "596047")
        overlay(p, "top", (1, 1, 5, 2), "7d8060")
        overlay(p, "top", (5, 3, 6, 5), "30392d")
        for side in SIDES:
            overlay(p, side, (0, 0, 7, 2), "596047")
            overlay(p, side, (0, 2, 7, 2), "30392d")
            overlay(p, side, (1, 0, 5, 0), "919076")
        # Crooked chalk slash and a chipped dent.
        overlay(p, "front", (2, 0, 2, 1), "d3c8a4")
        overlay(p, "front", (3, 1, 3, 2), "d3c8a4")
        overlay(p, "front", (6, 0, 7, 0), "30392d")
        overlay(p, "left", (0, 3, 1, 4), "30392d")
    elif index == 1:
        p.all_faces("head", "242a29")
        for side in SIDES:
            p.rect("head", side, 0, 0, 0, 7, "171d1e")
            p.rect("head", side, 2, 1, 5, 1, "363e3b")
        p.rect("head", "front", 1, 3, 6, 4, skin)
        p.rect("head", "front", 1, 3, 6, 3, shade)
        p.dot("head", "front", 2, 4, "d3bf98")
        p.dot("head", "front", 5, 4, "d3bf98")
        p.dot("head", "front", 3, 4, "131b1b")
        p.dot("head", "front", 6, 4, "131b1b")
        overlay(p, "front", (1, 5, 6, 6), "303735")
        overlay(p, "front", (3, 5, 4, 5), "454b43")
        overlay(p, "back", (3, 0, 3, 6), "41483f")
        overlay(p, "top", (3, 0, 3, 7), "41483f")
    else:
        p.rect("head", "front", 1, 5, 2, 5, "73675f")
        p.rect("head", "front", 5, 5, 6, 5, "73675f")
        p.dot("head", "front", 7, 2, hair)
        for side in SIDES:
            overlay(p, side, (0, 1, 7, 2), "d6caaa")
            overlay(p, side, (0, 2, 7, 2), "aaa58e")
        overlay(p, "front", (0, 3, 1, 3), "d6caaa")
        overlay(p, "front", (5, 1, 6, 2), "843c34")
        overlay(p, "front", (6, 1, 7, 1), "b2644e")
        overlay(p, "left", (0, 1, 2, 2), "945043")
        overlay(p, "back", (5, 3, 6, 4), "d6caaa")
        overlay(p, "back", (6, 5, 6, 5), "aaa58e")


def equipment(p, index):
    base, shade, light, earth = COLORS[index]
    if index == 0:
        for side in ("front", "back"):
            for x in (1, 6):
                p.rect("body", side, x, 0, x, 8, "39392c")
                p.dot("body", side, x, 2, "a79b76")
            p.rect("body", side, 0, 7, 7, 8, "39392c")
        for x, color in ((0, "817451"), (3, "6d714d"), (6, "8d7857")):
            p.rect("body", "front", x, 5, min(x + 1, 7), 8, color)
            p.rect("body", "front", x, 5, min(x + 1, 7), 5, "ada07a")
            p.dot("body", "front", x, 7, shade)
        for arm in ("right_arm", "left_arm"):
            p.dot(arm, "front", 0, 0, "b4ac82")
            p.dot(arm, "front", 1, 1, "b4ac82")
            p.dot(arm, "front", 2, 0, "b4ac82")
            p.dot(arm, "front", 1, 2, "b4ac82")
        p.rect("body", "back", 2, 3, 5, 5, earth)
        p.dot("body", "back", 2, 3, "ac9b77")
    elif index == 1:
        # Brass rounds on a diagonal scavenged leather bandolier.
        for side in ("front", "back"):
            for y in range(10):
                x = min(7, y * 7 // 9)
                if side == "back":
                    x = 7 - x
                p.rect("body", side, max(0, x - 1), y, x, y, "302a24")
                if y % 2 == 0:
                    p.dot("body", side, x, y, "c0a16a")
                    if x > 0:
                        p.dot("body", side, x - 1, y, "84724e")
        p.rect("body", "front", 0, 7, 2, 9, earth)
        p.rect("body", "front", 0, 7, 2, 7, "9e8e68")
        p.dot("body", "front", 1, 8, DARK)
    else:
        p.rect("body", "front", 2, 0, 5, 9, "8b8878")
        p.rect("body", "front", 3, 4, 5, 8, "78796c")
        p.rect("body", "front", 3, 0, 4, 1, PEOPLE[index][0])
        for x in (1, 6):
            p.rect("body", "front", x, 1, x, 9, shade)
            p.dot("body", "front", x, 2, light)
        p.dot("body", "front", 5, 7, "635749")
        p.dot("body", "front", 4, 8, "635749")
        p.rect("body", "back", 2, 4, 5, 7, earth)
        for x, y in ((2, 4), (4, 4), (3, 7), (5, 7)):
            p.dot("body", "back", x, y, "b0a186")
        p.rect("body", "front", 6, 8, 7, 11, "6c5140")
        p.dot("body", "front", 6, 9, "b0a186")
    # Short chain and offset pair of dog tags remain visible on every chest.
    for x, y in ((2, 0), (5, 0), (2, 1), (5, 1), (3, 2), (4, 2)):
        p.dot("body", "front", x, y, "868c7e")
    p.rect("body", "front", 3, 3, 3, 4, METAL)
    p.rect("body", "front", 4, 4, 4, 5, "d1ceba")


def make_skin(index):
    p = Painter()
    uniform(p, index)
    face(p, index)
    equipment(p, index)
    return p.image


def validate_skin(image, name):
    validate(image, name)
    allowed = set()
    for part, faces in UV.items():
        for x, y, w, h in faces.values():
            allowed.update((xx, yy) for yy in range(y, y + h) for xx in range(x, x + w))
            if part == "head":
                allowed.update((xx + 32, yy) for yy in range(y, y + h) for xx in range(x, x + w))
    for y in range(64):
        for x in range(64):
            alpha = image.getpixel((x, y))[3]
            assert alpha in (0, 255), f"{name}: non-pixel-art alpha at {x},{y}"
            assert not alpha or (x, y) in allowed, f"{name}: unused UV pixel at {x},{y}"


def figure(image, view):
    result = Image.new("RGBA", (16, 32))
    result.alpha_composite(face_crop(image, "head", view), (4, 0))
    x, y, _, _ = UV["head"][view]
    result.alpha_composite(image.crop((x + 32, y, x + 40, y + 8)), (4, 0))
    result.alpha_composite(face_crop(image, "body", view), (4, 8))
    for part, row, a, b in (("right_arm", 8, 0, 12), ("right_leg", 20, 4, 8)):
        limb = face_crop(image, part, view)
        if view == "back":
            a, b = b, a
        result.alpha_composite(limb, (a, row))
        result.alpha_composite(limb.transpose(Image.Transpose.FLIP_LEFT_RIGHT), (b, row))
    return result


def preview_sheet(skins):
    sheet = Image.new("RGB", (948, 390), "#171d24")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    draw.text((18, 12), "LAZARUS / DESERTERS", fill="#e5dfcc", font=font)
    draw.text((18, 30), "Abandoned checkpoints. Torn insignia. One red rag.", fill="#aeb7ad", font=font)
    for index, skin in enumerate(skins):
        x = 12 + index * 312
        draw.rectangle((x, 52, x + 300, 374), fill="#242e32", outline="#465047")
        draw.rectangle((x + 1, 53, x + 299, 55), fill="#" + RED)
        draw.text((x + 12, 65), f"0{index + 1} / {NAMES[index]}", fill="#eee7d3", font=font)
        for view, offset in (("front", 16), ("back", 158)):
            rendered = figure(skin, view).resize((128, 256), Image.Resampling.NEAREST)
            sheet.paste(rendered, (x + offset, 86), rendered)
            draw.text((x + offset + 48, 350), view, fill="#b5bbae", font=font)
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    skins = []
    for index in range(3):
        name = f"deserter_{index + 1}.png"
        skin = make_skin(index)
        validate_skin(skin, name)
        assert skin.tobytes() == make_skin(index).tobytes(), f"{name}: nondeterministic"
        skin.save(OUT / name)
        with Image.open(OUT / name) as saved:
            validate_skin(saved, name)
        skins.append(skin)
    assert len({skin.tobytes() for skin in skins}) == 3
    preview_sheet(skins)
    print(f"Generated and validated 3 deterministic skins and {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
