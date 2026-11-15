# Seeker Drones

[![Build](https://github.com/elpinho/seekerdrones/actions/workflows/build.yml/badge.svg)](https://github.com/elpinho/seekerdrones/actions/workflows/build.yml)

A NeoForge mod for Minecraft 1.21.1 that adds autonomous flying drones. A drone patrols an area, spots the kind of entity you told it to look for, and chases it. Depending on its upgrades, it then follows the target, sounds an alarm, reports it to you, or explodes.

Drones are built, programmed, deployed and recharged by machines, and every step accepts automation, so a base can keep a fleet in the air without anyone being there.

> **Status: in development (pre-1.0).** The mod isn't released yet, and it isn't on CurseForge or Modrinth. Textures and models are placeholders until the commissioned art is in, and numbers in the config are still being balanced. Saves may break between versions.

## Features

- **Drones** you throw to deploy and pick back up with all their settings. Each has a label, a color, an ID, energy and health, and slots for the targets it hunts: entity types, entity tags or specific players.
- **Upgrades:** Patrol, Sight, Explosive, Siren, Transmitter, Energy, Health, Player Seek, Solar, FPV Camera and Silencer.
- **The machine pipeline:**
  ```
  Drone Factory ──> Programming Station ──> Deploying Station ──> drone in the world
   (builds drones)   (upgrades and config)   (deploys them)            │
                                                                       ▼
                                                          Charging Station (recharges)
  ```
  Drones running low on energy fly back to a Charging Station on their own, queue for a free one and resume their patrol.
- **Drone Remote:** check a drone's status, watch its live first-person feed with the FPV Camera upgrade, and fly it by hand.
- **Radar machines** in three tiers, each building on the last:
  - **Radar Station:** a phosphor terrain map showing your own drones.
  - **Satellite Uplink:** a wider map in map colors, every drone in the area, an intruder alarm, and a drone's feed opened from the map.
  - **Command Center:** a holotable showing every drone, plus group orders (Move, Patrol Here, Strike, Hold, Return to Charge, Release).
- **Operator Groups:** choose who may control which drones. Machines never check permissions, so automation keeps working.
- **Server safety:** settings for explosion block damage, a target allowlist and denylist, a switch for player targets, and a limit on deployed drones per player (64 by default).
- **Built for many drones:** scans and energy use are batched, so dozens of drones per player stay cheap on the server.

The full behavior spec is in [`docs/DESIGN.md`](docs/DESIGN.md), and the plan in [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Optional integrations

The mod works on its own. These mods add extras when they're installed:

- **[Mekanism](https://www.curseforge.com/minecraft/mc-mods/mekanism):** alternative recipes that use Mekanism materials.
- **[JEI](https://www.curseforge.com/minecraft/mc-mods/jei):** recipes for the machines.
- **[Jade](https://www.curseforge.com/minecraft/mc-mods/jade):** machine and drone info when you look at them.

## Building from source

You need Java 21. The Gradle toolchain downloads it if it isn't installed.

```bash
./gradlew build
```

The mod jar ends up in `build/libs/`.

Other tasks:

| Task | What it does |
|---|---|
| `./gradlew runClient` | Starts a dev client. |
| `./gradlew runServer` | Starts a dev dedicated server. |
| `./gradlew runData` | Runs the data generators (output is committed in `src/generated`). |
| `./gradlew runGameTestServer` | Runs the GameTests, as CI does. `-PgametestFilter=<regex>` runs a subset. |

Add `-PwithMekanism` to a run to start it with Mekanism. On Windows, `scripts/` has PowerShell helpers to boot a dev server for a quick check, reload resources in a running client, and profile the server tick cost of many drones.

## Reporting issues

Bugs and suggestions go in the [issue tracker](https://github.com/elpinho/seekerdrones/issues). For a bug, include the mod, NeoForge and Minecraft versions, the other mods you use, and the log or crash report.

## License

- **Code:** [GNU LGPL v3.0](COPYING.LESSER) (which builds on the [GPL v3.0](COPYING)). You may use, change and share it. A changed version of the mod that you distribute must stay open source under the LGPL. Other mods may depend on and integrate with it freely.
- **Art assets** (textures, models and the mod's own sounds): [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/). See [`ASSETS_LICENSE.md`](ASSETS_LICENSE.md) for which files that covers.
- **Third-party sounds:** CC0 1.0 or CC BY 4.0, credited in [`SOUND_CREDITS.md`](SOUND_CREDITS.md).
- **Modpacks:** you may include the unmodified mod in any modpack.
