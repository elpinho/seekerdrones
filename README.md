# Seeker Drones

[![Build](https://github.com/elpinho/seekerdrones/actions/workflows/build.yml/badge.svg)](https://github.com/elpinho/seekerdrones/actions/workflows/build.yml)

**Put a fleet of hunter drones in the sky.** Tell them what to look for (zombies, creepers, a mob tag, even a specific player), and they patrol your land, lock on the moment they spot it, and chase it down. What happens next depends on how you built them: they shadow the target, wail a siren, send you its coordinates... or dive straight into it and **explode**.

Seeker Drones is a NeoForge mod for Minecraft 1.21.1. Drones are built, programmed, launched and recharged by machines, and every step can be automated, so your base keeps its defenses in the air long after you've logged off.

> **Status: in development (pre-1.0).** The mod isn't released yet, and it isn't on CurseForge or Modrinth. The drone and item art are placeholders until the commissioned art is in, and numbers in the config are still being balanced. Saves may break between versions.

## Features

### Kamikaze drones

Give a drone the **Explosive** upgrade and it stops following its target and flies into it instead, blowing up on contact. Stack up to four for a bigger blast. You'll hear one coming: as an Explosive drone closes in, it beeps faster and faster, like a parking sensor counting down. Server admins choose whether the explosions break blocks, and can cap how many of a team's Explosive drones go after the same target.

### Drones that think for themselves

- **Patrol** an area around a point and height you pick, scanning for anything on their target list.
- **Chase** what they spot and stay on it, going back to their patrol if it escapes.
- **Manage their own energy:** when a drone runs low, it flies back to a Charging Station, waits in line for a free one, tops up and goes back to work.
- **Upgrade** them to fit the job: Patrol, Sight, Explosive, Siren, Transmitter, Energy, Health, Player Seek, Solar, FPV Camera and Silencer. Want a loud deterrent? Add Sirens. A silent watcher? Silencers quiet its rotors and hide its nameplate.

### A fully automatable production line

```
Drone Factory ──> Programming Station ──> Deploying Station ──> drone in the world
 (builds drones)   (upgrades and config)   (launches them)            │
                                                                      ▼
                                                         Charging Station (recharges)
```

Feed the machines with pipes, hoppers or conveyors, and drones come off the line built, programmed with their targets and upgrades, and launched, without a player in sight. You can also deploy a drone by hand: throw it, and pick it back up later with all its settings.

### See what they see

- **Drone Remote:** check any of your drones, watch its live first-person feed through the FPV Camera upgrade, and take the controls to fly it yourself.
- **Radar machines** in three tiers:
  - **Radar Station:** a green phosphor scope of the land around it, with your drones lit up by the sweep.
  - **Satellite Uplink:** a wider map in full color showing every drone in range, yours identified and strangers' not, with an intruder alarm on a comparator and your drones' camera feeds one click away.
  - **Command Center:** a holotable war room. Box-select your drones on the map and send them orders: Move, Patrol Here, Strike, Hold, Return to Charge or Release.

### Made for multiplayer

- **Operator Groups** decide who may control which drones. Machines never check permissions, so automation keeps working.
- **Server settings** control explosion block damage, allowed and banned targets, whether players can be targeted, and how many drones each player may have deployed (64 by default).
- **Built to run many drones at once:** scans and energy use are batched to keep the server's tick time low.

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

## How this mod is made

Seeker Drones is a solo project, and I want to be open about how it's built:

- **Code:** written with AI assistance (Claude), with me designing the mod, deciding how it behaves and playtesting it. Changes are checked by an automated GameTest suite that runs on every push.
- **Current art:** the placeholder textures and models are drawn by scripts in `scripts/textures/`, which were also written with AI.
- **Sounds:** recordings by people on Freesound, credited in [`SOUND_CREDITS.md`](SOUND_CREDITS.md). Two (a beep and the siren) were synthesized for the mod.
- **Commissioned art:** the drone and item art is being commissioned from a human artist, who will be credited here.

## License

- **Code:** [GNU LGPL v3.0](COPYING.LESSER) (which builds on the [GPL v3.0](COPYING)). You may use, change and share it. A changed version of the mod that you distribute must stay open source under the LGPL. Other mods may depend on and integrate with it freely.
- **Art assets** (textures, models and the mod's own sounds): [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/). See [`ASSETS_LICENSE.md`](ASSETS_LICENSE.md) for which files that covers.
- **Third-party sounds:** CC0 1.0 or CC BY 4.0, credited in [`SOUND_CREDITS.md`](SOUND_CREDITS.md).
- **Modpacks:** you may include the unmodified mod in any modpack.
