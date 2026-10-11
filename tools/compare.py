"""Compare two benchmark runs: metric deltas per case plus screenshot diffs.

Usage: python tools/compare.py bench-runs/<baseline> bench-runs/<candidate>
Diff images are written to <candidate>/compare-<baseline-name>/.
"""
import json
import math
import os
import shutil
import subprocess
import sys
from pathlib import Path

from gpu_log import gpu_telemetry
from vitrail_log import SHADOW_TERRAIN, TERRAIN, pass_timings

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


def load(run):
    data = json.loads((run / "results.json").read_text(encoding="utf-8"))
    cases = {case["id"]: case for case in data["cases"]}
    # Older probe runs list their long terrain setups (at most 32) without counting them.
    for case in cases.values():
        if "longSetups" in case and "longSetupCount" not in case:
            case["longSetupCount"] = len(case["longSetups"])
    # Shader runs: add Vitrail's per-pass GPU breakdown from the log.
    for case_id, t in pass_timings(run).items():
        if case_id in cases:
            cases[case_id]["vitrail"] = {"spanMs": t["spanMs"], "terrainMs": t["labels"].get(TERRAIN),
                                         "shadowTerrainMs": t["labels"].get(SHADOW_TERRAIN)}
    # Runs since gpu.csv logging: clocks and power, to tell a code change from throttling.
    for case_id, t in gpu_telemetry(run).items():
        if case_id in cases:
            cases[case_id]["gpu"] = t
    return data, cases


def get(case, path):
    value = case
    for key in path:
        value = value.get(key) if isinstance(value, dict) else None
    return value


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


def jvm_flags(run):
    """The game JVM's -X/-XX flags (GC, heap), without system properties or a flight recording."""
    return sorted(a for a in run.get("jvmArgs") or [] if a.startswith("-X") and not a.startswith("-XX:StartFlightRecording"))


def vultrum_settings(run_dir):
    """The run dir's vultrum.properties as each run copied it, or None for older runs."""
    path = run_dir / "vultrum.properties"
    if not path.exists():
        return None
    settings = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, sep, value = line.partition("=")
        if sep and not key.lstrip().startswith("#"):
            settings[key.strip()] = value.strip()
    return settings


def main(base_dir, cand_dir):
    base_dir, cand_dir = Path(base_dir), Path(cand_dir)
    base, base_cases = load(base_dir)
    cand, cand_cases = load(cand_dir)
    print(f"baseline:  {base_dir.name} ({base['status']})")
    print(f"candidate: {cand_dir.name} ({cand['status']})")
    # reclaimThread: early runs turned the thread on with a toggle file, which vultrum.properties doesn't show.
    for key in ("device", "framebuffer", "options", "reclaimThread"):
        if base.get(key) != cand.get(key):
            print(f"WARNING {key} differs: {base.get(key)} vs {cand.get(key)}")
    if jvm_flags(base) != jvm_flags(cand):
        print(f"WARNING JVM flags differ (GC, heap): {jvm_flags(base)} vs {jvm_flags(cand)}")
    # A setting the older run's file doesn't have didn't exist yet: None.
    base_settings, cand_settings = vultrum_settings(base_dir), vultrum_settings(cand_dir)
    if base_settings is not None and cand_settings is not None:
        changed = {key: (base_settings.get(key), cand_settings.get(key)) for key in sorted(base_settings.keys() | cand_settings.keys())
                   if base_settings.get(key) != cand_settings.get(key)}
        if changed:
            print(f"WARNING Vultrum settings differ: {changed}")
    if base.get("mods") != cand.get("mods"):
        print(f"mods: {base.get('mods')} -> {cand.get('mods')}")

    if base.get("join") or cand.get("join"):
        print("\njoin")
        # A join loads at the first case's render distance, where the previous run quit: another scenario's spot.
        if base.get("scenario") != cand.get("scenario"):
            print("  WARNING scenarios differ: the joins loaded different places")
        print_metrics(base.get("join") or {}, cand.get("join") or {}, JOIN_METRICS)
        if get(base, ("join", "complete")) is False or get(cand, ("join", "complete")) is False:
            print("  flags: ['joinIncomplete']")

    diff_dir = cand_dir / f"compare-{base_dir.name}"
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
        b_png, c_png = base_dir / b["screenshot"], cand_dir / c["screenshot"]
        if b_png.exists() and c_png.exists():
            result = subprocess.run([java_executable(), str(image_tool), str(b_png), str(c_png),
                                     str(diff_dir / f"{case_id}.png")], capture_output=True, text=True)
            print(f"  screenshot diff: {result.stdout.strip() or result.stderr.strip()}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
