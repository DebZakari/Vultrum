"""The visual check of a run made with a visualCheck scenario: what Vultrum's culls change on screen.

Usage: python tools/visual_check.py bench-runs/<run>
Compares each static case's four shots (culls on, off, on, off) with tools/VisualCheck.java and prints a verdict per
case. Marked images are written to <run>/visual/: on1 dimmed, failing tiles red, noisy tiles blue. Exits 1 if any case
fails or couldn't be checked, or if the culls dropped nothing in any case.
"""
import json
import subprocess
import sys
from pathlib import Path

from compare import java_executable

SHOTS = ("on1", "off1", "on2", "off2")


def main(run_dir):
    run_dir = Path(run_dir)
    run = json.loads((run_dir / "results.json").read_text(encoding="utf-8"))
    out_dir = run_dir / "visual"
    out_dir.mkdir(exist_ok=True)
    tool = Path(__file__).with_name("VisualCheck.java")
    checked = failed = covered_total = 0
    for case in run.get("cases", []):
        shots = case.get("visual")
        if not shots:
            continue
        checked += 1
        region = case.get("shadowRegion") or {}
        covered_total += region.get("covered", 0)
        covered = f"{region.get('covered', 0)}/{region.get('sections', 0)} shadow sections covered"
        missing = [s for s in SHOTS if not shots.get(s) or not (run_dir / shots[s]).exists()]
        unchecked = ("shots missing " + str(missing) if missing
                     else "the camera moved" if case.get("visualMoved")
                     else "terrain was still building" if case.get("visualTerrainTimedOut")
                     else "the shadow culls are off (shadow-face-cull and sunless-shadow, or Vitrail)"
                     if region.get("enabled") is False else None)
        if unchecked:
            failed += 1
            print(f"{case['id']:<28} unchecked: {unchecked}")
            continue
        result = subprocess.run([java_executable(), str(tool), *(str(run_dir / shots[s]) for s in SHOTS),
                                 str(out_dir / f"{case['id']}.png")], capture_output=True, text=True)
        try:
            verdict = json.loads(result.stdout)
        except json.JSONDecodeError:
            failed += 1
            print(f"{case['id']:<28} unchecked: {result.stdout.strip() or result.stderr.strip()}")
            continue
        if verdict.get("verdict") != "pass":
            failed += 1
        flags = [f for f in ("terrainTimedOut", "focusLost", "unfocused") if case.get(f)]
        print(f"{case['id']:<28} {verdict.get('verdict', 'error'):<5} failing {verdict.get('failingTiles')} tiles"
              f" ({verdict.get('failingPct')}%), largest {verdict.get('largestCluster')} at {verdict.get('clusterBox')},"
              f" brighter with culls on: {verdict.get('clusterBrighterOn')}, noisy {verdict.get('noisyPct')}%; {covered}"
              + (f"; flags {flags}" if flags else ""))
    if checked == 0:
        print("No case has visual shots: run a scenario with \"visualCheck\": true.")
        return 1
    print(f"\n{checked - failed} of {checked} cases pass. Marked shots: {out_dir}")
    # A run whose culls dropped nothing anywhere shows the same frame on and off: it checks nothing.
    if covered_total == 0:
        print("No case's shadow culls dropped anything (shadowRegion.covered is 0 everywhere): the check tested nothing."
              " Is a shader pack on, and the face cull on in the run dir's vultrum.properties?")
        return 1
    return 1 if failed else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1]))
