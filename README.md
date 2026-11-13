<p align="center">
  <img src="https://raw.githubusercontent.com/SolarBiscuit/Atmosphera/main/docs/logo/atmosphera-icon.svg" width="160" alt="Atmosphera">
</p>

# Atmosphera

Named Stratos until 2026-11-12 (0.10.0 and older).

Clouds, sky and air for the [Atmosphera](https://github.com/SolarBiscuit/Atmosphera) suite on Minecraft Forge 1.20.1:
weather-driven, volumetric clouds that look like a shaderpack's, at about the cost of vanilla's, **without a
shaderpack**. Later releases add the rest of the sky (see the roadmap).

**Current version: 0.12.0** (needs AtmospheraLib 0.10.1 or newer).

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
- **Works with Lumis.** Lumis draws no clear-sky light rays or lens flare through Atmosphera's clouds.
- **Styles:** ATMOSPHERA (realistic, default), SOFT, BLOCKY (vanilla-like cells). Resource packs can replace them
  (`assets/atmosphera/styles/*.json`).

## Added in 0.4.0 to 0.6.0
Released together as 0.6.0; 0.6.1 fixes an AMD driver crash and 0.6.2-0.6.3 have the audit fixes. Every feature has its own
switch and is on by default.
- **Mirror:** clouds and the sky in water. Atmosphera keeps a small map of the whole sky (128x64) and Torrente's water
  reflects it (sunsets, twilight, clouds).
- **Storm:**
  - anvils, mammatus pouches and shelf clouds on thunder storms;
  - rain shafts and virga under raining clouds;
  - lightning inside the clouds, the same flashes for every player on a server;
  - regional clouds: fewer over deserts, flatter over snow, gathered on windward slopes and clear on lee sides.
- **Air:**
  - biome fog colour and distance from data files in `assets/atmosphera/air` (resource packs can replace them);
  - weather fog, and mist at dawn over water and wet ground;
  - a distant haze over terrain and LODs;
  - it defers to Atmospherics while that is installed.
- **Stars:** a "Star amount" option (0-300%) beside "Star brightness". Since 0.12.0, 100% is what 30% was before; older settings are rescaled.
- **Reload in game:** `/atmosphera reload` re-reads the settings; `/atmosphera reload shaders` (and the "Reload
  Atmosphera" key) also rebuilds Atmosphera's shaders.
- **Option sections** in `atmosphera-client.toml`: "storm" (storm clouds, rain shafts, lightning, regional clouds) and
  "air" (biome fog, haze, weather fog, dawn mist).

## Nebulae (0.9.0)
- **Real images.** Nebulae are 8 telescope images from ESA/Hubble and ESA/Webb (CC BY 4.0), one per nebula night. Each
  is 18 to 72 degrees across (most about 34).
- **They light the night.** A glow in the sky round the nebula, a reflection in water and, with Lumis, a tint and lift
  of the night's sky light, strongest at new moon.
- **Resource packs** can add images in `assets/<namespace>/textures/nebula/` (JPEG or PNG).
- **High, nearly still, up to three (0.10.0).**
  - They stand high in the sky: 35 to 80 degrees up at midnight, faded in above 25 degrees.
  - They stay nearly still against the land through the night (their frame turns at a tenth of the stars' rate).
  - Up to three can show on one night: a second on about 30% of nebula nights, a third on 10%. The option "Nebula
    count" sets the most.
- **Stars (0.10.0):** the options "Star twinkle" and "Twinkle speed" set how much and how fast stars twinkle.
- **Command:** `/atmosphera skyeffect nebula on <n> [count]` shows image n, and count nebulae.
- **Credits:** see `THIRD_PARTY_NOTICES.md` (in the jar, and in `src/main/resources/`).

## Golden hour (0.12.0)
- **Moon:** NASA's Lunar Reconnaissance Orbiter map of the near side on a sphere, with Lommel-Seeliger shading and
  earthshine. The option "Moon detail" sets the map size (HIGH 4096, MEDIUM 2048, LOW 1024).
- **Sunrise and sunset:** cloud light comes from the sky model's transmittance at each deck's height, and clouds no
  longer burn white towards the sun.
- **Aurora Australis:** a very rare band of pink, red and violet light over the southern horizon in icy biomes on dark,
  clear nights (option "Aurora Australis" and its chance).
- **With Stratos 0.4.0:** sandstorm air in deserts and badlands, and clouds by kind of rain.
- The resource pack's sun and moon are on by default for new settings.

## Cost
One post pass at a fraction of the screen's resolution, smoothed over frames.

| Quality | Resolution | Target GPU time (1440p) |
|---|---|---|
| LOW | 1/4, no history | ≤ 0.05 ms |
| MEDIUM (default) | 1/4, temporal | ≤ 0.15 ms |
| HIGH | 1/2, temporal | ≤ 0.25 ms |

These are targets; they are **not yet measured**. Only AMD hardware has been tested.

## Install
1. Forge 1.20.1 (47.x) and [AtmospheraLib](https://github.com/SolarBiscuit/AtmospheraLib) 0.10.1 or newer.
2. Put the Atmosphera jar in `mods/`.
3. Options are on the "Atmosphera (clouds)" page in Video Settings (with Xenon or Embeddium), and in
   `config/Atmosphera/atmosphera-client.toml`.
   Settings are grouped in dropdowns (closed by default, and remembered). Without Xenon or Embeddium, the library's own settings screen (the "Atmosphera..." button in Video Settings, or Config in the Mods list) shows the same groups.

## Roadmap
| Release | Theme |
|---|---|
| 0.1.0 "Deck" | Clouds |
| 0.2.0 "Shade" | Cloud shadows on the ground and light beams through cloud breaks (with Lumis) (built) |
| 0.3.0 "Firmament" | Atmosphera's own sky: scattering, sun and moon, stars, twilight (built) |
| 0.4.0 "Mirror" | Clouds in Torrente's water reflections (built) |
| 0.5.0 "Storm" | Anvils, rain shafts, lightning inside clouds, clouds on mountains (built) |
| 0.6.0 "Air" | Biome fog and haze (built; 0.4.0 to 0.6.0 released together as 0.6.x, current 0.6.3) |
| 0.7.0 "Optics" | Halos, sun dogs, rainbows, the green flash |
| 0.8.0 "Night" | Aurora, Aurora Australis (0.12.0), noctilucent clouds, meteors |

Details: `docs/roadmap/ATMOSPHERA-MOD-ROADMAP.md` and `docs/ATMOSPHERA-MOD-DESIGN.md` in the Atmosphera repository.

## Licence
**All Rights Reserved.** Copyright (C) 2026 SolarBiscuit. The source is public so it can be read. Unmodified jars may be
included in modpacks with credit; everything else needs permission. See [`LICENSE`](LICENSE). The Atmosphera sky mod needs
AtmospheraLib, which is a separate GPL-3.0 work.

The Atmosphera sky mod is an independent project, not affiliated with Mojang Studios, Microsoft, Minecraft Forge, or the authors of any
mod, resource pack or shaderpack it works with.
