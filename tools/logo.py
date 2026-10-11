"""Vultrum's logo: an element tile in ember, its letters drawn as strokes so no font is needed.

    python tools/logo.py [--png DIR]

Writes docs/images/logo.svg and the jar's two icons: assets/vultrum/icon.png (256 px, Mod Menu) and
assets/vultrum/textures/gui/config-icon.png (128 px, white, tinted by Sodium's settings screen). With --png,
also writes a 512 px icon into DIR, for Modrinth.
"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from readme_charts import ROOT, render_png

EMBER = ("#FFA24A", "#E5452F")


def big_vu(stroke=11.0):
    """The symbol. V from (26.5, 40) down to (45.5, 84) and back up; u's stems at 75.5 and 101.5, x-height 54."""
    return (f'<path d="M26.5 40 L45.5 84 L64.5 40" fill="none" stroke-width="{stroke}"/>'
            f'<path d="M75.5 54 V71 A13 13 0 0 0 101.5 71 M101.5 54 V84" fill="none" stroke-width="{stroke}"/>')


def number(stroke=3.2):
    """The atomic number, 119, in the top left corner."""
    return (f'<path d="M20 17 L22.5 15 V28 M27.5 17 L30 15 V28" fill="none" stroke-width="{stroke}"/>'
            f'<circle cx="38.6" cy="19.4" r="4.2" fill="none" stroke-width="{stroke}"/>'
            f'<path d="M42.8 19.4 V24 Q42.8 28 38.6 28" fill="none" stroke-width="{stroke}"/>')


def name(stroke=2.0, base=108.0):
    """The element's name, centred under the symbol."""
    cap, xh = base - 9, base - 6.5
    gap = 3.4
    widths = [7, 5.5, 0, 4, 4, 5.5, 9]
    x = 64 - (sum(widths) + gap * (len(widths) - 1)) / 2

    def u(x):
        r = 2.75
        return f'M{x} {xh} V{base - r} A{r} {r} 0 0 0 {x + 2 * r} {base - r} M{x + 2 * r} {xh} V{base}'

    letters = [
        lambda x: f'M{x} {cap} L{x + 3.5} {base} L{x + 7} {cap}',
        u,
        lambda x: f'M{x} {cap - 0.5} V{base}',
        lambda x: f'M{x + 1.3} {cap + 0.5} V{base - 2.5} Q{x + 1.3} {base} {x + 4} {base} M{x} {xh} H{x + 4}',
        lambda x: f'M{x} {xh} V{base} M{x} {xh + 3} Q{x} {xh} {x + 4} {xh}',
        u,
        lambda x: (f'M{x} {base} V{xh} M{x} {xh + 2.5} Q{x} {xh} {x + 2.25} {xh} Q{x + 4.5} {xh} {x + 4.5} {xh + 2.5} V{base} '
                   f'M{x + 4.5} {xh + 2.5} Q{x + 4.5} {xh} {x + 6.75} {xh} Q{x + 9} {xh} {x + 9} {xh + 2.5} V{base}'),
    ]
    parts = []
    for w, letter in zip(widths, letters):
        parts.append(letter(round(x, 2)))
        x += w + gap
    return f'<path d="{" ".join(parts)}" fill="none" stroke-width="{stroke}"/>'


def tile(size=None):
    """The full logo."""
    a, b = EMBER
    dims = f' width="{size}" height="{size}"' if size else ''
    return (f'<svg xmlns="http://www.w3.org/2000/svg"{dims} viewBox="0 0 128 128">'
            f'<defs><linearGradient id="ember" x1="0" y1="0" x2="1" y2="1">'
            f'<stop offset="0" stop-color="{a}"/><stop offset="1" stop-color="{b}"/></linearGradient></defs>'
            f'<rect width="128" height="128" rx="26" fill="url(#ember)"/>'
            f'<g stroke="#FFFFFF" stroke-linecap="round" stroke-linejoin="round">'
            f'{number()}{big_vu()}<g stroke-opacity="0.88">{name()}</g></g></svg>')


def mask_tile(size=None):
    """A white tile with the symbol cut out, for Sodium to tint. Small, so it drops the number and the name."""
    dims = f' width="{size}" height="{size}"' if size else ''
    return (f'<svg xmlns="http://www.w3.org/2000/svg"{dims} viewBox="0 0 128 128">'
            f'<defs><mask id="cut"><rect width="128" height="128" fill="#FFFFFF"/>'
            f'<g stroke="#000000" stroke-linecap="round" stroke-linejoin="round" '
            f'transform="translate(64 64) scale(1.12) translate(-64 -62)">{big_vu(12)}</g>'
            f'</mask></defs><rect x="4" y="4" width="120" height="120" rx="24" fill="#FFFFFF" mask="url(#cut)"/></svg>')


def render(svg, png):
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "icon.svg")
        with open(path, "w", encoding="utf-8") as f:
            f.write(svg)
        render_png(path, png)


if __name__ == "__main__":
    with open(os.path.join(ROOT, "docs", "images", "logo.svg"), "w", encoding="utf-8", newline="\n") as f:
        f.write(tile())
    assets = os.path.join(ROOT, "src", "main", "resources", "assets", "vultrum")
    render(tile(256), os.path.join(assets, "icon.png"))
    render(mask_tile(128), os.path.join(assets, "textures", "gui", "config-icon.png"))
    if "--png" in sys.argv:
        render(tile(512), os.path.join(os.path.abspath(sys.argv[sys.argv.index("--png") + 1]), "icon-512.png"))
