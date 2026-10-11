"""Per-case GPU clock, temperature, power and throttle state from a run's gpu.csv.

bench.ps1 samples nvidia-smi once a second into gpu.csv. Samples stamped inside a case's
"[bench] record start/end" markers in latest.log are averaged. GPU-bound numbers drift with power
and thermal throttling, so compare runs only when their clocks match.

Usage: python tools/gpu_log.py bench-runs/<run>
"""
import sys
from datetime import datetime
from pathlib import Path

from vitrail_log import record_windows

FIELDS = ("clockMhz", "memClockMhz", "tempC", "powerW", "utilPct")


def samples(run_dir):
    """Yields (seconds since midnight, {field: value}, throttle bits) per gpu.csv row."""
    csv = Path(run_dir) / "gpu.csv"
    if not csv.exists():
        return
    for line in csv.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = [p.strip() for p in line.split(",")]
        if len(parts) != 7:
            continue
        try:
            stamp = datetime.strptime(parts[0], "%Y/%m/%d %H:%M:%S.%f")
            values = dict(zip(FIELDS, (float(p) for p in parts[1:6])))
            throttle = int(parts[6], 16)
        except ValueError:
            continue
        yield stamp.hour * 3600 + stamp.minute * 60 + stamp.second, values, throttle


def gpu_telemetry(run_dir):
    """{case id: {field: mean, "throttle": OR of throttle-reason bits, "samples": n}}, empty without gpu.csv."""
    rows = list(samples(run_dir))
    log = Path(run_dir) / "latest.log"
    if not rows or not log.exists():
        return {}
    result = {}
    for case_id, (start, end) in record_windows(log.read_text(encoding="utf-8", errors="replace").splitlines()).items():
        inside = [r for r in rows if start <= r[0] <= end]
        if not inside:
            continue
        means = {f: sum(r[1][f] for r in inside) / len(inside) for f in FIELDS}
        throttle = 0
        for r in inside:
            throttle |= r[2]
        result[case_id] = {**means, "throttle": throttle, "samples": len(inside)}
    return result


def main(run_dir):
    telemetry = gpu_telemetry(run_dir)
    if not telemetry:
        sys.exit(f"No gpu.csv samples inside record windows in {run_dir}")
    print(f"{'case':<26} {'MHz':>6} {'mem':>6} {'°C':>5} {'W':>6} {'util':>5}  throttle")
    for case_id, t in telemetry.items():
        print(f"{case_id:<26} {t['clockMhz']:>6.0f} {t['memClockMhz']:>6.0f} {t['tempC']:>5.1f} {t['powerW']:>6.1f}"
              f" {t['utilPct']:>5.0f}  {t['throttle']:#x}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
