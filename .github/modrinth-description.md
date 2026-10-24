![The same view without Vultrum on the left and with it on the right, with a divider sweeping across. Dark forest 272 to 354 FPS (+30%); Snowy peaks 264 to 299 FPS (+13%); Savanna hillside 332 to 376 FPS (+13%). Both sides look the same.](https://raw.githubusercontent.com/DebZakari/Vultrum/main/docs/images/hero.webp)

Left without Vultrum, right with it. The same picture, more frames.

**Alpha.9.** Tested on one PC so far: Windows 11, an NVIDIA RTX 3090 and a Ryzen 5 5600X. If something breaks or looks wrong, please [open an issue on GitHub](https://github.com/DebZakari/Vultrum/issues) and say which GPU you have.

## What it does

Minecraft 26.3 can draw with Vulkan instead of OpenGL. Vultrum adds two things on top of Sodium there.

**With a [Vitrail](https://modrinth.com/mod/vitrail-shaders) shader pack**, it works out on the GPU, every frame, which terrain is hidden behind hills, trees and buildings, and skips drawing it. It also leaves block faces out of the shadow map when they can't change a shadow. Dense scenes gain the most: 30% more FPS in a dark forest at render distance 32. Terrain and shadows look the same.

**With or without shaders**, it smooths out chunk loading. Sodium does some of its loading work in bursts: a pile of new chunks from the server, the chunks that leave your view, the cleanup after Java's garbage collector runs. Vultrum spreads that work over several frames, so flying into new terrain stutters less. Since alpha.3, it also spreads already-loaded terrain registration across frames after F3+A or a render distance change, reducing reload freezes.

Most features are on by default and can be turned off in Sodium's video settings. Skip Sunless Terrain and Faster Punchy Hands are off by default.

## Performance

![Average FPS with shaders at 32 chunks, without and with Vultrum](https://raw.githubusercontent.com/DebZakari/Vultrum/main/docs/images/fps.png)

![1% low FPS while flying into new terrain, without and with Vultrum](https://raw.githubusercontent.com/DebZakari/Vultrum/main/docs/images/flight.png)

The charts and gallery measure **Vultrum 0.1.0-alpha.5**, with Complementary Unbound through Vitrail 0.13.0-beta.vultrum.1, Sodium 0.9.3-alpha.1 and Fabric API 0.162.0+26.3, at 1920×1080 on the RTX 3090 and Ryzen 5 5600X. They are not new alpha.9 measurements. Every number is the mean of two runs after a discarded warm-up; static scenes wait for terrain builds to finish. Frame-time lows fluctuate between runs. "Without Vultrum" is the same game with all of Vultrum's settings off, which is plain Sodium and Vitrail.

Vitrail in these runs was [Vultrum's build of Vitrail](https://github.com/DebZakari/vitrail-shaders/releases/tag/0.13.0-beta.vultrum.1), which has a few speedups that Vitrail's own release doesn't have yet. With Vitrail from Modrinth, FPS will be somewhat lower overall. The full numbers and how they were measured are on [GitHub](https://github.com/DebZakari/Vultrum#performance). Newer dependency versions have been checked for compatibility; their performance has not been compared here.

## Setup

Use a Fabric instance for **Minecraft 26.3**, with **Java 25** and **Fabric Loader 0.19.5 or later**.

1. Install [Fabric API](https://modrinth.com/mod/fabric-api) **0.162.0+26.3 or later** and [Sodium](https://modrinth.com/mod/sodium) **0.9.3-alpha.1 or later within the 0.9.3 series**. Sodium 0.9.2 won't load with Vultrum. Versions from 0.9.4-alpha onward disable Sodium-dependent features until compatibility is checked. Alpha.6 was checked with Sodium 0.9.3-beta.1 and Fabric API 0.163.0+26.3.
2. Optional: [Vitrail Shaders](https://modrinth.com/mod/vitrail-shaders) **0.12 or 0.13**, for shader packs on Vulkan and for Vultrum's culling. The current tested fork is [Vultrum's build of Vitrail 0.13.0-beta.vultrum.4](https://github.com/DebZakari/vitrail-shaders/releases/tag/0.13.0-beta.vultrum.4): faster mob and chest drawing, fixes for mipmapped shader targets and Entity Texture Features glow overlays, and support for Wakes Reforged trails with shaders on Minecraft 26.3. It replaces Vitrail in your mods folder. Report its bugs to [Vultrum](https://github.com/DebZakari/Vultrum/issues), not to Vitrail.
3. In game, set **Options → Video Settings → Graphics API** to **Prefer Vulkan (Experimental)** and restart. On OpenGL the loading fixes still work, but the culling doesn't.

## Settings

Two pages in Sodium's video settings. Changes apply the next time you start the game.

- **Culling** needs Vitrail: GPU Occlusion Culling, Light-Facing Shadow Faces, Skip Sunless Terrain (off by default). The shadow settings work while a shader pack is on. GPU Occlusion Culling needs a pack enabled when the game starts and can keep working if you switch the pack off during play.
- **Compatibility**, on the Culling page: Faster Punchy Hands (off by default). It reduces repeated shader compatibility checks without skipping hand animations. It needs Punchy 2.8f-hotfix or 2.8g and a supported Vitrail version, with Iris absent. Other versions leave the hook disabled.
- **Loading** needs only Sodium: Spread Chunk Arrivals, Smaller Terrain Buffers, Spread Chunk Unloads, Spread Terrain Reloads, Background Buffer Cleanup.

Each setting's tooltip says what it does and what it was measured to do. GPU Occlusion Culling falls back to ordinary terrain drawing if its initialization or runtime cull fails; the log explains why it is inactive.

Alpha.8 refreshes cached player-head textures after resource-pack reloads, fixing the crash reproduced when subsequently switching shaders off and back on in the tested full mod setup. Alpha.9 adds Punchy 2.8g support. See the [changelog](https://github.com/DebZakari/Vultrum/blob/main/CHANGELOG.md) for each release.

## Compatibility

Client-side only. Works in singleplayer and on servers, and the server doesn't need it. Both compute paths have been checked on the RTX 3090; AMD and Intel hardware remain untested.

Tested with popular mods including Lithium, FerriteCore, ImmediatelyFast, Entity Culling, More Culling, C2ME, ScalableLux, Distant Horizons, Sodium Extra, Xaero's maps, JourneyMap and JEI. The full list and test limits are on [GitHub](https://github.com/DebZakari/Vultrum#compatibility).

Skip Sunless Terrain remains off by default. It additionally passed light-update, reload and shadow-image checks with ScalableLux 0.3.0-alpha.0.6+26.3, Vitrail 0.13.0-beta.vultrum.1 and Complementary Unbound r5.9.3.

With BadOptimizations 2.4.1 and Xaero's Minimap, player head icons can become dots. Set `enable_entity_renderer_caching: false` in `config/badoptimizations.txt` and restart. This is a [known compatibility issue](https://github.com/imthosea/BadOptimizations/issues/145).

Wakes Reforged 1.4.1 wake trails passed shader toggle, resource reload and rejoin checks with Vitrail 0.13.0-beta.vultrum.4 and Complementary Unbound r5.9.3 on Minecraft 26.3. Leave Wakes' render type on `AUTO`.

Doesn't work with Nvidium or Reese's Sodium Options (both need Sodium 0.9.2), or with Iris shaders, which switch themselves off on Vulkan. Distant Horizons with Iris on Vulkan stops at a dialog; with Vitrail instead of Iris it works. Other mods that render only with OpenGL are unsupported.

## Licence

[LGPL 3.0](https://github.com/DebZakari/Vultrum/blob/main/LICENSE). Feel free to put it in modpacks.
