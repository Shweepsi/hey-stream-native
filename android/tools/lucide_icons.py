#!/usr/bin/env python3
"""Génère app/.../ui/theme/Lucide.kt depuis icon-nodes.json du paquet npm
`lucide-static` (licence ISC).

Usage : tools/lucide_icons.py chemin/vers/icon-nodes.json
Pour ajouter une icône : l'ajouter à ICONS puis relancer.
"""
import json
import sys
from pathlib import Path

ICONS = [
    "arrow-left", "audio-lines", "captions", "check", "chevron-down", "chevron-left",
    "chevron-right", "chevron-up", "circle-alert", "circle-check", "clapperboard", "clock",
    "delete", "ellipsis-vertical", "eye", "eye-off", "fast-forward", "film", "heart", "info",
    "key-round", "languages", "layers", "list", "list-video", "loader-circle", "lock",
    "lock-open", "log-out", "maximize", "minimize", "minus", "monitor-play", "pause", "play",
    "plus", "refresh-cw", "rewind", "rotate-ccw", "rotate-cw", "search", "server", "settings",
    "shield-check", "skip-back", "skip-forward", "star", "trash", "tv", "tv-minimal", "user",
    "volume-2", "wifi-off", "x",
]


def num(v):
    return float(v)


def fmt(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def to_path(tag, a):
    if tag == "path":
        return a["d"]
    if tag == "line":
        return f"M{fmt(num(a['x1']))} {fmt(num(a['y1']))}L{fmt(num(a['x2']))} {fmt(num(a['y2']))}"
    if tag in ("polyline", "polygon"):
        pts = a["points"].replace(",", " ").split()
        pairs = [f"{pts[i]} {pts[i + 1]}" for i in range(0, len(pts), 2)]
        d = "M" + "L".join(pairs)
        return d + ("Z" if tag == "polygon" else "")
    if tag == "circle":
        cx, cy, r = num(a["cx"]), num(a["cy"]), num(a["r"])
        return (f"M{fmt(cx - r)} {fmt(cy)}a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2 * r)} 0"
                f"a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2 * r)} 0")
    if tag == "ellipse":
        cx, cy, rx, ry = num(a["cx"]), num(a["cy"]), num(a["rx"]), num(a["ry"])
        return (f"M{fmt(cx - rx)} {fmt(cy)}a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(2 * rx)} 0"
                f"a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(-2 * rx)} 0")
    if tag == "rect":
        x, y = num(a.get("x", 0)), num(a.get("y", 0))
        w, h = num(a["width"]), num(a["height"])
        rx = num(a.get("rx", a.get("ry", 0)))
        ry = num(a.get("ry", a.get("rx", 0)))
        if rx == 0:
            return f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}Z"
        return (f"M{fmt(x + rx)} {fmt(y)}h{fmt(w - 2 * rx)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(ry)}"
                f"v{fmt(h - 2 * ry)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(ry)}"
                f"h{fmt(-(w - 2 * rx))}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(-ry)}"
                f"v{fmt(-(h - 2 * ry))}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(-ry)}Z")
    raise SystemExit(f"élément SVG non géré : {tag}")


def kotlin_name(name):
    return "".join(part.capitalize() for part in name.split("-"))


def main():
    nodes = json.loads(Path(sys.argv[1]).read_text())
    out = Path(__file__).resolve().parent.parent / "app/src/main/java/io/github/shweepsi/heystream/ui/theme/Lucide.kt"
    lines = [
        "// Généré par tools/lucide_icons.py : ne pas modifier à la main.",
        "// Icônes Lucide (https://lucide.dev), licence ISC.",
        "package io.github.shweepsi.heystream.ui.theme",
        "",
        "import androidx.compose.ui.graphics.vector.ImageVector",
        "",
        "object Lucide {",
    ]
    for name in ICONS:
        paths = ", ".join(json.dumps(to_path(tag, attrs)) for tag, attrs in nodes[name])
        lines.append(f"    val {kotlin_name(name)}: ImageVector by lazy {{ lucideIcon({json.dumps(name)}, {paths}) }}")
    lines.append("}")
    out.write_text("\n".join(lines) + "\n")
    print(f"{len(ICONS)} icônes -> {out}")


if __name__ == "__main__":
    main()
