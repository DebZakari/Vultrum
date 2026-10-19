# Changelog

What each release changed, for players. Newest first. Downloads are on [GitHub](https://github.com/DebZakari/Vultrum/releases) and [Modrinth](https://modrinth.com/mod/vultrum).

## 0.1.0-alpha.7 (2026-10-19)

- Added **Faster Punchy Hands** in the Compatibility group on Vultrum's Culling page in Sodium's video settings. It reduces repeated shader compatibility checks when using Punchy 2.8f-hotfix with Vitrail, without skipping hand animations.
- The option is **off by default** and takes effect after a restart. It stays inactive with Iris, other Punchy versions, or an unsupported Vitrail version.

Checked on the RTX 3090 with Fresh Animations enabled, including held items, swings, resource-pack reload, world rejoin and mod compatibility. The FPS benefit depends on the scene and mod setup. The published performance charts still describe alpha.5.

Known issue in the tested full mod setup: switching shaders off and back on caused a Vulkan device-loss crash with this option both enabled and disabled. This release does not fix that shader-switching issue.

## 0.1.0-alpha.6 (2026-10-16)

- Reworked coordination of terrain reloads and chunk unloading, preserving the existing loading settings and budgets.
- Checked compatibility with Sodium 0.9.3-beta.1 and Fabric API 0.163.0+26.3.
- Added a [reproducible setup for testing on other GPUs](https://github.com/DebZakari/Vultrum/blob/v0.1.0-alpha.6/bench/TESTING.md), with fixed shader settings and correctness checks before performance testing.

This is a maintenance and compatibility update. Settings and defaults are unchanged. Reloads, shader switching, dimension changes, window changes and rejoining passed across all four combinations of Spread Terrain Reloads and Spread Chunk Unloads. Checked on the RTX 3090; other GPUs remain untested. The published performance charts still describe alpha.5.

## 0.1.0-alpha.5 (2026-10-14)

- Vultrum's culling now works with Vitrail 0.13. With Vitrail 0.13 installed, earlier versions greyed out the Culling settings and left the culling off.

This is a compatibility update. Settings and defaults are unchanged. Checked on the RTX 3090 with Vitrail 0.13.0-beta; other GPUs remain untested.

## 0.1.0-alpha.4 (2026-10-13)

- Improved recovery from GPU Occlusion Culling failures, preserving terrain and transparent blocks before falling back to ordinary drawing for the rest of the session.
- Improved cleanup when culling initialization fails or the game closes. The log now reports whether culling is active and explains why it fell back.

This is a stability update. Settings and defaults are unchanged. Checked on the RTX 3090; other GPUs remain untested.

## 0.1.0-alpha.3 (2026-10-13)

- Added **Spread Terrain Reloads** to the Loading page, enabled by default. It spreads already-loaded terrain registration across frames after F3+A or a render distance change, reducing reload freezes.
- Terrain reappears over several frames during the reload. New chunk arrivals are handled immediately as before. The setting can be turned off and takes effect after a restart.

On the RTX 3090, median peak frames during repeated reloads at 32 chunks fell by 27–47% across three comparisons, with a longer comparison confirming the improvement. Average FPS stayed essentially unchanged. Other GPUs remain untested.

## 0.1.0-alpha.2 (2026-10-12)

- GPU Occlusion Culling now adapts automatically to GPUs with lower compute limits instead of switching off. The fallback was checked on the RTX 3090; AMD and Intel GPUs remain untested.

This is a compatibility update. Performance on the RTX 3090 stayed within the variation between runs.

## 0.1.0-alpha.1 (2026-10-12)

First public release, for Minecraft 26.3 on Fabric. Tested on one PC (Windows 11, RTX 3090), so please report anything odd with your GPU.

- GPU Occlusion Culling for Vitrail shader packs: terrain hidden behind other terrain isn't drawn. On by default, and it turns itself off in open scenes.
- Light-Facing Shadow Faces: only the sides of solid blocks that face the sun or moon go into the shadow map. On by default.
- Skip Sunless Terrain: caves and other terrain sunlight can't reach stay out of the shadow map. Off by default.
- Smoother chunk loading, with or without shaders: Spread Chunk Arrivals, Smaller Terrain Buffers, Spread Chunk Unloads and Background Buffer Cleanup. All on by default.
- Every setting is on Vultrum's two pages in Sodium's video settings. Changes apply after a restart.

Needs Sodium 0.9.3-alpha.1 or later and Fabric API. The culling also needs Minecraft's Graphics API set to Vulkan, and Vitrail.
