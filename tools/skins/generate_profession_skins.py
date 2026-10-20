"""Generate original, reproducible 64x64 Steve-format profession skins.

Run from the repository root with: python tools/skins/generate_profession_skins.py
Only Pillow is required. All visible clothing is painted onto base UV faces because
the NPC HumanoidModel renders the head overlay but no other overlay layers.
"""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/main/resources/assets/lazarusnpcs/textures/entity/profession"
PREVIEW = ROOT / "docs/profession_skins_preview.png"
PROFESSIONS = ("leader", "guard", "armorer", "trader", "medic", "expedition_soldier")


def rgb(value):
    return tuple(bytes.fromhex(value.lstrip("#"))) + (255,)


PALETTES = {
    "rovers": dict(base="766044", dark="3b342d", mid="9d7950", light="c1a274",
                   accent="b95b30", accent_light="e18a46", pants="53636a", boot="42352d",
                   metal="89908a", shirt="988265", white="e9e1ce", green="4b9958"),
    "wardens": dict(base="344d68", dark="1d2935", mid="587490", light="879bac",
                    accent="426d91", accent_light="789ebc", pants="354b60", boot="222b34",
                    metal="9aa8ae", shirt="607589", white="e8e9e4", green="4b9958"),
}

# Distinct faces are useful even when uniforms share a faction palette.
PEOPLE = {
    "rovers": (
        ("ae7855", "392b25", "513a2d"), ("d1a077", "453025", "5a4030"),
        ("76513e", "242326", "2d2829"), ("e2b791", "674736", "5c3f36"),
        ("966443", "302b28", "3e3029"), ("c28d68", "55412e", "514135")),
    "wardens": (
        ("8f614c", "29282a", "3d302d"), ("c49872", "694d38", "554033"),
        ("d4aa84", "493c32", "62473c"), ("684739", "26262a", "2f292b"),
        ("e4bfa0", "7b6047", "735243"), ("a7775d", "353438", "43342f")),
}

# Each entry is (x, y, width, height). Side ordering follows the standard skin UV.
UV = {
    "head": {"top": (8, 0, 8, 8), "bottom": (16, 0, 8, 8),
             "right": (0, 8, 8, 8), "front": (8, 8, 8, 8),
             "left": (16, 8, 8, 8), "back": (24, 8, 8, 8)},
    "body": {"top": (20, 16, 8, 4), "bottom": (28, 16, 8, 4),
             "right": (16, 20, 4, 12), "front": (20, 20, 8, 12),
             "left": (28, 20, 4, 12), "back": (32, 20, 8, 12)},
    "right_arm": {"top": (44, 16, 4, 4), "bottom": (48, 16, 4, 4),
                  "right": (40, 20, 4, 12), "front": (44, 20, 4, 12),
                  "left": (48, 20, 4, 12), "back": (52, 20, 4, 12)},
    "right_leg": {"top": (4, 16, 4, 4), "bottom": (8, 16, 4, 4),
                  "right": (0, 20, 4, 12), "front": (4, 20, 4, 12),
                  "left": (8, 20, 4, 12), "back": (12, 20, 4, 12)},
    "left_arm": {"top": (36, 48, 4, 4), "bottom": (40, 48, 4, 4),
                 "right": (32, 52, 4, 12), "front": (36, 52, 4, 12),
                 "left": (40, 52, 4, 12), "back": (44, 52, 4, 12)},
    "left_leg": {"top": (20, 48, 4, 4), "bottom": (24, 48, 4, 4),
                 "right": (16, 52, 4, 12), "front": (20, 52, 4, 12),
                 "left": (24, 52, 4, 12), "back": (28, 52, 4, 12)},
}
SIDES = ("right", "front", "left", "back")


class Painter:
    def __init__(self):
        self.image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        self.draw = ImageDraw.Draw(self.image)

    def rect(self, part, face, x0, y0, x1, y1, color):
        ox, oy, width, height = UV[part][face]
        assert 0 <= x0 <= x1 < width and 0 <= y0 <= y1 < height
        self.draw.rectangle((ox + x0, oy + y0, ox + x1, oy + y1), fill=rgb(color))

    def dot(self, part, face, x, y, color):
        self.rect(part, face, x, y, x, y, color)

    def all_faces(self, part, color):
        for face, (_, _, width, height) in UV[part].items():
            self.rect(part, face, 0, 0, width - 1, height - 1, color)

    def side_band(self, part, y0, y1, color):
        for face in SIDES:
            width = UV[part][face][2]
            self.rect(part, face, 0, y0, width - 1, y1, color)


def head(p, skin, hair, facial, faction, profession, index, colors):
    p.all_faces("head", skin)
    # Hair covers crown and frames the face, with a different fringe for each person.
    p.rect("head", "top", 0, 0, 7, 7, hair)
    for side in ("right", "left", "back"):
        p.rect("head", side, 0, 0, 7, 2 + index % 2, hair)
    p.rect("head", "front", 0, 0, 7, 1, hair)
    p.rect("head", "front", 0 if index % 2 else 5, 2, 2 if index % 2 else 7, 2, hair)
    p.dot("head", "front", 2, 3, facial)
    p.dot("head", "front", 5, 3, facial)
    p.dot("head", "front", 2, 4, "262b2b")
    p.dot("head", "front", 5, 4, "262b2b")
    p.dot("head", "front", 3, 5, "8e5b48" if index % 2 else "b88163")
    p.rect("head", "front", 3, 6, 4, 6, facial)
    p.dot("head", "front", 1, 5, facial)
    p.dot("head", "front", 6, 5, facial)
    if profession == "guard" and faction == "rovers":
        p.rect("head", "front", 0, 6, 7, 7, colors["accent"])
        p.rect("head", "front", 2, 6, 5, 6, colors["accent_light"])
    elif profession == "armorer":
        p.dot("head", "front", 1, 6, colors["dark"])
        p.dot("head", "front", 6, 5, colors["dark"])


def hat(p, faction, profession, colors):
    # The only populated overlay is the head overlay. Paint directly in its UV cells.
    overlay = ImageDraw.Draw(p.image)

    def patch(face, x0, y0, x1, y1, color):
        ox, oy, _, _ = UV["head"][face]
        overlay.rectangle((ox + x0 + 32, oy + y0, ox + x1 + 32, oy + y1), fill=rgb(color))

    if profession == "expedition_soldier":
        for face in ("front", "right", "left", "back"):
            patch(face, 0, 1, 7, 2, colors["dark"])
        patch("front", 1, 0, 6, 1, colors["metal"])
        patch("front", 2, 0, 3, 1, colors["dark"])
        patch("front", 4, 0, 5, 1, colors["dark"])
        patch("top", 0, 5, 7, 7, colors["dark"])
    elif faction == "rovers" and profession in ("leader", "trader"):
        for face in ("front", "right", "left", "back"):
            patch(face, 0, 1, 7, 1, colors["accent"])
        patch("front", 5, 2, 6, 3, colors["accent_light"])
    elif faction == "wardens" and profession == "leader":
        patch("front", 1, 0, 6, 1, colors["dark"])
        patch("front", 2, 1, 5, 1, colors["accent_light"])
        patch("top", 1, 5, 6, 7, colors["dark"])


def cloth_base(p, faction, profession, colors, skin):
    top = colors["shirt"] if profession in ("medic", "armorer") else colors["base"]
    pants = colors["pants"]
    for part in ("body", "right_arm", "left_arm"):
        p.all_faces(part, top)
    for part in ("right_leg", "left_leg"):
        p.all_faces(part, pants)
    # Form and seams: all four side faces are filled, including those facing armor.
    for part in ("body", "right_arm", "left_arm"):
        for side in SIDES:
            w = UV[part][side][2]
            p.rect(part, side, 0, 0, w - 1, 0, colors["light"])
            p.rect(part, side, 0, 11, w - 1, 11, colors["dark"])
    for leg in ("right_leg", "left_leg"):
        for side in SIDES:
            p.dot(leg, side, 0, 5, colors["light"])
    if profession in ("armorer", "trader", "medic"):
        for arm in ("right_arm", "left_arm"):
            p.side_band(arm, 5, 7, skin)
            p.side_band(arm, 5, 5, colors["dark"])


def faction_armband(p, faction, colors):
    p.side_band("left_arm", 1, 3, colors["accent"])
    if faction == "rovers":
        p.rect("left_arm", "front", 0, 2, 3, 2, colors["accent_light"])
        p.dot("left_arm", "back", 1, 4, colors["accent"])
    else:
        for face in ("front", "back"):
            p.dot("left_arm", face, 0, 1, colors["white"])
            p.dot("left_arm", face, 3, 1, colors["white"])
            p.rect("left_arm", face, 1, 2, 2, 2, colors["white"])


def boots_and_gloves(p, faction, profession, colors):
    for leg in ("right_leg", "left_leg"):
        p.side_band(leg, 9, 11, colors["boot"])
        p.side_band(leg, 9, 9, colors["mid"] if faction == "rovers" else colors["dark"])
        p.rect(leg, "front", 0, 11, 3, 11, colors["dark"])
    for arm in ("right_arm", "left_arm"):
        p.side_band(arm, 10, 11, colors["dark"])
        if profession in ("guard", "armorer", "medic"):
            p.side_band(arm, 10, 10, colors["mid"])
            for face in ("front", "back"):
                p.dot(arm, face, 1, 11, colors["shirt"])


def belt(p, colors, ornate=False):
    for face in SIDES:
        w = UV["body"][face][2]
        p.rect("body", face, 0, 9, w - 1, 10, colors["dark"])
    p.rect("body", "front", 3, 9, 4, 10, colors["metal"])
    p.dot("body", "front", 3, 9, colors["accent_light"] if ornate else colors["dark"])
    if ornate:
        p.dot("body", "front", 4, 10, colors["accent_light"])


def camo(p, faction, colors):
    blotches = (colors["mid"], colors["dark"], colors["light"])
    for part in ("body", "right_arm", "left_arm", "right_leg", "left_leg"):
        for face in SIDES:
            w = UV[part][face][2]
            for y in range(2, 9, 3):
                x = (y + (0 if face == "front" else 2)) % w
                p.dot(part, face, x, y, blotches[(y + x) % 3])
                p.dot(part, face, (x + 1) % w, y, blotches[(y + x) % 3])


def profession_details(p, faction, profession, colors, skin):
    b, d, m, l = (colors[k] for k in ("base", "dark", "mid", "light"))
    if profession == "leader":
        # Long coat continues down the legs so it remains identifiable under armor.
        for leg in ("right_leg", "left_leg"):
            p.side_band(leg, 0, 4, b)
            for face in ("front", "back"):
                p.rect(leg, face, 0, 0, 0, 4, d)
        for face in ("front", "back"):
            p.rect("body", face, 0, 1, 1, 8, d)
            p.rect("body", face, 6, 1, 7, 8, d)
        if faction == "rovers":
            p.rect("body", "front", 2, 1, 5, 3, colors["accent"])
            p.rect("body", "front", 3, 2, 4, 3, colors["accent_light"])
            for arm in ("right_arm", "left_arm"):
                p.side_band(arm, 7, 8, m)
        else:
            p.rect("body", "front", 3, 1, 4, 5, d)
            p.rect("body", "front", 3, 1, 4, 1, colors["white"])
            p.rect("body", "front", 5, 3, 6, 3, colors["white"])
            for arm in ("right_arm", "left_arm"):
                p.rect(arm, "front", 0, 0, 3, 1, colors["light"])
                p.dot(arm, "front", 1, 1, colors["white"])
        belt(p, colors, ornate=True)

    elif profession == "guard":
        p.rect("body", "front", 1, 1, 2, 7, d)
        p.rect("body", "front", 5, 1, 6, 7, d)
        p.rect("body", "front", 2, 4, 5, 4, m)
        for arm in ("right_arm", "left_arm"):
            p.side_band(arm, 2, 4, colors["metal"] if faction == "rovers" else m)
            p.rect(arm, "front", 0, 2, 3, 2, l)
        if faction == "wardens":
            p.rect("body", "front", 1, 5, 2, 7, d)
            p.rect("body", "front", 5, 5, 6, 7, d)
            p.dot("body", "front", 1, 5, colors["metal"])
            p.dot("body", "front", 5, 5, colors["metal"])
        for leg in ("right_leg", "left_leg"):
            p.rect(leg, "front", 1, 6, 2, 7, m)
        belt(p, colors)

    elif profession == "armorer":
        # Thick leather apron, tied around the back, and soot on sleeves.
        apron = "65442f" if faction == "rovers" else "4b4744"
        p.rect("body", "front", 1, 2, 6, 11, apron)
        p.rect("body", "front", 2, 3, 5, 8, colors["mid"])
        p.rect("body", "front", 2, 6, 2, 9, colors["dark"])
        p.rect("body", "back", 0, 6, 7, 7, apron)
        for leg in ("right_leg", "left_leg"):
            p.rect(leg, "front", 0, 0, 3, 3, apron)
        for arm in ("right_arm", "left_arm"):
            p.dot(arm, "front", 1, 3, d)
            p.dot(arm, "back", 2, 6, d)
            p.side_band(arm, 9, 11, apron)
        belt(p, colors)
        p.rect("body", "front", 1, 9, 2, 11, d)
        p.dot("body", "front", 1, 10, colors["metal"])
        p.rect("body", "front", 5, 9, 6, 11, d)
        p.rect("body", "front", 6, 8, 6, 10, colors["metal"])
        p.dot("body", "front", 4, 7, d)

    elif profession == "trader":
        if faction == "rovers":
            p.rect("body", "front", 1, 1, 6, 2, colors["accent"])
            p.rect("body", "front", 5, 4, 7, 6, "637b69")
            p.rect("body", "front", 0, 6, 2, 8, "c29667")
            p.rect("right_arm", "front", 0, 4, 2, 6, colors["accent_light"])
        else:
            p.rect("body", "front", 1, 1, 6, 8, m)
            p.rect("body", "front", 3, 1, 4, 8, d)
            p.rect("body", "front", 1, 3, 2, 4, d)
            p.rect("body", "front", 5, 3, 6, 4, d)
        for face in ("front", "back"):
            p.rect("body", face, 1, 0, 1, 8, d)
            p.rect("body", face, 6, 0, 6, 8, d)
        p.rect("body", "back", 2, 2, 5, 8, m)
        belt(p, colors)
        p.rect("body", "front", 0, 8, 2, 11, colors["mid"])
        p.rect("body", "front", 5, 8, 7, 11, colors["mid"])
        p.rect("body", "front", 0, 9, 2, 9, d)
        p.rect("body", "front", 5, 9, 7, 9, d)
        p.dot("body", "front", 1, 10, colors["metal"])
        p.dot("body", "front", 5, 10, colors["metal"])
        for arm in ("right_arm", "left_arm"):
            p.rect(arm, "front", 1, 7, 2, 8, m)
            p.dot(arm, "front", 1, 7, colors["metal"])
        p.rect("body", "front", 6, 8, 7, 9, colors["white"])
        p.dot("body", "front", 6, 8, colors["dark"])

    elif profession == "medic":
        p.rect("body", "front", 2, 0, 5, 8, colors["white"])
        p.rect("body", "front", 3, 1, 4, 6, colors["shirt"])
        p.rect("body", "front", 1, 1, 1, 8, d)
        p.rect("body", "front", 2, 6, 3, 6, colors["green"])
        p.rect("body", "front", 2, 5, 2, 7, colors["green"])
        p.rect("body", "back", 6, 1, 6, 8, d)
        p.rect("body", "front", 5, 8, 7, 11, m)
        p.rect("body", "front", 5, 8, 7, 8, d)
        belt(p, colors)
        # White medical armband and green cross on RIGHT arm, separate from faction LEFT.
        p.side_band("right_arm", 1, 4, colors["white"])
        for face in ("front", "back"):
            p.rect("right_arm", face, 1, 2, 2, 2, colors["green"])
            p.dot("right_arm", face, 1, 1, colors["green"])
            p.dot("right_arm", face, 1, 3, colors["green"])

    elif profession == "expedition_soldier":
        camo(p, faction, colors)
        for face in ("front", "back"):
            p.rect("body", face, 1, 0, 1, 8, d)
            p.rect("body", face, 6, 0, 6, 8, d)
            p.rect("body", face, 2, 5, 5, 5, m)
        for leg in ("right_leg", "left_leg"):
            p.rect(leg, "front", 0, 5, 3, 7, d)
            p.rect(leg, "front", 1, 5, 2, 6, colors["metal"])
        for arm in ("right_arm", "left_arm"):
            p.side_band(arm, 6, 7, d)
        belt(p, colors)


def make_skin(faction, profession, index):
    colors = PALETTES[faction]
    skin, hair, facial = PEOPLE[faction][index]
    p = Painter()
    head(p, skin, hair, facial, faction, profession, index, colors)
    cloth_base(p, faction, profession, colors, skin)
    profession_details(p, faction, profession, colors, skin)
    boots_and_gloves(p, faction, profession, colors)
    faction_armband(p, faction, colors)
    hat(p, faction, profession, colors)
    return p.image


def validate(image, name):
    if image.size != (64, 64) or image.mode != "RGBA":
        raise ValueError(f"{name}: expected 64x64 RGBA")
    required = set()
    for part, faces in UV.items():
        for x, y, w, h in faces.values():
            required.update((px, py) for py in range(y, y + h) for px in range(x, x + w))
    hat_space = set((x, y) for y in range(16) for x in range(32, 64))
    for y in range(64):
        for x in range(64):
            alpha = image.getpixel((x, y))[3]
            if (x, y) in required:
                if alpha != 255:
                    raise ValueError(f"{name}: transparent base pixel at {x},{y}")
            elif (x, y) not in hat_space and alpha != 0:
                raise ValueError(f"{name}: painted unused/overlay pixel at {x},{y}")


def face_crop(image, part, face):
    x, y, w, h = UV[part][face]
    return image.crop((x, y, x + w, y + h))


def assembled(image, view):
    figure = Image.new("RGBA", (16, 32), (0, 0, 0, 0))
    head_view = face_crop(image, "head", view)
    figure.alpha_composite(head_view, (4, 0))
    hx, hy, _, _ = UV["head"][view]
    figure.alpha_composite(image.crop((hx + 32, hy, hx + 40, hy + 8)), (4, 0))
    figure.alpha_composite(face_crop(image, "body", view), (4, 8))
    if view == "front":
        limbs = (("right_arm", (0, 8)), ("left_arm", (12, 8)),
                 ("right_leg", (4, 20)), ("left_leg", (8, 20)))
    else:
        limbs = (("left_arm", (0, 8)), ("right_arm", (12, 8)),
                 ("left_leg", (4, 20)), ("right_leg", (8, 20)))
    for part, dest in limbs:
        figure.alpha_composite(face_crop(image, part, view), dest)
    return figure


def preview_sheet(skins):
    cell_w, cell_h = 218, 246
    sheet = Image.new("RGB", (cell_w * 6 + 24, cell_h * 2 + 24), "#171d24")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    for row, faction in enumerate(PALETTES):
        for col, profession in enumerate(PROFESSIONS):
            x, y = 12 + col * cell_w, 12 + row * cell_h
            draw.rectangle((x, y, x + cell_w - 8, y + cell_h - 8), fill="#242e38", outline="#3f4c56")
            label = f"{faction}_{profession}.png"
            draw.text((x + 8, y + 8), label, fill="#f1f0e9", font=font)
            for view, dx in (("front", 8), ("back", 110)):
                figure = assembled(skins[(faction, profession)], view)
                scaled = figure.resize((96, 192), Image.Resampling.NEAREST)
                sheet.paste(scaled, (x + dx, y + 26), scaled)
                draw.text((x + dx + 30, y + 222), view, fill="#aebcc7", font=font)
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    skins = {}
    for faction in PALETTES:
        for index, profession in enumerate(PROFESSIONS):
            name = f"{faction}_{profession}.png"
            image = make_skin(faction, profession, index)
            validate(image, name)
            image.save(OUT / name)
            skins[(faction, profession)] = image
    preview_sheet(skins)
    print(f"Generated and validated {len(skins)} skins and {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
