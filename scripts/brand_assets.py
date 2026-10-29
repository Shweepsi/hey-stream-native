#!/usr/bin/env python3
"""Génère les dessins du logo C (docs/DESIGN.md) pour l'app Android.

Usage : python3 scripts/brand_assets.py  (depuis la racine du dépôt ; demande fontTools)

Symbole : triangle blanc aux coins arrondis, onde intérieure bleu électrique,
onde extérieure rouge vif. Le mot « Hey Stream » de la bannière TV est tracé
depuis Inter Bold (police de l'app).
"""

from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "android/app/src/main/res"
WHITE, BLUE, RED, BG = "#FFF4F4F5", "#FF1F6BFF", "#FFFF2E3B", "#FF121214"

# Boîte du symbole (unités du dessin), traits compris.
X0, Y0, X1, Y1 = 35.5, 28.5, 110.0, 91.5
WORDMARK = "Hey Stream"


def symbol(triangle=WHITE, inner=BLUE, outer=RED, indent="        "):
    return "\n".join(
        indent + line
        for line in [
            f'<path android:pathData="M40,38 L72,60 L40,82 Z" android:fillColor="{triangle}"',
            f'    android:strokeColor="{triangle}" android:strokeWidth="8" android:strokeLineJoin="round" />',
            f'<path android:pathData="M84,43 A24,24 0 0 1 84,77" android:strokeColor="{inner}"',
            '    android:strokeWidth="9" android:strokeLineCap="round" />',
            f'<path android:pathData="M94,33 A38,38 0 0 1 94,87" android:strokeColor="{outer}"',
            '    android:strokeWidth="9" android:strokeLineCap="round" />',
        ]
    )


def vector(comment, width, height, body):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {comment} Généré par scripts/brand_assets.py. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{width}dp" android:height="{height}dp"\n'
        f'    android:viewportWidth="{width}" android:viewportHeight="{height}">\n'
        f"{body}\n"
        "</vector>\n"
    )


def group(scale, tx, ty, body):
    return (
        f'    <group android:scaleX="{scale:.4f}" android:scaleY="{scale:.4f}"\n'
        f'        android:translateX="{tx:.3f}" android:translateY="{ty:.3f}">\n'
        f"{body}\n"
        "    </group>"
    )


def fitted(size, box, fill=True):
    """Symbole centré dans un carré `size`, sur `box` de sa largeur."""
    w, h = X1 - X0, Y1 - Y0
    scale = size * box / max(w, h)
    tx = size / 2 - (X0 + w / 2) * scale
    ty = size / 2 - (Y0 + h / 2) * scale
    return scale, tx, ty


def wordmark_path(font_size):
    font = TTFont(ROOT / "android/app/src/main/res/font/inter_bold.ttf")
    glyphs, cmap = font.getGlyphSet(), font.getBestCmap()
    upm = font["head"].unitsPerEm
    scale = font_size / upm
    tracking = -0.02 * font_size
    pen = SVGPathPen(glyphs)
    x = 0.0
    for ch in WORDMARK:
        name = cmap[ord(ch)]
        glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x, 0)))
        x += glyphs[name].width * scale + tracking
    return pen.getCommands(), x - tracking


def main():
    draw = RES / "drawable"
    w, h = X1 - X0, Y1 - Y0
    (draw / "ic_logo.xml").write_text(
        vector("Logo C : symbole.", round(w, 1), round(h, 1), group(1, -X0, -Y0, symbol()))
    )
    # Icône adaptative : zone sûre de 66 dp sur 108.
    s, tx, ty = fitted(108, 0.56)
    (draw / "ic_launcher_foreground.xml").write_text(
        vector("Icône d'app, logo C.", 108, 108, group(s, tx, ty, symbol()))
    )
    (draw / "ic_launcher_monochrome.xml").write_text(
        vector(
            "Icône thématique (Android 13+), logo C.",
            108,
            108,
            group(s, tx, ty, symbol("#FFFFFFFF", "#FFFFFFFF", "#FFFFFFFF")),
        )
    )
    # Bannière Android TV 320 × 180 : symbole et mot, centrés.
    font_size = 30
    path, text_w = wordmark_path(font_size)
    sym_h = 64
    sym_s = sym_h / h
    sym_w = w * sym_s
    gap = 14
    left = (320 - (sym_w + gap + text_w)) / 2
    banner = "\n".join(
        [
            f'    <path android:pathData="M0,0h320v180h-320z" android:fillColor="{BG}" />',
            group(sym_s, left - X0 * sym_s, 90 - sym_h / 2 - Y0 * sym_s, symbol()),
            f'    <group android:translateX="{left + sym_w + gap:.3f}" android:translateY="{90 + font_size * 0.36:.3f}">',
            f'        <path android:pathData="{path}" android:fillColor="{WHITE}" />',
            "    </group>",
        ]
    )
    (draw / "banner.xml").write_text(vector("Bannière Android TV, logo C.", 320, 180, banner))


if __name__ == "__main__":
    main()
