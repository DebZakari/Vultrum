"""Compare two benchmark runs: metric deltas per case plus screenshot diffs.

Usage: python tools/compare.py bench-runs/<baseline> bench-runs/<candidate>
Diff images are written to <candidate>/compare-<baseline-name>/.
"""
import argparse
import json
import math
import os
import shutil
import subprocess
from pathlib import Path

from bench_evidence import Run, assess, get

# (label, path into the run's join record, higher is better)
JOIN_METRICS = [
    ("join level s", ("levelS",), False),
    ("join terrain s", ("terrainS",), False),
    ("join frame ms max", ("frameMs", "max"), False),
    ("join hitches", ("hitches",), False),
    ("join packets ms max", ("packetsMs", "max"), False),
    ("join chunk events ms max", ("chunkEventsMs", "max"), False),
    ("join reclaim ms max", ("reclaimMs", "max"), None),
    ("join reclaim thread ms max", ("reclaimThread", "maxMs"), None),
    ("join forced drains", ("packetBudget", "forcedDrains"), False),
]

# (label, path into a case, higher is better)
METRICS = [
    ("fps avg", ("fpsAvg",), True),
    ("fps 1% low", ("fps1Low",), True),
    ("fps 0.1% low", ("fps01Low",), True),
    ("frame ms p99", ("frameMs", "p99"), False),
    ("frame ms p99.9", ("frameMs", "p999"), False),
    ("frame hitches", ("frameHitches",), False),
    ("hitch frame ms", ("hitchBreakdownMs", "frame"), False),
    ("hitch setup ms", ("hitchBreakdownMs", "terrainSetup"), False),
    ("hitch chunk events ms", ("hitchBreakdownMs", "chunkEvents"), False),
    ("hitch light queue ms", ("hitchBreakdownMs", "lightQueue"), False),
    ("hitch light engine ms", ("hitchBreakdownMs", "lightEngine"), False),
    ("render cpu ms", ("renderCpuMs", "mean"), False),
    ("extract cpu ms", ("extractCpuMs", "mean"), False),
    ("terrain setup cpu ms", ("terrainSetupCpuMs", "mean"), False),
    ("terrain setup p99 ms", ("terrainSetupCpuMs", "p99"), False),
    ("terrain draw cpu ms", ("terrainDrawCpuMs", "mean"), False),
    ("gpu frame ms", ("gpuFrameMs", "mean"), False),
    ("gpu terrain ms", ("gpuTerrainMs", "mean"), False),
    ("vitrail pass span ms", ("vitrail", "spanMs"), False),
    ("vitrail terrain ms", ("vitrail", "terrainMs"), False),
    ("vitrail shadow ms", ("vitrail", "shadowTerrainMs"), False),
    ("packets ms max", ("frameLoopPhasesMs", "packets", "max"), False),
    ("packet drains cut %", ("packetBudget", "cutDrainPct"), None),
    ("packet forced drains", ("packetBudget", "forcedDrains"), False),
    ("packet wait ms max", ("packetBudget", "longestWaitMs"), False),
    ("chunk events ms max", ("terrainSetupPhasesMs", "chunkEvents", "max"), False),
    ("columns queued max", ("chunkColumns", "pending", "max"), None),
    ("reclaim ms max", ("terrainSetupPhasesMs", "reclaim", "max"), None),
    ("reclaimed refs max", ("reclaimedRefs", "max"), None),
    ("reclaim thread ms max", ("reclaimThread", "maxMs"), None),
    ("reclaim thread refs", ("reclaimThread", "refs"), None),
    ("long setups", ("longSetupCount",), False),
    ("load s", ("load", "seconds"), False),
    ("load frame ms max", ("load", "frameMs", "max"), False),
    ("load hitches", ("load", "hitches"), False),
    ("load packets ms max", ("load", "packetsMs", "max"), False),
    ("load chunk events ms max", ("load", "chunkEventsMs", "max"), False),
    ("load reclaim ms max", ("load", "reclaimMs", "max"), None),
    ("load reclaim thread ms max", ("load", "reclaimThread", "maxMs"), None),
    ("load columns queued max", ("load", "columnsPendingMax"), None),
    ("load forced drains", ("load", "packetBudget", "forcedDrains"), False),
    ("visible sections", ("visibleSections",), None),
    ("entities", ("entities",), None),
    ("gpu clock MHz", ("gpu", "clockMhz"), None),
    ("gpu power W", ("gpu", "powerW"), None),
]
def print_metrics(b, c, metrics):
    for label, path, higher_better in metrics:
        bv, cv = get(b, path), get(c, path)
        if bv is None and cv is None:
            continue
        if bv is None or cv is None:
            print(f"  {label:<22} {bv!s:>10} {cv!s:>10}")
            continue
        # From zero (hitch counts while hovering) any change is infinitely large, and still gets a verdict.
        delta = (cv - bv) / bv * 100 if bv else (0.0 if cv == 0 else math.copysign(math.inf, cv))
        verdict = ""
        if higher_better is not None and abs(delta) >= 3:
            verdict = "better" if (delta > 0) == higher_better else "worse"
        print(f"  {label:<22} {bv:>10.3f} {cv:>10.3f} {delta:>+8.1f}%  {verdict}")


def java_executable():
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin" / "java.exe").exists():
        return str(Path(home) / "bin" / "java.exe")
    return shutil.which("java") or "java"


def main(base_dir, cand_dir, *, expected=None, allow_uncapped_unfocused=False, images=True):
    base_dir, cand_dir = Path(base_dir), Path(cand_dir)
    baseline, candidate = Run.read(base_dir), Run.read(cand_dir)
    base, base_cases = baseline.results, baseline.cases
    cand, cand_cases = candidate.results, candidate.cases
    print(f"baseline:  {base_dir.name} ({base['status']})")
    print(f"candidate: {cand_dir.name} ({cand['status']})")
    report = assess(baseline, candidate, expected=expected, allow_uncapped_unfocused=allow_uncapped_unfocused)
    print("timing evidence: " + ("eligible pair; series gate still required" if report.eligible else "exploratory only"))
    for change in report.expected_changes:
        print("EXPECTED " + change)
    for reason in report.exclusions:
        print("EXCLUDED " + reason)
    print("Metric better/worse labels are descriptive +/-3% hints, not keep decisions.")

    if base.get("join") or cand.get("join"):
        print("\njoin")
        # A join loads at the first case's render distance, where the previous run quit: another scenario's spot.
        if base.get("scenario") != cand.get("scenario"):
            print("  WARNING scenarios differ: the joins loaded different places")
        print_metrics(base.get("join") or {}, cand.get("join") or {}, JOIN_METRICS)
        if get(base, ("join", "complete")) is False or get(cand, ("join", "complete")) is False:
            print("  flags: ['joinIncomplete']")

    diff_dir = cand_dir / f"compare-{base_dir.name}"
    if images:
        diff_dir.mkdir(exist_ok=True)
    image_tool = Path(__file__).with_name("ImageDiff.java")

    for case_id, b in base_cases.items():
        c = cand_cases.get(case_id)
        if c is None:
            print(f"\n{case_id}: missing in candidate")
            continue
        print(f"\n{case_id}")
        print_metrics(b, c, METRICS)
        flags = [f for f in ("terrainTimedOut", "focusLost", "unfocused", "moved") if b.get(f) or c.get(f)]
        if get(b, ("load", "complete")) is False or get(c, ("load", "complete")) is False:
            flags.append("loadIncomplete")
        if flags:
            print(f"  flags: {flags}")
        b_png, c_png = base_dir / b.get("screenshot", "missing.png"), cand_dir / c.get("screenshot", "missing.png")
        if images and b_png.exists() and c_png.exists():
            result = subprocess.run([java_executable(), str(image_tool), str(b_png), str(c_png),
                                     str(diff_dir / f"{case_id}.png")], capture_output=True, text=True)
            print(f"  screenshot diff: {result.stdout.strip() or result.stderr.strip()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--expected", type=Path, help="JSON identity paths mapped to exact [control, candidate] values")
    parser.add_argument("--allow-uncapped-unfocused", action="store_true", help="accept matched unfocused runs only with saved uncapped background settings and no Dynamic FPS")
    parser.add_argument("--no-images", action="store_true", help="print evidence and metrics without creating screenshot diffs")
    args = parser.parse_args()
    expected = json.loads(args.expected.read_text(encoding="utf-8-sig")) if args.expected else None
    main(args.baseline, args.candidate, expected=expected,
         allow_uncapped_unfocused=args.allow_uncapped_unfocused, images=not args.no_images)
