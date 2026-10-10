# Seeker Drones — Improvements Backlog

Ideas and refinements that are **not tied to a roadmap milestone**. This is a backlog, not a spec:

- Nothing here is v1 scope. Do not implement anything from this file unless the user explicitly asks.
- Items marked `idea` are undecided and may change or be dropped.
- Once an item is decided, its behavior goes into `DESIGN.md` (and a milestone in `ROADMAP.md` if needed), and the item here is marked `decided`. Once it's implemented, the item is **removed** from this file (`DESIGN.md` is the record).
- Items that are agreed but deliberately postponed belong in `DESIGN.md` section 11 instead.

Status: `idea` | `decided`

---

## Drone behavior

- **Jammer block** (`idea`): a powered block that counters **enemy** drones within a radius, for example by breaking their target lock, stopping their scans or draining their energy (effect TBD). It should consume **ludicrous amounts of energy** so it can't cover a whole base for free. It needs a way to tell friend from foe: e.g. its own Operator Group, set by the player who places it. Machines never check operator permissions (`CLAUDE.md`), so this is about which drones it affects, not who can use it. To stay cheap, the check should run in the drone's staggered scan (DESIGN.md §3.3) against a per-level list of active jammers, not in a per-tick jammer scan. Radius, energy cost and effect would be config entries.
- **EMP grenade** (`idea`): a throwable item that disables drones hit by its blast for a few seconds (for example they stop scanning and hover, or lose energy). Separate from the Jammer block. It's a cheap, consumable counter, so it **doesn't need to be as expensive** as the Jammer. Needs decisions on whether it affects allied drones, the blast radius, the duration and the effect. All values would be config entries.

## Upgrades and energy

- **Antiprotonic Nucleosynthesizer for late-game recipes** (`idea`): the Mekanism variants of really late-game items (e.g. the most expensive upgrades) should require the Antiprotonic Nucleosynthesizer.
- **Solar upgrade** (`idea`, direction agreed): a drone upgrade that generates real energy (FE) from sunlight. The drone's net energy change is the solar output minus its energy usage.
  - **Stacking:** stackable, cap 4. **One upgrade is not enough to make a drone self-sufficient.** Energy usage depends on the drone's upgrades (the upgrade multiplier, DESIGN.md §5.1), so whether a drone is self-sufficient also depends on its other upgrades.
  - **Cost:** it should be **very expensive to craft**.
  - **Conditions:** it only generates with sky access (a `canSeeSky` heightmap lookup, which is cheap) during the day, and generates less in rain and thunder.
  - **Performance:** the generation is applied in the existing batched energy drain (every `drone.energyDrainInterval` ticks, DESIGN.md §8.4), so it adds no extra ticking.
  - Output per upgrade and the weather multipliers would be config entries.
- **Quiet upgrade** (`idea`, direction agreed; name TBD, e.g. "Stealth" or "Silencer"): a drone upgrade for silent watchers.
  - It lowers the volume of the drone's own sounds (flying, low power and so on, DESIGN.md section 2.9).
  - It **hides the drone's nameplate** (label), since a floating name tag defeats the point.
  - It's **mutually exclusive with the Siren upgrade**: a drone is either a loud deterrent or a silent watcher. The Programming Station and the debug command must refuse to install one while the other is installed.
  - **Still open:** how much each upgrade lowers the volume, the cap, whether it offsets the extra loudness from having many upgrades, and whether it also quiets the Explosive approach sound (a stealth kamikaze drone is fun but maybe harsh in PvP). All values would be config entries.
- **Transparent drone upgrade** (`idea`): an upgrade that makes the drone (semi-)transparent or invisible. Could be part of the Quiet upgrade above as one "Stealth" upgrade, or separate.

## Drone GUI and visuals
  - It depends on the new drone model (ROADMAP.md M9). Tell the artist so the model and animations work at any scale.
- **Status reasons** (`idea`): the status screen (DESIGN.md §2.4) and Jade (§8.6) show a drone's state, but not why it is in it. Drones can sit idle or misbehave for reasons the player can't see, so each state should come with a short reason line, e.g. "Idle: no usable target entries" or "Returning: station unreachable, trying the next one".
  - **Reasons to cover** (from the existing rules): no target entries, or only blacklisted or unusable ones (a player entry without Player Seek, §3.3); a target in view but already claimed by a teammate (§3.3); target out of sight, with the lost-sight countdown (§3.5); no usable Charging Station in range, so it will drop at 0 energy (§5.2); waiting by a busy station (§5.3); a station skipped as unreachable (§5.2); docked at a station with no FE; an unknown Operator Group (§6.2).
  - **Performance:** no extra syncing. The reason is worked out on the server only when a status screen or Jade asks for the drone's status (the existing on-demand payloads), from state the drone already keeps. Where a reason needs data the drone doesn't keep today (e.g. why the last scan found nothing), it's recorded cheaply during the staggered scan, not by an extra check.
  - **Still open:** the exact list and wording, whether operators only see the reasons (like the rest of the Jade details, §8.6), and whether recent events are shown too (e.g. "Lost target 12 s ago").

## Sounds

- **More sounds** (`idea`): the drone and machine sounds are implemented (DESIGN.md section 2.9). Still open:
  - **Explosive approach:** check in playtests whether the flying loop's rising pitch is enough. A non-Explosive drone chasing a fast target also flies fast, so pitch alone may not tell a kamikaze from a follower. If needed, give Explosive drones that are chasing a steeper pitch curve (the client already knows when one is approaching), or add a speeding-up beep layer.
  - Factory crafting sounds, a Programming Station completion chime, a Charging Station hum loop, a pickup sound, and sounds for the Jammer block and EMP grenade if those are made.
  - The **Quiet upgrade** lowers the result of the upgrade loudness (see above).

## Items and interaction

- **Drone Tracker and drone list** (`idea`, prioritized): help players find their drones. A drone that runs out of energy drops as an item wherever it is (§5.2), and drones far from players freeze in unloaded chunks (§3.6), so with dozens of drones losing track of them is common.
  - **Drone list:** a screen listing the drones a player operates (by label and ID, §2.8), with each one's last known position and dimension, state and energy, and whether it's deployed, docked, or an item (dropped, in a chest or in an inventory). It could be opened from the Drone Remote or the Tracker. It's a small slice of the v2 dashboard, without the camera or map.
  - **Tracker:** a compass-like item that points to one drone's last known position, picked from the list or linked like the Remote.
  - **Needs:** a server-side registry of drone ID → last known position and form (a `SavedData`, like the Charging Station registry, §8.3). It's updated at the moments a drone changes form or is saved (deploy, pickup, drop at 0 energy, destruction, chunk unload), not every tick. Positions of deployed drones in loaded chunks can be read live when the list is opened.
  - **Still open:** whether an item in a chest or another player's inventory is tracked (hard to keep current), how long destroyed drones stay in the list, and whether non-operators can see a drone in the list at all (probably not, §6.3).

## Mod compatibility

- **Defuser upgrade and Industrial Foregoing's Infinity Nuke** (`idea`, direction agreed): a **Defuser** upgrade (not stackable) lets a drone defuse bombs. A Defuser drone that reaches a defusable entity defuses it (e.g. removes it, and primed TNT drops a TNT item) instead of following it. The main use case is defusing IF's Infinity Nuke.
  - It's **incompatible with the Explosive upgrade**: the Programming Station and the debug command refuse to install one while the other is installed.
  - It should be **very expensive to craft**. The nuke is a late-game item, so a cheap counter would annoy its users. Defusing could also take a few seconds of hovering over the bomb (interruptible) instead of being instant.
  - **Unclear:** how to define what's defusable. One option is a `seekerdrones:defusable` entity tag (e.g. primed TNT and IF's nuke entity as an optional entry, `"required": false`). It would need no code dependency on IF, and modpack makers could extend it with datapacks. Not decided.
  - **Open:** whether the Defuser makes the drone **automatically target** defusable things (without using a target slot, and maybe ahead of its normal targets), or whether they have to be added as target entries. Also whether it covers blocks as well as entities. Primed TNT is an entity, and an unlit TNT block isn't a threat, so entities may be enough, depending on how IF's nuke works.
  - IF specifics (the nuke's entity ID, how arming works in 1.21.1, what to drop) are validated when this is implemented. It's an optional integration, so the mod must keep working without IF. Mekanism is currently the only optional integration (DESIGN.md §7.5).
- **ComputerCraft / CC: Tweaked integration** (`idea`, direction agreed): an optional integration, so the mod must keep working without CC: Tweaked (the peripheral code is only loaded when CC is present, like Mekanism). In phases:
  1. **Read-only machine peripherals:** each machine exposes its status (energy, fluid, progress, the docked or installed drone's ID, energy and health, the queue). This fits the rule that machines never check operator permissions (`CLAUDE.md`), and it's cheap, since it only runs when a computer calls it.
  2. **Drone events:** a "Drone Receiver" block (or a peripheral on an existing machine) that receives Transmitter messages as computer events (e.g. `drone_target_spotted` with the drone ID, target and coordinates), for alarms, logging and automation. Still read-only.
  3. **Control:** recalling drones, setting targets, deploying. This needs an explicit permission decision, e.g. the peripheral block records its placer and only controls drones that the placer is an operator of. It overlaps with the **Drone Remote**, so both should share the same rule.
- **FTB Teams / Open Parties and Claims** (`idea`, optional, timing unclear): optionally map Operator Groups (DESIGN.md §6) to these mods' teams, so servers don't have to manage two team systems. Not yet looked into.
- **JEI info pages** (`idea`, direction agreed): extend the existing JEI integration (DESIGN.md §8) with info pages, e.g. for the Programming Station (what each upgrade does, its caps and incompatibilities) and the other machines.

## Documentation

- **In-game manual** (`idea`): a guide book that explains the drones, upgrades, machines and Operator Groups in game. Specifics TBD, including which library to use (e.g. Patchouli, Modonomicon, or GuideME as used by AE2). It should be optional or bundled carefully, and it can link to the JEI entries.
- **Wiki** (`idea`): an online wiki for players and modpack makers (upgrades, machines, config options, recipes, integrations). Specifics TBD, e.g. where it's hosted (GitHub wiki, a docs site, or the Modrinth/CurseForge page) and how it stays in sync with the config and DESIGN.md.
