"""Benchmark identity, timing eligibility and bracketed-control scoring.

Exploratory comparisons remain useful even when they cannot support a timing
decision. Expected differences are exact (control, candidate) values keyed by
the identity paths reported in Assessment; unknown differences stay excluded.
Metric adapters own experiment-specific measurements, such as reload CSVs.
"""
from dataclasses import dataclass
from datetime import datetime
import json
import math
from pathlib import Path

from gpu_log import gpu_telemetry
from vitrail_log import SHADOW_TERRAIN, TERRAIN, pass_timings


def get(value, path):
    for key in path:
        value = value.get(key) if isinstance(value, dict) else None
    return value


def _json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def _settings(path):
    if not path.exists():
        return None
    values = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        key, sep, value = line.partition("=")
        if sep and not key.lstrip().startswith("#"):
            values[key.strip()] = value.strip()
    return values


@dataclass
class Run:
    path: Path
    results: dict
    meta: dict | None
    settings: dict | None
    sodium_options: dict | None
    options: dict | None

    @classmethod
    def read(cls, path):
        path = Path(path)
        results = _json(path / "results.json")
        meta = _json(path / "meta.json") if (path / "meta.json").exists() else None
        sodium = _json(path / "sodium-options.json") if (path / "sodium-options.json").exists() else None
        run = cls(path, results, meta, _settings(path / "vultrum.properties"),
                  sodium, _options(path / "options.txt"))
        # Preserve adapters for historical formats and optional telemetry.
        for case in results.get("cases", []):
            if "longSetups" in case and "longSetupCount" not in case:
                case["longSetupCount"] = len(case["longSetups"])
        cases = run.cases
        for key, value in pass_timings(path).items():
            if key in cases:
                cases[key]["vitrail"] = {"spanMs": value["spanMs"], "terrainMs": value["labels"].get(TERRAIN),
                                         "shadowTerrainMs": value["labels"].get(SHADOW_TERRAIN)}
        for key, value in gpu_telemetry(path).items():
            if key in cases:
                cases[key]["gpu"] = value
        return run

    @property
    def cases(self):
        return {case["id"]: case for case in self.results.get("cases", [])}

    def identity(self):
        result = self.results
        keys = ("scenario", "device", "framebuffer", "options", "mods", "java", "sodiumBackend", "reclaimThread")
        identity = {key: result.get(key) for key in keys}
        args = result.get("jvmArgs")
        properties, flags = {}, []
        if args is not None:
            for arg in args:
                if arg.startswith("-D"):
                    key, _, value = arg[2:].partition("=")
                    if key in ("vultrum.bench", "vultrum.bench.label", "vultrum.bench.out"):
                        continue
                    if key in properties:
                        # Preserve duplicates instead of trusting last-value interpretation.
                        previous = properties[key]
                        properties[key] = [*previous, value] if isinstance(previous, list) else [previous, value]
                    else:
                        properties[key] = value
                else:
                    flags.append(arg)
        identity.update(properties=properties, jvmFlags=sorted(flags) if args is not None else None,
                        settings=self.settings, sodiumOptions=self.sodium_options, savedOptions=self.options)
        identity["meta"] = {key: (self.meta or {}).get(key) for key in ("git", "mods", "shaders", "full", "compat")}
        identity["cases"] = {key: {field: case.get(field) for field in ("position", "loadedChunks", "visibleSections")}
                             for key, case in self.cases.items()}
        return identity

    def exclusions(self, allow_uncapped_unfocused=False):
        reasons = []
        result, meta = self.results, self.meta or {}
        if result.get("status") != "complete":
            reasons.append("run is not complete")
        if meta.get("dirty") is not False:
            reasons.append("tracked tree dirty or unknown")
        if meta.get("gradleExitCode") != 0:
            reasons.append("launcher exit failed or unknown")
        if meta.get("stats") is not False or meta.get("jfr") is not False:
            reasons.append("Stats/JFR instrumentation enabled or unknown")
        if meta.get("diagnosticOnly") or meta.get("profiler"):
            reasons.append("diagnostic/profiler capture")
        if any(arg.startswith("-XX:StartFlightRecording") for arg in result.get("jvmArgs") or []):
            reasons.append("JFR recording argument")
        if any(arg in ("-Dvultrum.bench.stats=true", "-Dvitrail.shaderDebugInfo=true")
               for arg in result.get("jvmArgs") or []):
            reasons.append("diagnostic shader/stats argument")
        try:
            start, end = _times(self)
            if start > end:
                reasons.append("run timestamps reversed")
        except (KeyError, ValueError, TypeError):
            reasons.append("run timestamps missing/invalid")
        for key in ("scenario", "device", "framebuffer", "options", "mods", "java", "jvmArgs", "sodiumBackend"):
            if result.get(key) is None:
                reasons.append(f"missing identity: {key}")
        for key in ("git", "mods", "shaders", "full", "compat"):
            if key not in meta:
                reasons.append(f"missing identity: meta.{key}")
        for key, value in (("settings", self.settings), ("savedOptions", self.options), ("sodiumOptions", self.sodium_options)):
            if value is None:
                reasons.append(f"missing identity: {key}")
        cases = result.get("cases", [])
        if not cases or len(self.cases) != len(cases):
            reasons.append("missing or duplicate cases")
        if "join" in result and result["join"] is not None and result["join"].get("complete") is not True:
            reasons.append("join incomplete or unknown")
        for case in cases:
            prefix = f"{case['id']}: "
            if get(case, ("load", "complete")) is not True:
                reasons.append(prefix + "load incomplete or unknown")
            for key in ("terrainTimedOut", "moved", "focusLost"):
                if case.get(key) is not False:
                    reasons.append(prefix + f"{key} invalid or unknown")
            for key in ("gpuDropped", "gpuSpanOverflow"):
                if case.get(key) != 0:
                    reasons.append(prefix + f"{key} invalid or unknown")
            if case.get("occlusionCull") is not None and get(case, ("occlusionCull", "broken")) is not False:
                reasons.append(prefix + "cull failed or unknown")
            for key in ("position", "loadedChunks", "visibleSections"):
                if case.get(key) is None:
                    reasons.append(prefix + f"missing identity: {key}")
            if "unfocused" not in case:
                reasons.append(prefix + "focus state unknown")
            elif case["unfocused"] and not (allow_uncapped_unfocused and self._uncapped_background()):
                reasons.append(prefix + "unfocused without verified uncapped-background policy")
        return reasons

    def _uncapped_background(self):
        mods = {**self.results.get("mods", {}), **(self.meta or {}).get("mods", {})}
        return (self.options is not None and self.options.get("inactivityFpsLimit") == '"minimized"'
                and not any("dynamicfps" in name.lower().replace("-", "").replace("_", "") for name in mods))


def _options(path):
    if not path.exists():
        return None
    return {key: value for line in path.read_text(encoding="utf-8-sig").splitlines()
            for key, sep, value in [line.partition(":")] if sep}


def _times(run):
    values = tuple(datetime.fromisoformat(run.meta[key]) for key in ("started", "finished"))
    if any(value.utcoffset() is None for value in values):
        raise ValueError("run timestamps require timezone offsets")
    return values


def _differences(before, after, path=""):
    if isinstance(before, dict) and isinstance(after, dict):
        for key in sorted(before.keys() | after.keys()):
            yield from _differences(before.get(key), after.get(key), f"{path}.{key}" if path else key)
    elif before != after:
        yield path, before, after


@dataclass(frozen=True)
class Assessment:
    exclusions: tuple[str, ...]
    expected_changes: tuple[str, ...]

    @property
    def eligible(self):
        return not self.exclusions


def assess(control, candidate, *, expected=None, allow_uncapped_unfocused=False):
    """Explain timing eligibility; declared differences never waive recording defects."""
    expected = expected or {}
    if any(not isinstance(pair, (list, tuple)) or len(pair) != 2 for pair in expected.values()):
        raise ValueError("expected differences require exact [control, candidate] pairs")
    reasons = [f"{run.path.name}: {reason}" for run in (control, candidate)
               for reason in run.exclusions(allow_uncapped_unfocused)]
    changes = []
    for path, before, after in _differences(control.identity(), candidate.identity()):
        message = f"{path}: {before!r} -> {after!r}"
        if path in expected and list(expected[path]) == [before, after]:
            changes.append(message)
        else:
            reasons.append("unexplained difference: " + message)
    # A changed focus condition needs its own exclusion even when both are uncapped.
    if {key: value.get("unfocused") for key, value in control.cases.items()} != {
            key: value.get("unfocused") for key, value in candidate.cases.items()}:
        reasons.append("recording focus conditions differ")
    return Assessment(tuple(reasons), tuple(changes))


@dataclass(frozen=True)
class Gate:
    minimum_absolute: float
    minimum_percent: float
    required_pairs: int = 3

    def __post_init__(self):
        if (not math.isfinite(self.minimum_absolute) or self.minimum_absolute < 0
                or not math.isfinite(self.minimum_percent) or self.minimum_percent < 0
                or self.required_pairs < 1):
            raise ValueError("gate thresholds must be finite/nonnegative and require at least one pair")


def score_brackets(brackets, metric, *, lower_is_better, gate, expected=None, allow_uncapped_unfocused=False):
    """Score explicit (before control, candidate, after control) run triples.

    metric(run) supplies one finite run-level value. A metric gate is separate
    from visual, lifetime, regression and memory acceptance of an experiment.
    Repeated cases in one run cannot count as independent run pairs.
    """
    rows, exclusions, candidates = [], [], set()
    for bracket in brackets:
        if len(bracket) != 3 or any(run is None for run in bracket):
            exclusions.append("missing bracketing control/candidate")
            continue
        before, candidate, after = bracket
        paths = [run.path.resolve() for run in bracket]
        if len(set(paths)) != 3 or paths[1] in candidates:
            exclusions.append("reused candidate or non-distinct bracketing runs")
            continue
        candidates.add(paths[1])
        try:
            times = [_times(run) for run in bracket]
            if times[0][1] > times[1][0] or times[1][1] > times[2][0]:
                exclusions.append("bracketing runs not chronological or overlap")
        except (KeyError, ValueError, TypeError):
            exclusions.append("bracketing timestamps missing/invalid")
        for control in (before, after):
            report = assess(control, candidate, expected=expected, allow_uncapped_unfocused=allow_uncapped_unfocused)
            exclusions.extend(report.exclusions)
        # Controls must match one another exactly, including experimental state.
        exclusions.extend(assess(before, after, allow_uncapped_unfocused=allow_uncapped_unfocused).exclusions)
        values = [metric(run) for run in bracket]
        if any(not isinstance(value, (int, float)) or not math.isfinite(value) for value in values):
            exclusions.append("metric missing or non-finite")
            continue
        a, b, c = values
        control = (a + c) / 2
        if control <= 0:
            exclusions.append("nonpositive bracketed control metric")
            continue
        improvement = control - b if lower_is_better else b - control
        percent, drift = 100 * improvement / control, abs(a - c)
        rows.append({"runs": [run.path.name for run in bracket], "values": values, "control": control,
                     "delta": b - control, "deltaPct": 100 * (b / control - 1), "controlDrift": drift,
                     "improvement": improvement, "improvementPct": percent,
                     "meetsPairGate": improvement >= gate.minimum_absolute and percent >= gate.minimum_percent
                     and improvement > drift})
    if len(rows) < gate.required_pairs:
        exclusions.append(f"only {len(rows)} independent pairs; {gate.required_pairs} required")
    return {"eligible": not exclusions, "exclusions": list(dict.fromkeys(exclusions)), "pairs": rows,
            "meetsMetricGate": not exclusions and all(row["meetsPairGate"] for row in rows)}
