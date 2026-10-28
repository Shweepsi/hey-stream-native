#!/usr/bin/env python3
"""Polices et logo de l'app (docs/DESIGN.md).

- Inter (licence OFL) réduite au latin, fonctionnalités OpenType gardées
  (chiffres tabulaires) : res/font/inter_*.ttf.
- Logo A (triangle lecture + deux ondes) : symbole, icône adaptative,
  bannière Android TV (mot « hey stream » vectorisé depuis Inter Bold).

Usage : tools/brand_assets.py dossier/Inter-4.1 (archive officielle de rsms/inter)
Dépendance : pip install fonttools
"""
import sys
from pathlib import Path

from fontTools import subset
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
BLUE, RED, BACKGROUND = "#FF1F6BFF", "#FFFF2E3B", "#FF121214"

UNICODES = "U+0000-024F,U+0300-036F,U+1E00-1EFF,U+2000-206F,U+20A0-20CF,U+2100-214F,U+2190-21FF,U+2212,U+2215,U+2219"
WEIGHTS = {"regular": "Regular", "medium": "Medium", "semibold": "SemiBold", "bold": "Bold"}

# Symbole dans un repère 120 × 120 (maquette « Logo et palette »).
TRIANGLE = "M38,34 L74,60 L38,86 Z"
WAVE_IN = "M84,40 A28,28 0 0 1 84,80"
WAVE_OUT = "M96,28 A44,44 0 0 1 96,92"
STROKE = 7
# Boîte englobante du symbole, trait compris.
BOX_X, BOX_Y, BOX_W, BOX_H = 34.5, 24.5, 79.5, 71.0


def fonts(inter: Path):
    for name, style in WEIGHTS.items():
        options = subset.Options()
        options.layout_features = ["*"]
        options.name_IDs = ["*"]
        options.notdef_outline = True
        # Écrans haute densité : l'optimisation TrueType ne sert pas.
        options.hinting = False
        font = TTFont(inter / "extras/ttf" / f"Inter-{style}.ttf")
        subsetter = subset.Subsetter(options)
        subsetter.populate(unicodes=subset.parse_unicodes(UNICODES))
        subsetter.subset(font)
        out = RES / "font" / f"inter_{name}.ttf"
        font.save(out)
        print(out.name, out.stat().st_size)


def symbol(monochrome=None):
    """Groupe du symbole, dans le repère 120 × 120."""
    if monochrome:
        fill = f'android:fillColor="{monochrome}"'
        gradient = ""
        blue = red = monochrome
    else:
        fill = ""
        gradient = f"""
            <aapt:attr name="android:fillColor">
                <gradient android:type="linear" android:startX="38" android:startY="34"
                    android:endX="74" android:endY="86">
                    <item android:offset="0" android:color="{BLUE}" />
                    <item android:offset="1" android:color="{RED}" />
                </gradient>
            </aapt:attr>"""
        blue, red = BLUE, RED
    return f"""
        <path android:pathData="{TRIANGLE}" {fill}>{gradient}
        </path>
        <path android:pathData="{WAVE_IN}" android:strokeColor="{blue}"
            android:strokeWidth="{STROKE}" android:strokeLineCap="round" />
        <path android:pathData="{WAVE_OUT}" android:strokeColor="{red}"
            android:strokeWidth="{STROKE}" android:strokeLineCap="round" />"""


def vector(name, width, height, vw, vh, body, comment):
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} Généré par tools/brand_assets.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="{width}dp" android:height="{height}dp"
    android:viewportWidth="{vw}" android:viewportHeight="{vh}">{body}
</vector>
"""
    (RES / "drawable" / f"{name}.xml").write_text(xml)


def group(scale, tx, ty, inner):
    return f"""
    <group android:scaleX="{scale:.4f}" android:scaleY="{scale:.4f}"
        android:translateX="{tx:.3f}" android:translateY="{ty:.3f}">{inner}
    </group>"""


def centered(size, target, monochrome=None):
    scale = target / max(BOX_W, BOX_H)
    tx = size / 2 - (BOX_X + BOX_W / 2) * scale
    ty = size / 2 - (BOX_Y + BOX_H / 2) * scale
    return group(scale, tx, ty, symbol(monochrome))


def wordmark(inter: Path, size, letter_spacing=-0.02):
    """Chemin SVG de « hey stream » en Inter Bold, ligne de base à y = 0."""
    font = TTFont(inter / "extras/ttf/Inter-Bold.ttf")
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    upm = font["head"].unitsPerEm
    scale = size / upm
    pen = SVGPathPen(glyphs)
    x = 0.0
    for char in "hey stream":
        name = cmap[ord(char)]
        glyph = glyphs[name]
        glyph.draw(TransformPen(pen, (scale, 0, 0, -scale, x, 0)))
        x += glyph.width * scale + letter_spacing * size
    x -= letter_spacing * size
    cap = font["OS/2"].sCapHeight * scale
    return pen.getCommands(), x, cap


def main():
    inter = Path(sys.argv[1])
    (RES / "font").mkdir(exist_ok=True)
    (RES / "drawable").mkdir(exist_ok=True)
    fonts(inter)

    # Symbole seul, au plus juste (accueil, connexion).
    vector("ic_logo", BOX_W, BOX_H, BOX_W, BOX_H,
           group(1, -BOX_X, -BOX_Y, symbol()), "Logo A : symbole.")
    # Icône adaptative : zone sûre de 66 dp sur 108.
    vector("ic_launcher_foreground", 108, 108, 108, 108, centered(108, 58), "Icône d'app.")
    vector("ic_launcher_monochrome", 108, 108, 108, 108, centered(108, 58, "#FFFFFFFF"),
           "Icône thématique (Android 13+).")

    # Bannière Android TV : 320 × 180 dp, fond sombre, symbole + mot.
    text, text_w, cap = wordmark(inter, 36)
    sym_h = 60
    sym_scale = sym_h / BOX_H
    sym_w = BOX_W * sym_scale
    gap = 14
    total = sym_w + gap + text_w
    left = (320 - total) / 2
    body = f"""
    <path android:pathData="M0,0h320v180h-320z" android:fillColor="{BACKGROUND}" />""" + group(
        sym_scale, left - BOX_X * sym_scale, 90 - (BOX_Y + BOX_H / 2) * sym_scale, symbol()
    ) + f"""
    <group android:translateX="{left + sym_w + gap:.3f}" android:translateY="{90 + cap / 2:.3f}">
        <path android:pathData="{text}" android:fillColor="#FFF4F4F5" />
    </group>"""
    vector("banner", 320, 180, 320, 180, body, "Bannière Android TV.")
    print(f"bannière : symbole {sym_w:.0f} + mot {text_w:.0f} = {total:.0f} dp sur 320")


if __name__ == "__main__":
    main()
