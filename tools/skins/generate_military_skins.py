"""Original military pixel art. Run here with: python generate_military_skins.py.

Only stdlib and Pillow; reference artwork is never loaded or copied.
Equipment is on base faces except headgear. The preview uses the actual
HumanoidModel mirrored right limbs; both standard left UV regions are filled.
"""

import sys

sys.dont_write_bytecode = True

from PIL import Image, ImageDraw, ImageFont
from generate_profession_skins import OUT, ROOT, SIDES, UV, Painter
from generate_hostile_skins import figure, overlay, validate_skin


PREVIEW = ROOT / "docs/military_skins_preview.png"
FILES = ("military_1", "military_2", "military_3", "military_medic")
NAMES = ("RIFLEMAN", "RADIO OPERATOR", "QUARANTINE TROOPER", "FIELD MEDIC")
OLIVE, SHADE, LIGHT, EARTH = "566d43", "334a35", "82935b", "8e8660"
INK, GOLD, UNIT = "1d2927", "d8bb69", "b5c48b"
TAN, TAN_DARK, TAN_LIGHT = "a39569", "625e43", "c6b88b"
WHITE, RED = "edf0df", "ba393c"
PEOPLE = (
    ("986747", "704a35", "c08c60", "292c26"),
    ("c99c79", "97735a", "e2ba92", "62665a"),
    ("ac805e", "795840", "c69c74", "30372d"),
    ("d6b192", "a38169", "edd0ac", "443c2d"),
)


def uniform(p, index):
    for part in ("body", "right_arm", "left_arm", "right_leg", "left_leg"):
        p.all_faces(part, OLIVE)
        for face_index, face in enumerate(SIDES):
            w = UV[part][face][2]
            p.rect(part, face, 0, 0, w - 1, 0, LIGHT)
            p.rect(part, face, 0, 1, 0, 11, SHADE)
            # Connected, regular woodland shapes keep the matching kit readable.
            for row, color in ((2, EARTH), (5, SHADE), (8, LIGHT)):
                x = (face_index + row) % (w - 1)
                p.rect(part, face, x, row, x + 1, row + 1, color)
                p.dot(part, face, min(w - 1, x + 2), row, color)
        if index == 2:
            # A dark sealed outer suit leaves camo visible on lower sleeves/legs.
            p.side_band(part, 0, 6 if "leg" in part else 8, "354a41")
            for face in SIDES:
                p.rect(part, face, 0, 1, 0, 6, "243830")
                p.rect(part, face, 1, 1, 1, 5, "53665a")
    for arm in ("right_arm", "left_arm"):
        p.side_band(arm, 8, 8, INK if index == 2 else SHADE)
        p.side_band(arm, 9, 11, "c2cfc6" if index == 3 else "303b30")
        p.rect(arm, "bottom", 0, 0, 3, 3, "a5b7ae" if index == 3 else INK)
        for face in SIDES:
            p.rect(arm, face, 1, 9, 2, 10, WHITE if index == 3 else "63705a")
        # Intact shoulder shield: gold chevron on an olive field.
        p.rect(arm, "right", 0, 0, 3, 3, INK)
        p.rect(arm, "right", 1, 0, 2, 2, UNIT)
        p.rect(arm, "right", 0, 1, 3, 1, GOLD)
        p.dot(arm, "right", 1, 2, GOLD)
        p.rect(arm, "front", 0, 1, 2, 2, SHADE)
        p.rect(arm, "front", 0, 1, 2, 1, UNIT)
        p.dot(arm, "front", 1, 2, GOLD)
        # Small subdued flag, distinct from a cloth armband.
        p.rect(arm, "right", 0, 5, 3, 7, TAN_DARK)
        p.rect(arm, "right", 1, 5, 3, 5, UNIT)
        p.rect(arm, "right", 1, 7, 3, 7, UNIT)
        p.dot(arm, "right", 0, 5, GOLD)
    for leg in ("right_leg", "left_leg"):
        p.rect(leg, "front", 0, 5, 3, 7, SHADE)
        p.rect(leg, "front", 1, 5, 2, 6, "768361")
        p.rect(leg, "right", 1, 2, 3, 4, OLIVE)
        p.rect(leg, "right", 1, 2, 3, 2, UNIT)
        p.side_band(leg, 8, 11, "29312a")
        p.side_band(leg, 11, 11, "161e1b")
        p.rect(leg, "bottom", 0, 0, 3, 3, "161e1b")
        p.rect(leg, "front", 1, 8, 2, 8, "7d8974")
        p.rect(leg, "front", 1, 10, 3, 10, "58685b")
        p.dot(leg, "front", 1, 9, "a5afa0")
        p.rect(leg, "back", 1, 9, 2, 10, "424e40")


def carrier(p, index):
    base, dark, light = (TAN, TAN_DARK, TAN_LIGHT)
    if index == 2:
        base, dark, light = "67765a", "2b3c32", "96a27a"
    for face in ("front", "back"):
        p.rect("body", face, 1, 1, 6, 8, dark)
        p.rect("body", face, 2, 2, 5, 7, base)
        for x in (1, 6):
            p.rect("body", face, x, 0, x, 4, base)
            p.dot("body", face, x, 1, light)
        p.rect("body", face, 2, 2, 5, 2, light)
        p.rect("body", face, 2, 4, 5, 4, dark)
        p.rect("body", face, 0, 8, 7, 9, base)
        for x in (0, 3, 6):
            p.rect("body", face, x, 8, x + 1, 8, light)
    for face in ("right", "left"):
        p.rect("body", face, 0, 4, 3, 9, dark)
        p.rect("body", face, 0, 5, 3, 5, base)
        p.rect("body", face, 0, 7, 3, 7, base)
    for x in (0, 3, 6):
        p.rect("body", "front", x, 5, x + 1, 8, dark)
        p.rect("body", "front", x, 5, x + 1, 5, light)
        p.rect("body", "front", x, 6, x + 1, 7, base)
    for face in SIDES:
        w = UV["body"][face][2]
        p.rect("body", face, 0, 10, w - 1, 10, INK)
    p.rect("body", "front", 3, 10, 4, 10, GOLD)
    p.rect("body", "back", 3, 3, 4, 3, UNIT)
    p.rect("body", "top", 1, 0, 1, 3, base)
    p.rect("body", "top", 6, 0, 6, 3, base)


def face(p, index):
    skin, shade, light, hair = PEOPLE[index]
    p.all_faces("head", skin)
    p.rect("head", "bottom", 0, 0, 7, 7, shade)
    p.rect("head", "top", 0, 0, 7, 7, hair)
    for side in ("right", "left", "back"):
        p.rect("head", side, 0, 0, 7, 3, hair)
        p.rect("head", side, 0, 4, 7, 4, shade)
        p.rect("head", side, 3, 4, 4, 5, light)
    p.rect("head", "back", 1, 4, 6, 5, hair)
    p.rect("head", "front", 0, 0, 7, 1, hair)
    for x in (1, 5):
        p.rect("head", "front", x, 3, x + 1, 3, hair)
        p.dot("head", "front", x + 1, 4, INK)
        p.dot("head", "front", x, 5, light)
    p.dot("head", "front", 3, 5, shade)
    p.dot("head", "front", 4, 5, light)
    p.rect("head", "front", 3, 6, 4, 6, shade)
    p.rect("head", "front", 2, 7, 5, 7, light)
    if index == 1:
        p.rect("head", "front", 0, 2, 0, 4, "a7ab96")
        p.rect("head", "front", 7, 2, 7, 4, "a7ab96")
        p.dot("head", "front", 1, 6, shade)
        p.dot("head", "front", 6, 6, shade)
    if index in (0, 3):
        for side in ("right", "left"):
            p.rect("head", side, 1, 3, 1, 6, SHADE)
        p.dot("head", "front", 0, 6, SHADE)
        p.dot("head", "front", 7, 6, SHADE)


def headgear(p, index):
    base = "435c40" if index != 2 else "354a41"
    overlay(p, "top", (0, 0, 7, 7), base)
    overlay(p, "top", (1, 1, 6, 5), OLIVE)
    overlay(p, "top", (2, 2, 5, 3), LIGHT)
    for side in SIDES:
        overlay(p, side, (0, 0, 7, 1 if index == 1 else 2), base)
        overlay(p, side, (1, 0, 6, 0), LIGHT)
        overlay(p, side, (0, 1 if index == 1 else 2, 7, 1 if index == 1 else 2), SHADE)
    if index == 0:
        for side in ("left", "right", "back"):
            overlay(p, side, (0, 1, 7, 1), INK)
        overlay(p, "front", (0, 0, 7, 2), INK)
        for x in (1, 5):
            overlay(p, "front", (x, 0, x + 1, 1), "668e89")
            overlay(p, "front", (x, 0, x + 1, 0), "b0c6ad")
        overlay(p, "front", (3, 1, 4, 1), TAN)
    elif index == 1:
        overlay(p, "front", (0, 2, 7, 2), OLIVE)
        overlay(p, "front", (3, 0, 4, 0), GOLD)
        overlay(p, "top", (5, 0, 5, 7), INK)
        overlay(p, "right", (3, 2, 5, 5), INK)
        overlay(p, "right", (4, 3, 5, 4), "78816a")
        overlay(p, "front", (0, 5, 0, 6), INK)
        overlay(p, "front", (1, 6, 2, 6), INK)
    elif index == 2:
        p.all_faces("head", "354a41")
        for side in SIDES:
            p.rect("head", side, 0, 3, 7, 3, INK)
            p.rect("head", side, 0, 6, 7, 6, INK)
        p.rect("head", "front", 1, 2, 6, 7, "66756a")
        for x in (1, 5):
            overlay(p, "front", (x, 3, x + 1, 4), "101e21")
            overlay(p, "front", (x, 3, x, 3), "5c9191")
        overlay(p, "front", (3, 5, 4, 7), INK)
        overlay(p, "front", (3, 6, 4, 6), "9ca98c")
        overlay(p, "right", (1, 5, 3, 7), INK)
        overlay(p, "right", (2, 5, 2, 7), "859179")
        overlay(p, "back", (3, 2, 4, 7), "53665a")
    else:
        # A five-pixel stepped circle leaves room for a clear three-pixel cross.
        for side in ("front", "right", "left"):
            overlay(p, side, (2, 0, 4, 4), WHITE)
            overlay(p, side, (1, 1, 5, 3), WHITE)
            overlay(p, side, (3, 1, 3, 3), RED)
            overlay(p, side, (2, 2, 4, 2), RED)


def role_equipment(p, index):
    if index == 1:
        p.rect("body", "front", 2, 5, 5, 8, TAN_DARK)
        p.rect("body", "front", 2, 5, 5, 7, "d9d4af")
        p.rect("body", "front", 3, 6, 4, 6, "82957b")
        p.dot("body", "front", 4, 7, SHADE)
        p.rect("body", "back", 1, 3, 6, 9, INK)
        p.rect("body", "back", 2, 4, 5, 8, "52644a")
        p.rect("body", "back", 2, 4, 5, 4, UNIT)
        p.rect("body", "back", 5, 0, 5, 3, INK)
        p.dot("body", "back", 5, 0, "acb5a1")
        for y in (6, 8):
            p.rect("body", "back", 3, y, 5, y, INK)
        p.dot("body", "back", 2, 5, GOLD)
        p.rect("body", "front", 1, 2, 1, 4, INK)
    elif index == 2:
        p.rect("body", "front", 3, 0, 4, 1, INK)
        p.rect("body", "front", 2, 3, 5, 3, UNIT)
        p.rect("body", "back", 2, 5, 5, 8, INK)
        p.rect("body", "back", 3, 5, 4, 8, "7b8c73")
        p.rect("body", "back", 3, 6, 4, 6, GOLD)
    elif index == 3:
        for arm in ("right_arm", "left_arm"):
            p.side_band(arm, 4, 7, WHITE)
            for side in ("front", "right", "back"):
                p.rect(arm, side, 1, 4, 1, 6, RED)
                p.rect(arm, side, 0, 5, 2, 5, RED)
        for side in ("front", "back"):
            for y in range(10):
                x = y * 6 // 9
                if side == "back":
                    x = 6 - x
                p.rect("body", side, x, y, x + 1, y, TAN_DARK)
                p.dot("body", side, x, y, TAN_LIGHT)
        p.rect("body", "front", 5, 7, 7, 9, WHITE)
        p.rect("body", "front", 6, 7, 6, 9, RED)
        p.rect("body", "front", 5, 8, 7, 8, RED)
        p.rect("body", "back", 1, 5, 6, 9, TAN_DARK)
        p.rect("body", "back", 2, 5, 5, 8, TAN_LIGHT)
        p.rect("body", "back", 2, 6, 4, 8, WHITE)
        p.rect("body", "back", 3, 6, 3, 8, RED)
        p.rect("body", "back", 2, 7, 4, 7, RED)


def make_skin(index):
    p = Painter()
    uniform(p, index)
    carrier(p, index)
    face(p, index)
    headgear(p, index)
    role_equipment(p, index)
    return p.image


def preview_sheet(skins):
    sheet = Image.new("RGB", (1260, 410), "#171d24")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    draw.text((18, 12), "LAZARUS / MILITARY", fill="#" + WHITE, font=font)
    draw.text((18, 30), "Hold the checkpoint. Keep formation. Bring everyone home.",
              fill="#" + UNIT, font=font)
    for index, skin in enumerate(skins):
        x = 12 + index * 312
        draw.rectangle((x, 52, x + 300, 394), fill="#242e32", outline="#465747")
        draw.rectangle((x + 1, 53, x + 299, 55), fill="#" + UNIT)
        draw.text((x + 12, 65), f"0{index + 1} / {NAMES[index]}", fill="#" + WHITE, font=font)
        for view, offset in (("front", 16), ("back", 158)):
            rendered = figure(skin, view).resize((128, 256), Image.Resampling.NEAREST)
            sheet.paste(rendered, (x + offset, 86), rendered)
            draw.text((x + offset + 48, 350), view, fill="#" + UNIT, font=font)
        draw.text((x + 12, 377), FILES[index] + ".png", fill="#aeb7ad", font=font)
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    skins = [make_skin(index) for index in range(4)]
    assert len({skin.tobytes() for skin in skins}) == 4, "skins must be distinct"
    for index, skin in enumerate(skins):
        name = FILES[index] + ".png"
        validate_skin(skin, name)
        assert skin.tobytes() == make_skin(index).tobytes(), f"{name}: nondeterministic"
        skin.save(OUT / name)
        with Image.open(OUT / name) as saved:
            validate_skin(saved, name)
            assert saved.tobytes() == skin.tobytes(), f"{name}: save mismatch"
    preview_sheet(skins)
    print(f"Generated and validated 4 deterministic military skins and {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
