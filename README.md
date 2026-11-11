<p align="center">
  <img src="https://raw.githubusercontent.com/SolarBiscuit/Atmosphera/main/docs/logo/stratos-icon.svg" width="160" alt="Stratos">
</p>

# Stratos

Clouds for the [Atmosphera](https://github.com/SolarBiscuit/Atmosphera) suite on Minecraft Forge 1.20.1: weather-driven,
volumetric clouds that look like a shaderpack's, at about the cost of vanilla's, **without a shaderpack**. Later
releases add the sky itself (see the roadmap).

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

## Cost
One post pass at a fraction of the screen's resolution, smoothed over frames.

| Quality | Resolution | Target GPU time (1440p) |
|---|---|---|
| LOW | 1/4, no history | ≤ 0.05 ms |
| MEDIUM (default) | 1/4, temporal | ≤ 0.15 ms |
| HIGH | 1/2, temporal | ≤ 0.25 ms |

These are targets; they are **not yet measured**. Only AMD hardware has been tested.

## Install
1. Forge 1.20.1 (47.x) and [AtmospheraLib](https://github.com/SolarBiscuit/AtmospheraLib) 0.7.0 or newer.
2. Put the Stratos jar in `mods/`.
3. Options are on the "Stratos (clouds)" page in Video Settings (with Xenon or Embeddium), and in
   `config/Atmosphera/stratos-client.toml`.

## Roadmap
| Release | Theme |
|---|---|
| 0.1.0 "Deck" | Clouds (this release) |
| 0.2.0 "Shade" | Cloud shadows on the ground and light beams through cloud breaks (with Lumis) |
| 0.3.0 "Firmament" | Stratos's own sky: scattering, sun and moon, stars, twilight |
| 0.4.0 "Mirror" | Clouds in Torrente's water reflections |
| 0.5.0 "Storm" | Anvils, rain shafts, lightning inside clouds, clouds on mountains |
| 0.6.0 "Air" | Biome fog and haze |
| 0.7.0 "Optics" | Halos, sun dogs, rainbows, the green flash |
| 0.8.0 "Night" | Aurora, noctilucent clouds, meteors |

Details: `docs/roadmap/STRATOS-ROADMAP.md` and `docs/STRATOS-DESIGN.md` in the Atmosphera repository.

## Licence
**All Rights Reserved.** Copyright (C) 2026 SolarBiscuit. The source is public so it can be read. Unmodified jars may be
included in modpacks with credit; everything else needs permission. See [`LICENSE`](LICENSE). Stratos needs
AtmospheraLib, which is a separate GPL-3.0 work.

Stratos is an independent project, not affiliated with Mojang Studios, Microsoft, Minecraft Forge, or the authors of any
mod, resource pack or shaderpack it works with.
