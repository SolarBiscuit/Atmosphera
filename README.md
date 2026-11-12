<p align="center">
  <img src="https://raw.githubusercontent.com/SolarBiscuit/Atmosphera/main/docs/logo/stratos-icon.svg" width="160" alt="Stratos">
</p>

# Stratos

Clouds, sky and air for the [Atmosphera](https://github.com/SolarBiscuit/Atmosphera) suite on Minecraft Forge 1.20.1:
weather-driven, volumetric clouds that look like a shaderpack's, at about the cost of vanilla's, **without a
shaderpack**. Later releases add the rest of the sky (see the roadmap).

**Current version: 0.6.3** (needs AtmospheraLib 0.8.2 or newer).

> **Client-only.** Never required on a server, never blocks joining one. Off while a shaderpack is active (packs draw
> their own clouds) and under Fabulous graphics.

## What it does (0.1.0)
- **Three cloud decks.** A volumetric low deck of fair-weather cumulus; thin altocumulus above it; high, wispy cirrus
  that the faster wind aloft drags sideways.
- **Weather.** Rain turns the sky into a low grey sheet, thunder builds towering storm clouds, dry biomes have fewer
  clouds and cold ones flatter ones. Clouds drift with the shared wind (Gale's, when installed).
- **Light.** A silver lining towards the sun, dark storm bases, clouds glowing after sunset, moonlight at night, haze
  with distance, and a curved layer that sinks to the horizon.
- **Distant terrain.** NeoVoxy and Voxy LODs hide the clouds behind them; Distant Horizons too (not yet tested in
  game).
- **Works with Lumis.** Lumis draws no clear-sky light rays or lens flare through Stratos's clouds.
- **Styles:** STRATOS (realistic, default), SOFT, BLOCKY (vanilla-like cells). Resource packs can replace them
  (`assets/stratos/styles/*.json`).

## Added in 0.4.0 to 0.6.0
Released together as 0.6.0; 0.6.1 fixes an AMD driver crash and 0.6.2-0.6.3 have the audit fixes. Every feature has its own
switch and is on by default.
- **Mirror:** clouds and the sky in water. Stratos keeps a small map of the whole sky (128x64) and Torrente's water
  reflects it (sunsets, twilight, clouds).
- **Storm:**
  - anvils, mammatus pouches and shelf clouds on thunder storms;
  - rain shafts and virga under raining clouds;
  - lightning inside the clouds, the same flashes for every player on a server;
  - regional clouds: fewer over deserts, flatter over snow, gathered on windward slopes and clear on lee sides.
- **Air:**
  - biome fog colour and distance from data files in `assets/stratos/air` (resource packs can replace them);
  - weather fog, and mist at dawn over water and wet ground;
  - a distant haze over terrain and LODs;
  - it defers to Atmospherics while that is installed.
- **Stars:** a "Star amount" option (0-300%) beside "Star brightness".
- **Reload in game:** `/atmosphera reload` re-reads the settings; `/atmosphera reload shaders` (and the "Reload
  Atmosphera" key) also rebuilds Stratos's shaders.
- **Option sections** in `stratos-client.toml`: "storm" (storm clouds, rain shafts, lightning, regional clouds) and
  "air" (biome fog, haze, weather fog, dawn mist).

## Cost
One post pass at a fraction of the screen's resolution, smoothed over frames.

| Quality | Resolution | Target GPU time (1440p) |
|---|---|---|
| LOW | 1/4, no history | ≤ 0.05 ms |
| MEDIUM (default) | 1/4, temporal | ≤ 0.15 ms |
| HIGH | 1/2, temporal | ≤ 0.25 ms |

These are targets; they are **not yet measured**. Only AMD hardware has been tested.

## Install
1. Forge 1.20.1 (47.x) and [AtmospheraLib](https://github.com/SolarBiscuit/AtmospheraLib) 0.8.2 or newer.
2. Put the Stratos jar in `mods/`.
3. Options are on the "Stratos (clouds)" page in Video Settings (with Xenon or Embeddium), and in
   `config/Atmosphera/stratos-client.toml`.

## Roadmap
| Release | Theme |
|---|---|
| 0.1.0 "Deck" | Clouds |
| 0.2.0 "Shade" | Cloud shadows on the ground and light beams through cloud breaks (with Lumis) (built) |
| 0.3.0 "Firmament" | Stratos's own sky: scattering, sun and moon, stars, twilight (built) |
| 0.4.0 "Mirror" | Clouds in Torrente's water reflections (built) |
| 0.5.0 "Storm" | Anvils, rain shafts, lightning inside clouds, clouds on mountains (built) |
| 0.6.0 "Air" | Biome fog and haze (built; 0.4.0 to 0.6.0 released together as 0.6.x, current 0.6.3) |
| 0.7.0 "Optics" | Halos, sun dogs, rainbows, the green flash |
| 0.8.0 "Night" | Aurora, noctilucent clouds, meteors |

Details: `docs/roadmap/STRATOS-ROADMAP.md` and `docs/STRATOS-DESIGN.md` in the Atmosphera repository.

## Licence
**All Rights Reserved.** Copyright (C) 2026 SolarBiscuit. The source is public so it can be read. Unmodified jars may be
included in modpacks with credit; everything else needs permission. See [`LICENSE`](LICENSE). Stratos needs
AtmospheraLib, which is a separate GPL-3.0 work.

Stratos is an independent project, not affiliated with Mojang Studios, Microsoft, Minecraft Forge, or the authors of any
mod, resource pack or shaderpack it works with.
