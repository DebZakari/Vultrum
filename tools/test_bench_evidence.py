"""Timing decisions through the same interface used by comparison and series callers."""
from contextlib import redirect_stdout
import copy
from datetime import datetime, timedelta, timezone
import io
import json
from pathlib import Path
import shutil
import subprocess
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
        fixture = {"vitrail": {"pack.txt": "pack-config-hash", "options.txt": None,
                              "amortise-shadow": None, "graphics-api.txt": None,
                              "box-shadow-cull": None, "soft-shadow-compare": None},
                   "shaderpacks": {"pack.zip": "pack-hash", "pack.zip.txt": "settings-hash"},
                   "irisProperties": None}
        meta["shaderFixture"] = {"version": 1, "before": fixture, "after": copy.deepcopy(fixture)}
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

    def test_tag_correctness_checks_are_excluded_even_when_both_runs_enable_them(self):
        for value in ("true", "off"):
            a, b = self.run_file(), self.run_file()
            for run in (a, b):
                run.results["jvmArgs"].append("-Dvultrum.bench.tagMembershipCheck=" + value)
            report = assess(a, b)
            self.assertFalse(report.eligible)
            self.assertTrue(any("correctness checks" in reason for reason in report.exclusions))
        a, b = self.run_file(), self.run_file()
        for run in (a, b):
            run.results["jvmArgs"].append("-Dvultrum.bench.tagMembershipCheck=false")
        self.assertTrue(assess(a, b).eligible)

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
        # Diagnostic arguments exclude both runs even when they match.
        for arg in ("-Dvultrum.bench.stats=true", "-Dvultrum.bench.timeline=true", "-Dvultrum.bench.timeline=TRUE",
                    "-Dvultrum.bench.timeline=chunks", "-Dvitrail.shaderDebugInfo=true"):
            with self.subTest(arg=arg):
                c, d = self.run_file(), self.run_file()
                for run in (c, d):
                    run.results["jvmArgs"].append(arg)
                self.assertTrue(any("diagnostic" in reason for reason in assess(c, d).exclusions))
        # A timeline turned off is no diagnostic.
        c, d = self.run_file(), self.run_file()
        for run in (c, d):
            run.results["jvmArgs"].append("-Dvultrum.bench.timeline=false")
        self.assertTrue(assess(c, d).eligible)
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

    def test_static_views_recorded_while_building_are_excluded(self):
        a, b = self.run_file(), self.run_file()
        b.results["cases"][0]["builds"] = {"record": 0}
        self.assertTrue(assess(a, b).eligible)
        b.results["cases"][0]["builds"] = {"record": 12}
        self.assertTrue(any("section builds while recording" in reason for reason in assess(a, b).exclusions))
        # A sweep or a flight builds as it goes.
        for run in (a, b):
            run.results["scenario"]["viewpoints"] = [{"name": "view", "fly": {"speed": 10}}]
        self.assertTrue(assess(a, b).eligible)

    def test_scenario_fields_added_later_read_as_their_default(self):
        a, b = self.run_file(), self.run_file()
        b.results["scenario"]["quietBuildsSeconds"] = 0.0
        self.assertTrue(assess(a, b).eligible)
        b.results["scenario"]["quietBuildsSeconds"] = 3.0
        self.assertFalse(assess(a, b).eligible)

    def test_chunks_in_range_identify_a_case_that_records_them(self):
        # Chunks a long teleport left out of range change the loaded count, not the chunks in range.
        a, b = self.run_file(), self.run_file()
        for run, loaded in ((a, 20), (b, 24)):
            run.results["cases"][0].update(loadedChunks=loaded, chunksInRange=16)
        self.assertTrue(assess(a, b).eligible)
        b.results["cases"][0]["chunksInRange"] = 15
        self.assertFalse(assess(a, b).eligible)
        # A run that has them against one that hasn't compares different counts.
        del a.results["cases"][0]["chunksInRange"]
        self.assertFalse(assess(a, b).eligible)

    def test_unknown_recording_flags_do_not_count_as_false(self):
        a, b = self.run_file(), self.run_file()
        b.results["cases"][0]["moved"] = None
        self.assertFalse(assess(a, b).eligible)

    def test_unknown_focus_is_excluded_even_when_both_runs_match(self):
        for value in (None, 0, "false"):
            a, b = self.run_file(), self.run_file()
            a.cases["view-rd32"]["unfocused"] = b.cases["view-rd32"]["unfocused"] = value
            self.assertFalse(assess(a, b, allow_uncapped_unfocused=True).eligible)

    def test_declared_candidate_change_must_actually_exist(self):
        report = assess(self.run_file(), self.run_file(), expected={"properties.experiment": (None, "true")})
        self.assertFalse(report.eligible)
        self.assertTrue(any("declared difference absent" in reason for reason in report.exclusions))

    def test_shader_fixture_missing_changed_or_unstable_is_excluded(self):
        for kind in ("missing", "pack", "settings", "during"):
            a, b = self.run_file(), self.run_file()
            if kind == "missing":
                del a.meta["shaderFixture"]
                del b.meta["shaderFixture"]
            elif kind == "during":
                b.meta["shaderFixture"]["after"]["vitrail"]["pack.txt"] = "changed"
            else:
                filename = "pack.zip" if kind == "pack" else "pack.zip.txt"
                for phase in ("before", "after"):
                    b.meta["shaderFixture"][phase]["shaderpacks"][filename] = "changed"
            self.assertFalse(assess(a, b).eligible, kind)

    def test_harness_snapshot_hashes_settings_directory_packs_and_missing_inputs(self):
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if shell is None:
            self.skipTest("PowerShell required for harness snapshot integration")
        helper = Path(__file__).with_name("bench-fixture.ps1").resolve()
        def snapshot():
            command = ". '{}' ; Get-BenchmarkShaderFixture '{}' | ConvertTo-Json -Depth 10".format(
                str(helper).replace("'", "''"), str(self.root).replace("'", "''"))
            return json.loads(subprocess.run([shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
                                            capture_output=True, text=True, check=True).stdout)
        original = snapshot()
        self.assertIsNone(original["vitrail"]["amortise-shadow"])
        self.assertIsNone(original["irisProperties"])
        self.assertEqual(original["shaderpacks"], {})
        pack = self.root / "shaderpacks/directory/shaders"
        pack.mkdir(parents=True)
        (pack / "terrain.fsh").write_text("first shader")
        settings = self.root / "shaderpacks/directory.txt"
        settings.write_text("QUALITY=HIGH")
        first = snapshot()
        self.assertIn("directory/shaders/terrain.fsh", first["shaderpacks"])
        settings.write_text("QUALITY=LOW")
        second = snapshot()
        self.assertNotEqual(first["shaderpacks"]["directory.txt"], second["shaderpacks"]["directory.txt"])
        (pack / "terrain.fsh").write_text("second shader")
        self.assertNotEqual(second["shaderpacks"]["directory/shaders/terrain.fsh"],
                            snapshot()["shaderpacks"]["directory/shaders/terrain.fsh"])
        config = self.root / "config"
        config.mkdir()
        iris = config / "iris.properties"
        iris.write_text("enableShaders=true")
        on = snapshot()["irisProperties"]
        iris.write_text("enableShaders=false")
        self.assertNotEqual(on, snapshot()["irisProperties"])

    def test_harness_records_cull_validation_inputs(self):
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if shell is None:
            self.skipTest("PowerShell required for harness snapshot integration")
        helper = Path(__file__).with_name("bench-fixture.ps1").resolve()
        def snapshot():
            command = ". '{}' ; Get-BenchmarkCullValidation '{}' | ConvertTo-Json".format(
                str(helper).replace("'", "''"), str(self.root).replace("'", "''"))
            return json.loads(subprocess.run([shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
                                            capture_output=True, text=True, check=True).stdout)
        self.assertEqual(snapshot(), {"fault": None, "portable": False})
        directory = self.root / "vultrum"
        directory.mkdir()
        (directory / "cull-failure").write_text("after-phase1-draw:32")
        (directory / "portable-compute").write_text("")
        self.assertEqual(snapshot(), {"fault": "after-phase1-draw:32", "portable": True})

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

    def test_series_cannot_use_runs_hours_apart_or_separate_sessions(self):
        brackets = [self.bracket((50, 30, 50)) for _ in range(3)]
        for run in brackets[2]:
            for key in ("started", "finished"):
                run.meta[key] = (datetime.fromisoformat(run.meta[key]) + timedelta(days=1)).isoformat()
        result = self.score(brackets)
        self.assertFalse(result["eligible"])
        self.assertTrue(any("idle gap" in reason for reason in result["exclusions"]))

    def test_individually_ordered_brackets_cannot_overlap_other_runs(self):
        brackets = [self.bracket((50, 30, 50)) for _ in range(3)]
        for run in brackets[1]:
            for key in ("started", "finished"):
                run.meta[key] = (datetime.fromisoformat(run.meta[key]) - timedelta(seconds=170)).isoformat()
        report = self.score(brackets)
        self.assertFalse(report["eligible"])
        self.assertIn("series runs overlap", report["exclusions"])

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
