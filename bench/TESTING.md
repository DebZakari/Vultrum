# Testing Vultrum on another GPU

Use this setup to test alpha.6 with Sodium 0.9.3-beta.1 and Fabric API 0.163.0+26.3, using the same shader settings and scenarios on each PC. It creates `run-compat-tester` inside this checkout and never edits a launcher instance. Results stay in ignored `bench-runs/` directories.

## Requirements and setup

- Windows, Git, PowerShell 7, Python 3.11 or later, and a JDK 25 installation.
- Enough RAM for the benchmark's fixed 8 GB Java heap, plus the game and OS. Keep other games closed.
- Set `JAVA_HOME` to your JDK 25 installation (the reference PC uses 25.0.4). These scripts target Windows; Linux and macOS need a separate runner. Use a clean checkout; the runner refuses tracked edits and extra Java/profiling environment flags.

Clone the repository, keeping its release tags, and run these commands from its root:

```powershell
git checkout v0.1.0-alpha.6
python tools/tester_setup.py --prepare
python tools/tester_setup.py --verify
```

The setup downloads the exact released Vitrail `0.13.0-beta.vultrum.1` jar and Complementary Unbound r5.9.3 from their published URLs and checks SHA-256 before installing either. Their URLs, checksums and settings are in [`tester-fixture.json`](tester-fixture.json). The shader pack is downloaded from its author's distribution and is not bundled here. Existing downloads can be used with `--vitrail-jar PATH --shader-pack PATH`; their checksums are still checked.

Gradle supplies Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.163.0+26.3 and Sodium 0.9.3-beta.1, as pinned in `gradle.properties`. It builds Vultrum from this checkout. The setup compares runtime/build source with the immutable release tag in the profile's `sourceTag` field, so the runtime matches alpha.6. It also refuses to overwrite an existing tester directory.

An existing tester directory belongs to its original profile and is not migrated automatically. Use a separate checkout for this recipe. The alpha.5 recipe with these newer dependencies remains at commit `8cb934e`; the earlier recipe with Sodium 0.9.3-alpha.1 and Fabric API 0.162.0+26.3 remains at `c50fd2d`. Keep their checkouts and results separate. Runs using different Vultrum runtimes or dependency combinations are different fixtures and must not be combined into one performance comparison.

This is the minimal shader fixture. Extra performance mods and personal worlds are excluded. Worlds are generated from seed `20261008`; the scenarios select the same positions and located biomes/structures. This reproduces the recipe, not the exact entity positions or world state of an earlier recording.

Use 1920×1080 for the reference framebuffer when possible. Fullscreen uses the monitor's resolution; the harness records the actual framebuffer. If yours differs, report it and keep it identical between your off/on runs. Compare Vultrum off/on on the same PC; do not expect another GPU's FPS to match the RTX 3090 or compare two different GPUs as an optimization pair.

## Start with correctness

Use PowerShell 7 (`pwsh`), leave the PC idle while the game is running, and let each command finish before starting the next:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/tester_run.ps1 -Suite Smoke -Label my-gpu-smoke
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/tester_run.ps1 -Suite Robust -Label my-gpu-robust
python tools/tester_setup.py --verify
```

Check screenshots and `latest.log` before performance testing. The robustness scenario covers terrain reload, pack off/on, camera movement, Nether/End, resizing, fullscreen and rejoining. A completed run alone does not establish visual correctness. If the game fails, keep its output and report the failure before retrying. Do not use `-Force` to bypass another running Minecraft client.

## Optional FPS comparison

The compact static scenario records overview, dark forest and village at 16 and 32 chunks, after terrain builds have gone quiet. First run a discarded warm-up, then default/off/off/default without unrelated PC activity. Use unique labels if repeating a series:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/tester_run.ps1 -Suite Static -Label my-gpu-static
```

The runner stops on a failed or incomplete run and restores the original launch settings even after failure. Controller logs also stay under `bench-runs/`. All eight settings are off in the baseline; default settings enable seven, with Skip Sunless Terrain still off. This compares Vultrum's contribution while keeping the same Vitrail build and shader pack in both arms. Warm-up/generation, driver, thermal and scheduling variation mean these are exploratory two-repeat results, not an automatic production keep decision.

For a wider check, select `-Suite Visual` and analyze its run with [`tools/visual_check.py`](../tools/visual_check.py), or select `-Suite Flight` for traversal. `tools/bench.ps1 -Compat run-compat-tester -Scenario bench/scenarios/compat.json` checks entities and the settings pages too. These use the same tester directory. The private deep-dark world used in some local checks is not distributed. Multiplayer and extended play are separate coverage; the short robustness run does not establish either.

## Return evidence

Send the exact run directories printed by the harness, including `results.json`, `meta.json`, `latest.log`, the saved options/configs, screenshots and `gpu.csv` when present. Include GPU model, driver version, OS, monitor resolution, whether terrain/shadows looked correct, and whether the log says culling was active or fell back. A fallback result is useful but does not prove the accelerated path ran.

Review logs for personal paths or server addresses before sharing. Results are ignored by Git. Do not commit raw runs or shader downloads. If using GitHub issues, attach a zipped set of the relevant run directories and a brief description; no account credentials are needed in a report.
