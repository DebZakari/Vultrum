"""Draws the README's performance charts from a series of bench runs.

    python tools/readme_charts.py [--png DIR]

Reads the runs named in SERIES (each config's runs are averaged per case), writes a light and a dark SVG
per chart to docs/images/, and prints the README's table. With --png, also renders dark PNG copies into
DIR for Modrinth's gallery, through Edge's headless screenshot.
"""
import glob
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "images")

# 0.1.0-alpha.5 with Vitrail 0.13.0-beta.vultrum.1, 2026-10-14: on/off/off/on after a warm-up.
# Static views wait for quiet builds; "without" has every setting off, "with" uses the defaults.
SERIES = {
    "still": {
        "on": ["20261014-053249-review013-s-on-1", "20261014-055150-review013-s-on-4"],
        "off": ["20261014-053910-review013-s-off-2", "20261014-054530-review013-s-off-3"],
    },
    "flight": {
        "on": ["20261014-055811-review013-t-on-1", "20261014-060831-review013-t-on-4"],
        "off": ["20261014-060136-review013-t-off-2", "20261014-060505-review013-t-off-3"],
    },
}

THEMES = {
    "light": dict(card="#ffffff", border="#d0d7de", text="#1f2328", text2="#59636e", grid="#eaeef2",
                  base="#8c959f", accent="#e5502f"),
    "dark": dict(card="#0d1117", border="#30363d", text="#e6edf3", text2="#9198a1", grid="#21262d",
                 base="#6e7681", accent="#f0603a"),
}
FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans', Helvetica, Arial, sans-serif"
LEGEND = ("Without Vultrum", "With Vultrum")


def run_dirs(patterns):
    """Resolve explicit run names or legacy glob patterns for charts and screenshots."""
    if isinstance(patterns, str):
        patterns = [patterns]
    found = set()
    for pattern in patterns:
        matched = glob.glob(os.path.join(ROOT, "bench-runs", pattern))
        if not matched:
            sys.exit(f"no runs match {pattern}")
        found.update(matched)
    return sorted(found)


def runs(patterns):
    return [json.load(open(os.path.join(r, "results.json"), encoding="utf-8")) for r in run_dirs(patterns)]


def mean_by_case(results, metric):
    """{case id: mean of metric(case) over the runs}."""
    sums = {}
    for r in results:
        for c in r["cases"]:
            sums.setdefault(c["id"], []).append(metric(c))
    return {k: sum(v) / len(v) for k, v in sums.items()}


def nice_step(vmax, ticks=5):
    raw = vmax / ticks
    mag = 10 ** math.floor(math.log10(raw))
    for m in (1, 2, 2.5, 5, 10):
        if raw <= m * mag:
            return m * mag
    return 10 * mag


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def bar(x0, y, w, h, fill, r=4):
    """A bar from the baseline at x0, square there and rounded at its data end."""
    if w <= r:
        return f'<rect x="{x0:.1f}" y="{y:.1f}" width="{max(w, 0):.1f}" height="{h}" fill="{fill}"/>'
    xe = x0 + w
    return (f'<path d="M{x0:.1f} {y:.1f} H{xe - r:.1f} A{r} {r} 0 0 1 {xe:.1f} {y + r:.1f} V{y + h - r:.1f} '
            f'A{r} {r} 0 0 1 {xe - r:.1f} {y + h:.1f} H{x0:.1f} Z" fill="{fill}"/>')


def chart(title, subtitle, rows, unit, theme, lower_is_better=False, decimals=0):
    """Grouped horizontal bars: rows of (label, without, with)."""
    t = THEMES[theme]
    W, pad, value_w = 840, 28, 118
    label_w = max(120, round(7.8 * max(len(r[0]) for r in rows) + 18))
    bar_h, gap, row_gap = 14, 2, 22
    top = 120
    row_h = 2 * bar_h + gap
    plot_h = len(rows) * row_h + (len(rows) - 1) * row_gap
    x0, x1 = pad + label_w, W - pad - value_w
    vmax = max(max(b, v) for _, b, v in rows)
    step = nice_step(vmax)
    axis_max = math.ceil(vmax / step) * step
    scale = (x1 - x0) / axis_max
    axis_y = top + plot_h + 14
    H = axis_y + 38

    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" '
           f'font-family="{FONT}">',
           f'<rect x="0.5" y="0.5" width="{W - 1}" height="{H - 1}" rx="10" fill="{t["card"]}" stroke="{t["border"]}"/>',
           f'<text x="{pad}" y="44" font-size="19" font-weight="600" fill="{t["text"]}">{esc(title)}</text>',
           f'<text x="{pad}" y="68" font-size="13.5" fill="{t["text2"]}">{esc(subtitle)}</text>']
    lx = pad
    for name, color in zip(LEGEND, (t["base"], t["accent"])):
        out.append(f'<rect x="{lx}" y="88" width="12" height="12" rx="3" fill="{color}"/>')
        out.append(f'<text x="{lx + 18}" y="99" font-size="13" fill="{t["text"]}">{name}</text>')
        lx += 18 + 7.4 * len(name) + 24

    v = 0
    while v <= axis_max + 1e-9:
        x = x0 + v * scale
        out.append(f'<line x1="{x:.1f}" y1="{top - 8}" x2="{x:.1f}" y2="{axis_y - 6}" stroke="{t["grid"]}" stroke-width="1"/>')
        out.append(f'<text x="{x:.1f}" y="{axis_y + 10}" font-size="12" fill="{t["text2"]}" text-anchor="middle" '
                   f'style="font-variant-numeric: tabular-nums">{v:g}</text>')
        v += step
    out.append(f'<text x="{x1:.1f}" y="{axis_y + 28}" font-size="12" fill="{t["text2"]}" text-anchor="end">{esc(unit)}</text>')

    y = top
    for label, without, with_ in rows:
        out.append(f'<text x="{pad}" y="{y + row_h / 2 + 5:.1f}" font-size="14" fill="{t["text"]}">{esc(label)}</text>')
        for i, (value, color) in enumerate(((without, t["base"]), (with_, t["accent"]))):
            by = y + i * (bar_h + gap)
            w = value * scale
            out.append(bar(x0, by, w, bar_h, color))
            text = f"{value:.{decimals}f}"
            if i == 0:
                out.append(f'<text x="{x0 + w + 6:.1f}" y="{by + 11.5:.1f}" font-size="12" fill="{t["text2"]}">{text}</text>')
            else:
                change = (with_ / without - 1) * 100 if without else 0
                sign = "+" if change >= 0 else "\u2212"
                out.append(f'<text x="{x0 + w + 6:.1f}" y="{by + 11.5:.1f}" font-size="12" fill="{t["text"]}">{text}'
                           f'<tspan dx="6" font-weight="600">{sign}{abs(change):.0f}%</tspan></text>')
        y += row_h + row_gap
    out.append("</svg>")
    return "\n".join(out)


SCENES = {
    "dark-forest-floor": "Dark forest",
    "mountains": "Snowy peaks",
    "ground-east": "Savanna hillside",
    "jungle-floor": "Jungle",
    "ground-north": "Savanna valley",
    "village": "Village",
    "overview": "High in the sky",
}
FLIGHTS = {"jungle-fly": "Jungle", "dark-forest-fly": "Dark forest"}


def load():
    still = {k: runs(v) for k, v in SERIES["still"].items()}
    flight = {k: runs(v) for k, v in SERIES["flight"].items()}
    return still, flight


def metric(results, name):
    return {k: mean_by_case(v, lambda c: c[name]) for k, v in results.items()}


def build_charts():
    still, flight = load()
    fps = metric(still, "fpsAvg")
    low = metric(flight, "fps1Low")
    rows = [(label, fps["off"][f"{scene}-rd32"], fps["on"][f"{scene}-rd32"]) for scene, label in SCENES.items()]
    rows.sort(key=lambda r: r[2] / r[1], reverse=True)
    flights = [(f"{label}, {rd} chunks", low["off"][f"{case}-rd{rd}"], low["on"][f"{case}-rd{rd}"])
               for rd in (32, 16) for case, label in FLIGHTS.items()]
    return {
        "fps": dict(title="Average FPS with shaders at 32 chunks",
                    subtitle="Standing still. Complementary Unbound through Vitrail, 1920×1080, RTX 3090.",
                    rows=rows, unit="FPS, higher is better"),
        "flight": dict(title="1% low FPS while flying into new terrain",
                       subtitle="30 blocks a second, 30 blocks above the ground. Same setup.",
                       rows=flights, unit="1% low FPS, higher is better"),
    }


def change(off, on):
    pct = round((on / off - 1) * 100)
    return "0%" if pct == 0 else f"{pct:+d}%".replace("-", "−")


def print_table():
    still, flight = load()
    fps, low = metric(still, "fpsAvg"), metric(still, "fps1Low")
    print("| Scene | Render distance | FPS without | FPS with | Change | 1% low without | 1% low with |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for rd in (32, 16):
        for scene, label in SCENES.items():
            k = f"{scene}-rd{rd}"
            print(f"| {label} | {rd} | {fps['off'][k]:.0f} | {fps['on'][k]:.0f} | {change(fps['off'][k], fps['on'][k])} "
                  f"| {low['off'][k]:.0f} | {low['on'][k]:.0f} |")
    print()
    fps, low1, low01 = metric(flight, "fpsAvg"), metric(flight, "fps1Low"), metric(flight, "fps01Low")
    worst = {k: mean_by_case(v, lambda c: c["frameMs"]["max"]) for k, v in flight.items()}
    print("| Flight | Render distance | FPS without | FPS with | 1% low without | 1% low with | 0.1% low without "
          "| 0.1% low with | Longest frame without | Longest frame with |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for rd in (32, 16):
        for case, label in FLIGHTS.items():
            k = f"{case}-rd{rd}"
            print(f"| {label} | {rd} | {fps['off'][k]:.0f} | {fps['on'][k]:.0f} | {low1['off'][k]:.0f} | {low1['on'][k]:.0f} "
                  f"| {low01['off'][k]:.0f} | {low01['on'][k]:.0f} | {worst['off'][k]:.1f} ms | {worst['on'][k]:.1f} ms |")


def render_png(svg_path, png_path):
    """Screenshots an SVG at its own size through headless Edge (the only rasterizer on this machine)."""
    edge = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
    head = open(svg_path, encoding="utf-8").read(400)
    w = int(head.split('width="')[1].split('"')[0])
    h = int(head.split('height="')[1].split('"')[0])
    profile = tempfile.mkdtemp(prefix="vedge")
    try:
        subprocess.run([edge, "--headless", "--disable-gpu", "--hide-scrollbars", f"--user-data-dir={profile}",
                        f"--window-size={w},{h}", "--default-background-color=00000000",
                        f"--screenshot={png_path}", "file:///" + svg_path.replace(os.sep, "/")],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        # The launcher returns before the browser has written the file.
        for _ in range(100):
            if os.path.exists(png_path) and os.path.getsize(png_path) > 0:
                time.sleep(0.5)
                break
            time.sleep(0.2)
        else:
            sys.exit(f"Edge wrote no {png_path}")
    finally:
        shutil.rmtree(profile, ignore_errors=True)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    png_dir = sys.argv[sys.argv.index("--png") + 1] if "--png" in sys.argv else None
    os.makedirs(OUT, exist_ok=True)
    charts = build_charts()
    for name, spec in charts.items():
        for theme in THEMES:
            path = os.path.join(OUT, f"{name}-{theme}.svg")
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(chart(theme=theme, **spec))
            if png_dir and theme == "dark":
                render_png(path, os.path.join(os.path.abspath(png_dir), f"{name}.png"))
    print_table()


if __name__ == "__main__":
    main()
