<p align="center">
  <img src="docs/images/logo.svg" width="112" alt="">
</p>

<h1 align="center">Vultrum</h1>

<p align="center">
  More FPS with shader packs and smoother chunk loading for Minecraft 26.3 on Vulkan.<br>
  A Sodium add-on for Fabric.
</p>

<p align="center">
  <a href="https://github.com/DebZakari/Vultrum/releases">GitHub</a> ·
  <a href="https://modrinth.com/mod/vultrum">Modrinth</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="#install">Install</a> ·
  <a href="#settings">Settings</a> ·
  <a href="#compatibility">Compatibility</a> ·
  <a href="https://github.com/DebZakari/Vultrum/issues">Report a bug</a>
</p>

<p align="center">
  <img src="docs/images/hero.webp" alt="The same view without Vultrum on the left and with it on the right, with a divider sweeping across. Dark forest 272 to 354 FPS (+30%); Snowy peaks 264 to 299 FPS (+13%); Savanna hillside 332 to 376 FPS (+13%). Both sides look the same.">
  <br>
  <sub>Left without Vultrum, right with it. The same picture, more frames.</sub>
</p>

> [!NOTE]
> Vultrum is in alpha. Version 0.1.0-alpha.6 has been tested on one PC: Windows 11, an NVIDIA RTX 3090 and a Ryzen 5 5600X. If something breaks or looks wrong, please [open an issue](https://github.com/DebZakari/Vultrum/issues) and say which GPU you have.

## What it does

Minecraft 26.3 can draw with Vulkan instead of OpenGL. Vultrum adds two things on top of Sodium there.

**With a Vitrail shader pack**, it works out on the GPU, every frame, which terrain is hidden behind hills, trees and buildings, and skips drawing it. It also leaves block faces out of the shadow map when they can't change a shadow. Dense scenes gain the most: 30% more FPS in a dark forest at render distance 32. Terrain and shadows look the same.

**With or without shaders**, it smooths out chunk loading. Sodium does some of its loading work in bursts: a pile of new chunks from the server, the chunks that leave your view, the cleanup after Java's garbage collector runs. Vultrum spreads that work over several frames, so flying into new terrain stutters less. Alpha.3 also spreads already-loaded terrain registration across frames after F3+A or a render distance change, reducing reload freezes.

Most features are on by default and can be turned off in Sodium's video settings. Skip Sunless Terrain is off by default.

Inspired by [Nvidium](https://github.com/MCRcortex/nvidium) and [Vercidium's voxel optimisation work](https://vercidium.com/blog/voxel-world-optimisations/).

## Performance

In alpha.3, median peak frames during repeated terrain reloads at 32 chunks were 27–47% shorter across three comparisons on the RTX 3090. A longer comparison confirmed the improvement. Average FPS stayed essentially unchanged; the change reduces reload freezes. The FPS charts and flight results below were measured with alpha.5.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/fps-dark.svg">
  <img src="docs/images/fps-light.svg" alt="Bar chart of average FPS with shaders at 32 chunks, without and with Vultrum. Dark forest 272 to 354 (+30%); Snowy peaks 264 to 299 (+13%); Savanna hillside 332 to 376 (+13%); Jungle 311 to 347 (+12%); Savanna valley 299 to 331 (+11%); Village 390 to 424 (+9%); High in the sky 270 to 275 (+2%).">
</picture>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/flight-dark.svg">
  <img src="docs/images/flight-light.svg" alt="Bar chart of 1% low FPS while flying into new terrain, without and with Vultrum. Jungle at 32 chunks 87 to 107 (+24%); Dark forest at 32 chunks 88 to 104 (+17%); Jungle at 16 chunks 170 to 177 (+4%); Dark forest at 16 chunks 152 to 168 (+11%).">
</picture>

Complementary Unbound through Vitrail, 1920×1080 fullscreen, RTX 3090 and Ryzen 5 5600X, measured with Vultrum 0.1.0-alpha.5 and Vitrail 0.13.0-beta.vultrum.1. "Without Vultrum" is the same game with all of Vultrum's settings off, which is plain Sodium and Vitrail. Every number is the mean of two runs of the benchmark in this repository ([`tools/bench.ps1`](tools/bench.ps1)), after a discarded warm-up. Static scenes wait until terrain finishes building before recording.

These performance results use Sodium 0.9.3-alpha.1 and Fabric API 0.162.0+26.3. The newer dependency versions below were checked for compatibility; their performance has not been compared here.

How much you gain depends on how much of the view is hidden. In a dark forest most of the terrain behind the first trees never gets drawn. High in the sky almost nothing is hidden, so the culling switches itself off and FPS stays where it was. At 16 chunks there's less to hide, and the gains are smaller (0 to 13%).

While flying at 32 chunks, 1% low FPS was 17 to 24% higher with Vultrum. These lows and the longest frames fluctuate between runs.

Vitrail in these runs was [Vultrum's build of Vitrail](https://github.com/DebZakari/vitrail-shaders/releases/tag/0.13.0-beta.vultrum.1), which has a few speedups that Vitrail's own release doesn't have yet. With Vitrail from Modrinth, FPS will be somewhat lower overall.

<details>
<summary>All numbers</summary>

Standing still, 10 seconds per scene:

| Scene | Render distance | FPS without | FPS with | Change | 1% low without | 1% low with |
|---|---:|---:|---:|---:|---:|---:|
| Dark forest | 32 | 272 | 354 | +30% | 229 | 282 |
| Snowy peaks | 32 | 264 | 299 | +13% | 224 | 247 |
| Savanna hillside | 32 | 332 | 376 | +13% | 270 | 297 |
| Jungle | 32 | 311 | 347 | +12% | 257 | 281 |
| Savanna valley | 32 | 299 | 331 | +11% | 245 | 267 |
| Village | 32 | 390 | 424 | +9% | 304 | 329 |
| High in the sky | 32 | 270 | 275 | +2% | 226 | 233 |
| Dark forest | 16 | 323 | 364 | +13% | 266 | 291 |
| Snowy peaks | 16 | 345 | 360 | +4% | 279 | 289 |
| Savanna hillside | 16 | 361 | 390 | +8% | 285 | 303 |
| Jungle | 16 | 322 | 348 | +8% | 262 | 282 |
| Savanna valley | 16 | 340 | 359 | +6% | 258 | 272 |
| Village | 16 | 408 | 427 | +5% | 320 | 319 |
| High in the sky | 16 | 388 | 387 | 0% | 303 | 307 |

Flying straight ahead at 30 blocks a second for 10 seconds, into terrain the game hasn't loaded yet:

| Flight | Render distance | FPS without | FPS with | 1% low without | 1% low with | 0.1% low without | 0.1% low with | Longest frame without | Longest frame with |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Jungle | 32 | 231 | 241 | 87 | 107 | 73 | 85 | 14.7 ms | 12.7 ms |
| Dark forest | 32 | 195 | 210 | 88 | 104 | 66 | 87 | 16.5 ms | 13.4 ms |
| Jungle | 16 | 326 | 334 | 170 | 177 | 140 | 152 | 8.1 ms | 6.9 ms |
| Dark forest | 16 | 292 | 302 | 152 | 168 | 95 | 136 | 17.2 ms | 8.5 ms |

</details>

## Install

You need a Fabric instance for Minecraft 26.3 on Java 25, with these mods:

| Mod | Version | |
|---|---|---|
| [Sodium](https://modrinth.com/mod/sodium) | 0.9.3, starting with alpha.1 | required. 0.9.2 won't load with Vultrum |
| [Fabric API](https://modrinth.com/mod/fabric-api) | 0.162.0+26.3 or later | required by Sodium |
| [Vitrail Shaders](https://modrinth.com/mod/vitrail-shaders) | 0.12 or 0.13 | optional, for shader packs on Vulkan and for Vultrum's culling |
| Vultrum | 0.1.0-alpha.6 | from [GitHub Releases](https://github.com/DebZakari/Vultrum/releases) or [Modrinth](https://modrinth.com/mod/vultrum) |

Fabric Loader 0.19.5 or later.

Vultrum's Sodium integration supports versions from 0.9.3-alpha.1 up to, but excluding, 0.9.4-alpha. Unsupported versions disable the features that depend on Sodium until compatibility is checked.

Alpha.6 was checked with Sodium 0.9.3-beta.1 and Fabric API 0.163.0+26.3 on the RTX 3090 with Vitrail 0.13 and the existing 32-mod setup, including terrain reloads, shader switching, dimension changes, window changes and rejoining. Alpha.5 also supports these dependency versions.

<a id="vitrail"></a>For shader packs you can use Vitrail from Modrinth, or [Vultrum's build of Vitrail](https://github.com/DebZakari/vitrail-shaders/releases/tag/0.13.0-beta.vultrum.1). It's the build Vultrum is tested with: Vitrail 0.13.0-beta, plus faster drawing of mobs and chests. It replaces Vitrail in your `mods` folder. If something goes wrong with it, report it on [Vultrum's issues](https://github.com/DebZakari/Vultrum/issues), not Vitrail's.

What changed in each version is in the [changelog](CHANGELOG.md).

Then switch the game to Vulkan: **Options → Video Settings → Graphics API → Prefer Vulkan (Experimental)**, and restart. The game's default is OpenGL. Vultrum's loading fixes still work there, but its culling doesn't.

## Settings

Vultrum adds two pages to Sodium's video settings, **Culling** and **Loading**. A change is saved right away and applies the next time you start the game. Each setting's tooltip says what it does and what it was measured to do.

**Culling** needs Vitrail and is greyed out without it. The shadow settings work while a shader pack is on. GPU Occlusion Culling needs a pack enabled when the game starts and can keep working if you switch the pack off during play.

| Setting | Default | |
|---|---|---|
| GPU Occlusion Culling | on | Skips terrain hidden behind other terrain. It turns itself off in open scenes, where it would cost more than it saves. |
| Light-Facing Shadow Faces | on | Draws only the sides of solid blocks that face the sun or moon into the shadow map. |
| Skip Sunless Terrain | off | Leaves terrain that sunlight can't reach, such as caves, out of the shadow map. About 1% more FPS. |

GPU Occlusion Culling falls back to ordinary terrain drawing when the device is unsupported or its cull fails. A runtime failure disables it for the rest of the session. The log reports whether the setting was requested, whether it is active, and why it is off. A shader pack must be enabled and its saved launch state readable when the game starts; enabling it later needs a restart.

**Loading** needs only Sodium, and works without shaders.

| Setting | Default | |
|---|---|---|
| Spread Chunk Arrivals | on | Handles at most 1 ms of the server's packets a frame, so a burst of new chunks is spread out. |
| Smaller Terrain Buffers | on | Caps each new terrain buffer at 96 MiB. A bigger one stalled the GPU for up to 5 ms. Uses up to 0.6 GB more video memory when flying fast at 32 chunks. |
| Spread Chunk Unloads | on | Removes about 0.25 ms worth of chunks a frame instead of all of them at once. |
| Spread Terrain Reloads | on | Registers about 2 ms of already-loaded terrain columns a frame after F3+A or a render distance change. Terrain reappears over several frames while the reload completes. |
| Background Buffer Cleanup | on | Moves Sodium's buffer cleanup to its own thread. After a garbage collection it could take up to 33 ms of one frame. |

## Compatibility

Vultrum is client-side. It works in singleplayer and on servers, and the server doesn't need it.

GPU occlusion culling automatically adapts to the GPU's compute limits, including devices without subgroup arithmetic or with 128-thread workgroups. Both compute paths have been checked on the RTX 3090; AMD and Intel hardware remain untested.

Tested on Vulkan together with Vitrail and Complementary Unbound, all in one instance, with no errors from Vultrum:

These compatibility checks use default settings. Keep Skip Sunless Terrain off with ScalableLux; that combination has not been validated.

- **Performance mods:** Lithium, FerriteCore, ImmediatelyFast, Entity Culling, More Culling, C2ME, ScalableLux, Distant Horizons, Sodium Extra, Bobby, Dynamic FPS, BadOptimizations, Krypton, Debugify, Particle Core, FastQuit and Language Reload.
- **Other popular mods:** Xaero's Minimap and World Map, JourneyMap, AppleSkin, Zoomify, Mod Menu, YACL, Cloth Config, ViaFabricPlus, LambDynamicLights, Continuity, Entity Model Features, Entity Texture Features, 3D Skin Layers, Not Enough Animations, First-person Model, JEI, Jade, Litematica, Freecam, Mouse Tweaks, Inventory Profiles Next, Controlling, Chat Heads, Shulker Box Tooltip, Wavey Capes, Sound Physics Remastered, Presence Footsteps, BetterF3 and Visuality.

Doesn't work with:

- **Nvidium** and **Reese's Sodium Options.** Both need Sodium 0.9.2, so the game won't start with them and Vultrum. Nvidium is also OpenGL only.
- **Iris shaders.** Iris switches itself off on Vulkan, so its packs need OpenGL. It loads next to Vultrum without problems, it just won't draw a pack. Use Vitrail for shader packs on Vulkan.
- **Distant Horizons with Iris** on Vulkan. Distant Horizons picks OpenGL when Iris is installed and stops the game with a dialog. With Vitrail instead of Iris it works.
- Other mods that only render with OpenGL.

## FAQ

**Do I need a shader pack?**
No. Without one, the loading fixes still work. The culling only runs under a Vitrail shader pack, because that's where terrain costs the most to draw.

**Will it work on my AMD or Intel GPU?**
It hasn't been tested on them yet. Vultrum uses plain Vulkan features, nothing NVIDIA-only. If you try it, please open an issue and say how it went, good or bad.

**Is it like Nvidium?**
Similar goal. Nvidium needs an NVIDIA GPU and OpenGL. Vultrum runs on Minecraft's Vulkan renderer and isn't tied to one GPU brand.

**Can I put it in a modpack?**
Yes. See the licence below.

## Reporting bugs

Open an issue with:

- what happened, and a screenshot if it's visual (with the place, the time of day and your render distance),
- your GPU and driver version,
- `logs/latest.log` and your mod list.

## Building

`./gradlew build` with JDK 25. The jar ends up in `build/libs/`.

To help test another GPU, use the [reproducible tester setup](bench/TESTING.md). It pins the shader build, pack and settings and saves results locally.

## Licence

Vultrum is licensed under the [GNU LGPL 3.0](LICENSE). You can use it in modpacks and other projects. If you change Vultrum itself and share the result, share your changes under the same licence.
