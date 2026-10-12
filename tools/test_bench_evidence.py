"""Timing decisions through the same interface used by comparison and series callers."""
from contextlib import redirect_stdout
import copy
from datetime import datetime, timedelta, timezone
import io
import json
from pathlib import Path
import tempfile
import unittest

from bench_evidence import Gate, Run, assess, score_brackets
import compare


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.count = 0

    def run_file(self, metric=50, candidate=False):
        index = self.count
        self.count += 1
        path = self.root / str(index)
        path.mkdir()
        start = datetime(2026, 10, 13, tzinfo=timezone.utc) + timedelta(minutes=index)
        meta = {"git": "abc123", "dirty": False, "gradleExitCode": 0, "stats": False, "jfr": False,
                "mods": {"shader.jar": "123456"}, "shaders": True, "full": False, "compat": "run-compat-test",
                "started": start.isoformat(), "finished": (start + timedelta(seconds=30)).isoformat()}
        case = {"id": "view-rd32", "position": [0, 80, 0], "loadedChunks": 20, "visibleSections": 10,
                "load": {"complete": True}, "terrainTimedOut": False, "moved": False, "focusLost": False,
                "unfocused": False, "gpuDropped": 0, "gpuSpanOverflow": 0,
                "occlusionCull": {"broken": False}, "fpsAvg": metric}
        result = {"status": "complete", "scenario": {"seed": 1}, "device": {"backend": "Vulkan"},
                  "framebuffer": [1920, 1080], "options": {"vsync": False}, "mods": {"shader": "1"},
                  "java": "25", "sodiumBackend": "indirect", "jvmArgs": ["-Xmx8G", f"-Dvultrum.bench.out={path}"],
                  "cases": [case]}
        if candidate:
            result["jvmArgs"].append("-Dexperiment=true")
        (path / "results.json").write_text(json.dumps(result), encoding="utf-8")
        (path / "meta.json").write_text(json.dumps(meta), encoding="utf-8-sig")
        (path / "vultrum.properties").write_text("# comment\nocclusion-cull=true\n", encoding="utf-8")
        (path / "sodium-options.json").write_text('{"quality": {"filter": "nearest"}}', encoding="utf-8")
        (path / "options.txt").write_text('inactivityFpsLimit:"minimized"\nenableVsync:false\n', encoding="utf-8")
        return Run.read(path)

    def bracket(self, values):
        return tuple(self.run_file(value, candidate=(index == 1)) for index, value in enumerate(values))

    def score(self, brackets, **kwargs):
        return score_brackets(brackets, lambda run: run.results["cases"][0]["fpsAvg"],
                              lower_is_better=True, gate=Gate(5, 20),
                              expected={"properties.experiment": (None, "true")}, **kwargs)

    def test_intended_argument_difference_requires_exact_declaration(self):
        control, candidate = self.run_file(), self.run_file(candidate=True)
        self.assertFalse(assess(control, candidate).eligible)
        report = assess(control, candidate, expected={"properties.experiment": (None, "true")})
        self.assertTrue(report.eligible)
        self.assertEqual(len(report.expected_changes), 1)
        self.assertFalse(assess(control, candidate, expected={"properties.experiment": (None, "false")}).eligible)

    def test_declared_setting_change_does_not_hide_other_fixture_changes(self):
        control, candidate = self.run_file(), self.run_file()
        candidate.settings["occlusion-cull"] = "false"
        expected = {"settings.occlusion-cull": ("true", "false")}
        self.assertTrue(assess(control, candidate, expected=expected).eligible)
        candidate.meta["mods"]["shader.jar"] = "different"
        report = assess(control, candidate, expected=expected)
        self.assertFalse(report.eligible)
        self.assertTrue(any("meta.mods.shader.jar" in reason for reason in report.exclusions))

    def test_identical_missing_historical_metadata_is_excluded(self):
        a, b = self.run_file(), self.run_file()
        a.meta = b.meta = None
        a.settings = b.settings = None
        report = assess(a, b)
        self.assertFalse(report.eligible)
        self.assertTrue(any("missing identity" in reason for reason in report.exclusions))

    def test_instrumentation_cannot_be_declared_away(self):
        a, b = self.run_file(), self.run_file()
        for key in ("stats", "jfr", "diagnosticOnly", "profiler"):
            with self.subTest(key=key):
                changed = copy.deepcopy(b)
                changed.meta[key] = True
                self.assertFalse(assess(a, changed).eligible)
        b.results["jvmArgs"].append("-XX:StartFlightRecording=filename=test.jfr")
        self.assertFalse(assess(a, b).eligible)

    def test_incomplete_and_bad_recordings_are_excluded(self):
        for field in ("terrainTimedOut", "moved", "focusLost", "gpuDropped", "gpuSpanOverflow"):
            with self.subTest(field=field):
                a, b = self.run_file(), self.run_file()
                b.results["cases"][0][field] = True
                self.assertFalse(assess(a, b).eligible)
        a, b = self.run_file(), self.run_file()
        b.results["cases"][0]["load"]["complete"] = False
        self.assertFalse(assess(a, b).eligible)
        b.results["cases"][0]["load"]["complete"] = True
        b.results["cases"][0]["occlusionCull"]["broken"] = True
        self.assertFalse(assess(a, b).eligible)

    def test_unknown_recording_flags_do_not_count_as_false(self):
        a, b = self.run_file(), self.run_file()
        b.results["cases"][0]["moved"] = None
        self.assertFalse(assess(a, b).eligible)

    def test_uncapped_unfocused_requires_verified_files_and_no_cap_mod(self):
        a, b = self.run_file(), self.run_file()
        a.results["cases"][0]["unfocused"] = b.results["cases"][0]["unfocused"] = True
        self.assertFalse(assess(a, b).eligible)
        self.assertTrue(assess(a, b, allow_uncapped_unfocused=True).eligible)
        a.meta["mods"]["Dynamic-FPS.jar"] = b.meta["mods"]["Dynamic-FPS.jar"] = "123"
        self.assertFalse(assess(a, b, allow_uncapped_unfocused=True).eligible)

    def test_changed_focus_condition_remains_excluded(self):
        a, b = self.run_file(), self.run_file()
        b.results["cases"][0]["unfocused"] = True
        self.assertFalse(assess(a, b, allow_uncapped_unfocused=True).eligible)

    def test_case_set_and_pose_changes_are_excluded(self):
        a, b = self.run_file(), self.run_file()
        b.results["cases"][0]["position"] = [1, 80, 0]
        self.assertFalse(assess(a, b).eligible)
        b.results["cases"] = []
        self.assertFalse(assess(a, b).eligible)

    def test_duplicate_case_ids_are_excluded(self):
        a, b = self.run_file(), self.run_file()
        b.results["cases"].append(copy.deepcopy(b.results["cases"][0]))
        self.assertFalse(assess(a, b).eligible)

    def test_saved_sodium_settings_and_jvm_properties_affect_identity(self):
        a, b = self.run_file(), self.run_file()
        b.sodium_options["quality"]["filter"] = "linear"
        b.results["jvmArgs"].append("-Dshader.mode=fast")
        report = assess(a, b)
        self.assertTrue(any("sodiumOptions" in reason for reason in report.exclusions))
        self.assertTrue(any("properties.shader.mode" in reason for reason in report.exclusions))

    def test_three_pairs_meet_absolute_relative_and_control_drift_gate(self):
        result = self.score([self.bracket(values) for values in ((50, 35, 52), (60, 40, 62), (45, 30, 47))])
        self.assertTrue(result["eligible"])
        self.assertTrue(result["meetsMetricGate"])
        self.assertEqual(result["pairs"][0]["control"], 51)
        self.assertEqual(result["pairs"][0]["controlDrift"], 2)

    def test_improvement_within_control_drift_does_not_pass(self):
        result = self.score([self.bracket((40, 35, 60)) for _ in range(3)])
        self.assertTrue(result["eligible"])
        self.assertFalse(result["meetsMetricGate"])

    def test_both_absolute_and_relative_thresholds_are_required(self):
        for values in ((10, 7, 10), (100, 90, 100)):
            with self.subTest(values=values):
                result = self.score([self.bracket(values) for _ in range(3)])
                self.assertFalse(result["meetsMetricGate"])

    def test_one_pair_is_diagnostic_and_cannot_replace_three_pairs(self):
        bracket = self.bracket((50, 30, 50))
        result = self.score([bracket])
        self.assertFalse(result["eligible"])
        self.assertTrue(result["pairs"][0]["meetsPairGate"])
        self.assertFalse(result["meetsMetricGate"])
        self.assertFalse(self.score([bracket] * 3)["meetsMetricGate"])

    def test_missing_controls_and_nonfinite_metrics_are_excluded(self):
        self.assertFalse(self.score([(self.run_file(), self.run_file(), None)])["eligible"])
        result = self.score([self.bracket((50, float("nan"), 50))])
        self.assertTrue(any("non-finite" in reason for reason in result["exclusions"]))

    def test_controls_must_match_even_when_candidate_difference_is_expected(self):
        bracket = self.bracket((50, 30, 50))
        bracket[2].meta["mods"]["shader.jar"] = "different"
        self.assertFalse(self.score([bracket])["eligible"])

    def test_overlapping_or_reversed_runs_are_excluded(self):
        bracket = self.bracket((50, 30, 50))
        bracket[1].meta["started"] = bracket[0].meta["started"]
        result = self.score([bracket])
        self.assertTrue(any("chronological" in reason for reason in result["exclusions"]))

    def test_higher_is_better_and_zero_controls_are_handled(self):
        brackets = [self.bracket((50, 70, 50)) for _ in range(3)]
        result = score_brackets(brackets, lambda run: run.results["cases"][0]["fpsAvg"], lower_is_better=False,
                                gate=Gate(5, 20), expected={"properties.experiment": (None, "true")})
        self.assertTrue(result["meetsMetricGate"])
        self.assertFalse(self.score([self.bracket((0, 0, 0))])["eligible"])

    def test_comparison_keeps_exploratory_metrics_when_timing_is_ineligible(self):
        a, b = self.run_file(50), self.run_file(70)
        (b.path / "meta.json").unlink()
        output = io.StringIO()
        with redirect_stdout(output):
            compare.main(a.path, b.path, images=False)
        self.assertIn("exploratory only", output.getvalue())
        self.assertIn("fps avg", output.getvalue())
        self.assertFalse((b.path / f"compare-{a.path.name}").exists())

    def test_invalid_gate_and_expected_difference_shapes_are_rejected(self):
        with self.assertRaises(ValueError):
            Gate(float("nan"), 20)
        with self.assertRaises(ValueError):
            assess(self.run_file(), self.run_file(), expected={"settings": "any"})


if __name__ == "__main__":
    unittest.main()
