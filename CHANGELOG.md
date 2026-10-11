# Changelog

What each release changed, for players. Newest first. Downloads are on [GitHub](https://github.com/DebZakari/Vultrum/releases) and Modrinth.

## Unreleased

- GPU Occlusion Culling now adapts automatically to GPUs with lower compute limits instead of switching off. The fallback was checked on the RTX 3090; AMD and Intel GPUs remain untested.

## 0.1.0-alpha.1 (2026-10-12)

First public release, for Minecraft 26.3 on Fabric. Tested on one PC (Windows 11, RTX 3090), so please report anything odd with your GPU.

- GPU Occlusion Culling for Vitrail shader packs: terrain hidden behind other terrain isn't drawn. On by default, and it turns itself off in open scenes.
- Light-Facing Shadow Faces: only the sides of solid blocks that face the sun or moon go into the shadow map. On by default.
- Skip Sunless Terrain: caves and other terrain sunlight can't reach stay out of the shadow map. Off by default.
- Smoother chunk loading, with or without shaders: Spread Chunk Arrivals, Smaller Terrain Buffers, Spread Chunk Unloads and Background Buffer Cleanup. All on by default.
- Every setting is on Vultrum's two pages in Sodium's video settings. Changes apply after a restart.

Needs Sodium 0.9.3-alpha.1 or later and Fabric API. The culling also needs Minecraft's Graphics API set to Vulkan, and Vitrail.
