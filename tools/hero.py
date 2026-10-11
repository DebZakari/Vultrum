"""Draws the README's before/after hero and Modrinth's gallery stills from the chart series' screenshots.

    python tools/hero.py [--gallery DIR]

Each scene pairs a screenshot without Vultrum with one with it, taken from the same spot by the runs in
readme_charts.SERIES (of each config's runs, the pair that differs least, so the clouds line up). The hero
is an animated WebP: a divider sweeps across each scene, the left side without Vultrum and the right side
with it, labelled with the scene's mean FPS. With --gallery, also writes a 1920x1080 JPEG still per scene
into DIR, with the divider in the middle.

Headless Edge draws and encodes every frame, so no image library is needed. After the first
frame of a scene, a frame holds only the strip the divider crossed, and this script packs them into one file.
"""
import base64
import glob
import html
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from readme_charts import FONT, ROOT, SCENES, SERIES, change, load, metric  # noqa: E402

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
HERO = ["dark-forest-floor", "mountains", "ground-east"]
GALLERY = ["dark-forest-floor", "mountains", "ground-east", "ground-north", "village"]
RD = 32
HERO_SIZE = (1280, 720)
GALLERY_SIZE = (1920, 1080)
QUALITY = 0.9

PAGE = r"""<!doctype html><html><body>
%IMAGES%
<pre id="out"></pre>
<script>
const C = %CONFIG%;
const FONT = %FONT%;
const img = id => document.getElementById(id);

function scaled(im, W, H) {
  const c = document.createElement('canvas'); c.width = W; c.height = H;
  const g = c.getContext('2d', {alpha: false});
  g.imageSmoothingQuality = 'high';
  g.drawImage(im, 0, 0, W, H);
  return c;
}

// Mean absolute difference over a small copy: picks the off/on pair whose clouds line up best.
function diff(a, b) {
  const pa = scaled(a, 192, 108).getContext('2d').getImageData(0, 0, 192, 108).data;
  const pb = scaled(b, 192, 108).getContext('2d').getImageData(0, 0, 192, 108).data;
  let s = 0;
  for (let i = 0; i < pa.length; i += 4) s += Math.abs(pa[i] - pb[i]) + Math.abs(pa[i + 1] - pb[i + 1]) + Math.abs(pa[i + 2] - pb[i + 2]);
  return s / (pa.length / 4 * 3);
}

function pill(g, x, y, w, h, r, src) {
  g.save();
  g.beginPath(); g.roundRect(x, y, w, h, r); g.clip();
  g.filter = 'blur(' + Math.round(h / 6) + 'px)';
  g.drawImage(src, 0, 0);
  g.filter = 'none';
  g.fillStyle = 'rgba(13, 17, 23, 0.66)';
  g.fillRect(x, y, w, h);
  g.restore();
  g.save();
  g.beginPath(); g.roundRect(x + 0.5, y + 0.5, w - 1, h - 1, r);
  g.strokeStyle = 'rgba(255, 255, 255, 0.10)'; g.lineWidth = 1; g.stroke();
  g.restore();
}

function ember(g, x, y, h) {
  const gr = g.createLinearGradient(0, y - h, 0, y);
  gr.addColorStop(0, '#FFA24A'); gr.addColorStop(1, '#E5452F');
  return gr;
}

// One frame: the off image left of x, the on image right of it, the divider, the labels.
function draw(g, W, H, off, on, x, scene) {
  const s = W / 1280;
  g.drawImage(off, 0, 0);
  g.save(); g.beginPath(); g.rect(x, 0, W - x, H); g.clip(); g.drawImage(on, 0, 0); g.restore();

  g.save();
  g.shadowColor = 'rgba(0, 0, 0, 0.45)'; g.shadowBlur = 6 * s;
  g.fillStyle = 'rgba(255, 255, 255, 0.95)';
  g.fillRect(x - 1.5 * s, 0, 3 * s, H);
  const r = 21 * s, cy = H / 2;
  g.beginPath(); g.arc(x, cy, r, 0, Math.PI * 2); g.fill();
  g.restore();
  g.fillStyle = '#1f2328';
  for (const d of [-1, 1]) {
    g.beginPath();
    g.moveTo(x + d * 4 * s, cy - 6.5 * s); g.lineTo(x + d * 11 * s, cy); g.lineTo(x + d * 4 * s, cy + 6.5 * s);
    g.closePath(); g.fill();
  }

  // The labels are drawn over a frosted copy of the frame so far.
  const src = document.createElement('canvas'); src.width = W; src.height = H;
  src.getContext('2d').drawImage(g.canvas, 0, 0);
  const m = 22 * s, px = 19 * s, py = 14 * s, small = 20 * s, big = 40 * s, gap = 6 * s, rad = 15 * s;
  const h = py * 2 + small + gap + big;
  const label = (side, title, fps, extra) => {
    g.font = '400 ' + small + 'px ' + FONT; const tw = g.measureText(title).width;
    g.font = '700 ' + big + 'px ' + FONT; const fw = g.measureText(fps).width;
    const ew = extra ? g.measureText(extra).width + 12 * s : 0;
    const w = px * 2 + Math.max(tw, fw + ew);
    const x0 = side < 0 ? m : W - m - w;
    pill(g, x0, m, w, h, rad, src);
    g.textBaseline = 'alphabetic';
    g.font = '400 ' + small + 'px ' + FONT; g.fillStyle = 'rgba(230, 237, 243, 0.85)';
    g.fillText(title, x0 + px, m + py + small * 0.82);
    const yb = m + py + small + gap + big * 0.8;
    g.font = '700 ' + big + 'px ' + FONT; g.fillStyle = '#ffffff';
    g.fillText(fps, x0 + px, yb);
    if (extra) { g.fillStyle = ember(g, 0, yb, big * 0.75); g.fillText(extra, x0 + px + fw + 12 * s, yb); }
    return w;
  };
  label(-1, 'Without Vultrum', scene.off + ' FPS');
  label(1, 'With Vultrum', scene.on + ' FPS', scene.change);

  const cap = scene.label + '  \u00b7  ' + C.rd + ' chunks  \u00b7  Complementary Unbound';
  g.font = '400 ' + (17 * s) + 'px ' + FONT;
  const cw = g.measureText(cap).width + 30 * s, ch = 36 * s;
  pill(g, m, H - m - ch, cw, ch, ch / 2, src);
  g.fillStyle = 'rgba(230, 237, 243, 0.9)'; g.textBaseline = 'middle';
  g.fillText(cap, m + 15 * s, H - m - ch / 2 + 1 * s);
}

function encode(c, x, w, type, q) {
  const t = document.createElement('canvas'); t.width = w; t.height = c.height;
  t.getContext('2d', {alpha: false}).drawImage(c, x, 0, w, c.height, 0, 0, w, c.height);
  return t.toDataURL(type, q).split(',')[1];
}

const ease = t => 0.5 - Math.cos(Math.PI * t) / 2;

window.addEventListener('load', () => {
  const out = {pairs: {}, hero: [], gallery: {}};
  const pair = {};
  for (const sc of C.scenes) {
    let best = null;
    for (const o of sc.offIds) for (const n of sc.onIds) {
      const d = diff(img(o), img(n));
      if (!best || d < best.d) best = {o, n, d};
    }
    pair[sc.id] = best;
    out.pairs[sc.id] = {off: best.o, on: best.n, diff: +best.d.toFixed(2)};
  }

  // The hero: per scene, rest in the middle, sweep right, all the way left, back to the middle.
  const [W, H] = C.hero;
  const main = document.createElement('canvas'); main.width = W; main.height = H;
  const g = main.getContext('2d', {alpha: false});
  const fps = 30, ms = 1000 / fps;
  const lo = Math.round(W * 0.30), hi = Math.round(W * 0.70), mid = Math.round(W / 2);
  const reach = Math.ceil(30 * W / 1280);
  for (const id of C.heroScenes) {
    const sc = C.scenes.find(s => s.id === id);
    const off = scaled(img(pair[id].o), W, H), on = scaled(img(pair[id].n), W, H);
    draw(g, W, H, off, on, mid, sc);
    out.hero.push({x: 0, w: W, d: 900, data: encode(main, 0, W, 'image/webp', C.q)});
    let x = mid;
    for (const [from, to, secs, hold] of [[mid, hi, 0.8, 300], [hi, lo, 1.5, 300], [lo, mid, 0.8, 600]]) {
      const n = Math.round(secs * fps);
      for (let i = 1; i <= n; i++) {
        const nx = Math.round(from + (to - from) * ease(i / n));
        draw(g, W, H, off, on, nx, sc);
        let x0 = Math.max(0, Math.min(x, nx) - reach), x1 = Math.min(W, Math.max(x, nx) + reach);
        x0 -= x0 % 2;
        const d = i === n ? hold : Math.round(i * ms) - Math.round((i - 1) * ms);
        out.hero.push({x: x0, w: x1 - x0, d, data: encode(main, x0, x1 - x0, 'image/webp', C.q)});
        x = nx;
      }
    }
  }

  if (C.gallery) {
    const [GW, GH] = C.gallerySize;
    const gc = document.createElement('canvas'); gc.width = GW; gc.height = GH;
    const gg = gc.getContext('2d', {alpha: false});
    for (const id of C.galleryScenes) {
      const sc = C.scenes.find(s => s.id === id);
      draw(gg, GW, GH, scaled(img(pair[id].o), GW, GH), scaled(img(pair[id].n), GW, GH), GW / 2, sc);
      out.gallery[id] = encode(gc, 0, GW, 'image/jpeg', 0.92);
    }
  }
  document.getElementById('out').textContent = JSON.stringify(out);
});
</script></body></html>
"""


def screenshots(pattern, scene):
    dirs = sorted(glob.glob(os.path.join(ROOT, "bench-runs", pattern)))
    return [p for p in (os.path.join(d, "screenshots", f"{scene}-rd{RD}.png") for d in dirs) if os.path.exists(p)]


def file_url(path):
    return "file:///" + path.replace(os.sep, "/").replace(" ", "%20")


def chunk(tag, data):
    return tag + struct.pack("<I", len(data)) + data + (b"\0" if len(data) % 2 else b"")


def chunks(webp):
    """The ALPH and VP8/VP8L chunks of a still WebP, as they go into an animation frame."""
    assert webp[:4] == b"RIFF" and webp[8:12] == b"WEBP"
    out, i = b"", 12
    while i < len(webp):
        tag, size = webp[i:i + 4], struct.unpack("<I", webp[i + 4:i + 8])[0]
        if tag in (b"ALPH", b"VP8 ", b"VP8L"):
            out += webp[i:i + 8 + size + size % 2]
        i += 8 + size + size % 2
    return out


def u24(v):
    return struct.pack("<I", v)[:3]


def animation(frames, w, h):
    body = chunk(b"VP8X", bytes([0x02, 0, 0, 0]) + u24(w - 1) + u24(h - 1))
    body += chunk(b"ANIM", bytes([0, 0, 0, 0]) + struct.pack("<H", 0))
    for f in frames:
        # Blending off (bit 1): a frame replaces the pixels it covers. Disposal none: the rest stay.
        head = u24(f["x"] // 2) + u24(0) + u24(f["w"] - 1) + u24(h - 1) + u24(f["d"]) + bytes([0x02])
        body += chunk(b"ANMF", head + chunks(base64.b64decode(f["data"])))
    return b"RIFF" + struct.pack("<I", 4 + len(body)) + b"WEBP" + body


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    gallery_dir = os.path.abspath(sys.argv[sys.argv.index("--gallery") + 1]) if "--gallery" in sys.argv else None
    still, _ = load()
    fps = metric(still, "fpsAvg")

    images, scenes = [], []
    for scene in dict.fromkeys(HERO + (GALLERY if gallery_dir else [])):
        ids = {}
        for cfg in ("off", "on"):
            paths = screenshots(SERIES["still"][cfg], scene)
            if not paths:
                sys.exit(f"no {cfg} screenshots of {scene}-rd{RD}")
            ids[cfg] = []
            for p in paths:
                ids[cfg].append(f"i{len(images)}")
                images.append((ids[cfg][-1], p))
        k = f"{scene}-rd{RD}"
        scenes.append(dict(id=scene, label=SCENES[scene], offIds=ids["off"], onIds=ids["on"],
                           off=f"{fps['off'][k]:.0f}", on=f"{fps['on'][k]:.0f}", change=change(fps["off"][k], fps["on"][k])))

    config = dict(scenes=scenes, heroScenes=HERO, galleryScenes=GALLERY, gallery=bool(gallery_dir), rd=RD,
                  hero=HERO_SIZE, gallerySize=GALLERY_SIZE, q=QUALITY)
    page = (PAGE.replace("%IMAGES%", "\n".join(f'<img id="{i}" src="{file_url(p)}">' for i, p in images))
            .replace("%CONFIG%", json.dumps(config)).replace("%FONT%", json.dumps(FONT)))
    work = tempfile.mkdtemp(prefix="vhero")
    try:
        page_path = os.path.join(work, "hero.html")
        with open(page_path, "w", encoding="utf-8") as f:
            f.write(page)
        dom = subprocess.run([EDGE, "--headless", "--disable-gpu", "--allow-file-access-from-files",
                              f"--user-data-dir={os.path.join(work, 'profile')}", "--dump-dom", file_url(page_path)],
                             check=True, capture_output=True, timeout=600).stdout.decode("utf-8")
    finally:
        shutil.rmtree(work, ignore_errors=True)
    raw = dom.split('<pre id="out">', 1)[1].split("</pre>", 1)[0]
    if not raw:
        sys.exit("Edge drew nothing")
    out = json.loads(html.unescape(raw))

    names = dict(images)
    for scene, p in out["pairs"].items():
        print(f"{scene}: {os.path.basename(os.path.dirname(os.path.dirname(names[p['off']])))} vs "
              f"{os.path.basename(os.path.dirname(os.path.dirname(names[p['on']])))} (mean difference {p['diff']})")

    hero = os.path.join(ROOT, "docs", "images", "hero.webp")
    with open(hero, "wb") as f:
        f.write(animation(out["hero"], *HERO_SIZE))
    print(f"{hero}: {len(out['hero'])} frames, {os.path.getsize(hero) / 1e6:.2f} MB")

    if gallery_dir:
        os.makedirs(gallery_dir, exist_ok=True)
        for i, (scene, data) in enumerate(out["gallery"].items(), 1):
            path = os.path.join(gallery_dir, f"compare-{i}-{SCENES[scene].lower().replace(' ', '-')}.jpg")
            with open(path, "wb") as f:
                f.write(base64.b64decode(data))
            print(f"{path}: {os.path.getsize(path) / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
