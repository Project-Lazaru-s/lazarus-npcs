"""Add faction cloth markings to Apocalypse Now survivor textures.

Source textures are read from the Apocalypse Now JAR (default: libs/apocalypsenow-3.0.5.jar, or the
path given as the first argument). The original mod file is never modified; generated resources ship
with Lazarus NPCs.
"""
import sys
from pathlib import Path
from io import BytesIO
from zipfile import ZipFile
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
JAR = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "libs/apocalypsenow-3.0.5.jar"
OUT = ROOT / "src/main/resources/assets/apocalypsenow/textures/entities"
COLORS = {
    1: (87, 129, 91), 2: (87, 129, 91), 3: (87, 129, 91),
    4: (169, 119, 52), 5: (169, 119, 52), 6: (169, 119, 52),
    7: (72, 105, 145), 8: (72, 105, 145),
}


def marked(source: Image.Image, color: tuple[int, int, int]) -> Image.Image:
    image = source.convert("RGBA")
    # Standard 64x64 player-skin UV: jacket front, back, sides and upper arms.
    regions = ((16, 22, 40, 29), (44, 22, 48, 28), (36, 54, 40, 60))
    for left, top, right, bottom in regions:
        for y in range(top, bottom):
            for x in range(left, right):
                r, g, b, a = image.getpixel((x, y))
                if a < 64:
                    continue
                image.putpixel((x, y), (
                    (r * 2 + color[0] * 3) // 5,
                    (g * 2 + color[1] * 3) // 5,
                    (b * 2 + color[2] * 3) // 5,
                    a,
                ))
    return image


OUT.mkdir(parents=True, exist_ok=True)
with ZipFile(JAR) as archive:
    for variant, color in COLORS.items():
        texture = f"so{variant}.png"
        source = Image.open(BytesIO(archive.read(f"assets/apocalypsenow/textures/entities/{texture}")))
        assert source.size == (64, 64), (texture, source.size)
        marked(source, color).save(OUT / texture)
