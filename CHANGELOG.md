# Changelog

What each release changed, for players. Newest first. Downloads are on [GitHub](https://github.com/DebZakari/Vultrum/releases) and [Modrinth](https://modrinth.com/mod/vultrum).

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
