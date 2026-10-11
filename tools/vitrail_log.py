"""Per-case GPU pass breakdown from Vitrail's pass-timing reports in a run's latest.log.

The shaders run config sets -Dvitrail.passTimings=1, so Vitrail logs one report a second. Reports
stamped inside a case's "[bench] record start/end" markers are averaged, weighted by frame count.
Log stamps have one-second resolution, so a report may reach up to a second into the warmup or
drain; both show the same frozen view, so that's harmless.

Usage: python tools/vitrail_log.py bench-runs/<run>
"""
import re
import sys
from pathlib import Path

STAMP = r"^\[(\d\d):(\d\d):(\d\d)\] "
MARKER = re.compile(STAMP + r".*\[bench\] record (start|end) (\S+)")
HEADER = re.compile(STAMP + r".*\(Vitrail\) Pass timings over [\d.]+ s, (\d+) frames:\s+([\d.]+) ms of passes in a\s+([\d.]+) ms span")
ROW = re.compile(STAMP + r".*\(Vitrail\)\s+([\d.]+) ms\s+[\d.]+%\s+(?:x[\d.]+\s+)?(.+?)\s*$")

# Labels worth naming in comparisons: main-pass and shadow-pass terrain.
TERRAIN = "Vitrail chunk"
SHADOW_TERRAIN = "Vitrail shadow chunk"


def seconds(match):
    h, m, s = (int(match.group(i)) for i in (1, 2, 3))
    return h * 3600 + m * 60 + s


def reports(lines):
    """Yields (time, frames, passes_ms, span_ms, {label: ms}) for each report."""
    current = None
    for line in lines:
        header = HEADER.match(line)
        if header:
            if current:
                yield current
            current = (seconds(header), int(header.group(4)), float(header.group(5)), float(header.group(6)), {})
            continue
        row = ROW.match(line) if current else None
        if row:
            current[4][row.group(5)] = float(row.group(4))
        elif current:
            yield current
            current = None
    if current:
        yield current


def record_windows(lines):
    """{case id: (start, end)} in seconds since midnight, from the "[bench] record start/end" markers."""
    windows, open_cases = {}, {}
    for line in lines:
        marker = MARKER.match(line)
        if marker:
            if marker.group(4) == "start":
                open_cases[marker.group(5)] = seconds(marker)
            elif marker.group(5) in open_cases:
                # A re-recorded case keeps only its last window.
                windows[marker.group(5)] = (open_cases.pop(marker.group(5)), seconds(marker))
    return windows


def pass_timings(run_dir):
    """{case id: {"frames", "passesMs", "spanMs", "labels": {label: ms per frame}}}, empty without Vitrail reports."""
    log = Path(run_dir) / "latest.log"
    if not log.exists():
        return {}
    lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
    all_reports = list(reports(lines))
    result = {}
    for case_id, (start, end) in record_windows(lines).items():
        inside = [r for r in all_reports if start < r[0] <= end]
        frames = sum(r[1] for r in inside)
        if not frames:
            continue
        labels = {}
        for _, n, _, _, rows in inside:
            for label, ms in rows.items():
                labels[label] = labels.get(label, 0.0) + ms * n
        result[case_id] = {
            "frames": frames,
            "passesMs": sum(r[2] * r[1] for r in inside) / frames,
            "spanMs": sum(r[3] * r[1] for r in inside) / frames,
            "labels": {k: v / frames for k, v in sorted(labels.items(), key=lambda kv: -kv[1])},
        }
    return result


def main(run_dir):
    timings = pass_timings(run_dir)
    if not timings:
        sys.exit(f"No Vitrail pass timings inside record windows in {run_dir}/latest.log")
    for case_id, t in timings.items():
        print(f"{case_id}: {t['frames']} frames, {t['passesMs']:.3f} ms of passes in a {t['spanMs']:.3f} ms span")
        for label, ms in list(t["labels"].items())[:8]:
            print(f"  {ms:7.3f} ms  {100 * ms / t['passesMs']:5.1f}%  {label}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
