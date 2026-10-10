# Seeker Drones — Improvements Backlog

Ideas and refinements that are **not tied to a roadmap milestone**. This is a backlog, not a spec:

- Nothing here is v1 scope. Do not implement anything from this file unless the user explicitly asks.
- Items marked `idea` are undecided and may change or be dropped.
- Once an item is decided, its behavior goes into `DESIGN.md` (and a milestone in `ROADMAP.md` if needed), and the item here is marked `decided`. Once it's implemented, the item is **removed** from this file (`DESIGN.md` is the record).
- Items that are agreed but deliberately postponed belong in `DESIGN.md` section 11 instead.

Status: `idea` | `decided`

---

## Drone behavior

- **Drones targeting other drones** (`idea`, direction agreed): today `TargetMatcher.matches()` excludes every `DroneEntity`, so drones can never target drones. Proposal:
  - Lift that exclusion for **enemy** drones only: a drone with a different Operator Group or owner. A drone's own allies (same group, or same owner if it has no group) are always exempt, like operators are exempt from Player Seek. Unowned drones count as enemies to everyone.
  - Gate it behind a new **Interceptor** upgrade (name TBD, not stackable), in the same way that Player Seek gates player names. With it, `seekerdrones:drone` (or a drone tag) works as a target entry and uses a target slot like any other. The Interceptor upgrade should be **expensive to craft**.
  - A non-Explosive interceptor has no attack, so it only follows the enemy drone (surveillance, plus Siren and Transmitter alerts). An Explosive interceptor is an anti-drone missile, built from the existing mechanics.
- **Jammer block** (`idea`): a powered block that counters **enemy** drones within a radius, for example by breaking their target lock, stopping their scans or draining their energy (effect TBD). It should consume **ludicrous amounts of energy** so it can't cover a whole base for free. It needs a way to tell friend from foe: e.g. its own Operator Group, set by the player who places it. Machines never check operator permissions (`CLAUDE.md`), so this is about which drones it affects, not who can use it. To stay cheap, the check should run in the drone's staggered scan (DESIGN.md §3.3) against a per-level list of active jammers, not in a per-tick jammer scan. Radius, energy cost and effect would be config entries.
- **EMP grenade** (`idea`): a throwable item that disables drones hit by its blast for a few seconds (for example they stop scanning and hover, or lose energy). Separate from the Jammer block. It's a cheap, consumable counter, so it **doesn't need to be as expensive** as the Jammer or the Interceptor upgrade. Needs decisions on whether it affects allied drones, the blast radius, the duration and the effect. All values would be config entries.

## Upgrades and energy

- **Antiprotonic Nucleosynthesizer for late-game recipes** (`idea`): the Mekanism variants of really late-game items (e.g. the most expensive upgrades) should require the Antiprotonic Nucleosynthesizer.
- **Solar upgrade** (`idea`, direction agreed): a drone upgrade that generates real energy (FE) from sunlight. The drone's net energy change is the solar output minus its energy usage.
  - **Stacking:** stackable, cap 4. **One upgrade is not enough to make a drone self-sufficient.** Energy usage depends on the drone's upgrades (the upgrade multiplier, DESIGN.md §5.1), so whether a drone is self-sufficient also depends on its other upgrades.
  - **Cost:** it should be **very expensive to craft**.
  - **Conditions:** it only generates with sky access (a `canSeeSky` heightmap lookup, which is cheap) during the day, and generates less in rain and thunder.
  - **Performance:** the generation is applied in the existing batched energy drain (every `drone.energyDrainInterval` ticks, DESIGN.md §8.4), so it adds no extra ticking.
  - Output per upgrade and the weather multipliers would be config entries.
- **Quiet upgrade** (`idea`, direction agreed; name TBD, e.g. "Stealth" or "Silencer"): a drone upgrade for silent watchers.
  - It lowers the volume of the drone's own sounds (flying, charging, low power and so on; see **Drone and machine sounds** below).
  - It **hides the drone's nameplate** (label), since a floating name tag defeats the point.
  - It's **mutually exclusive with the Siren upgrade**: a drone is either a loud deterrent or a silent watcher. The Programming Station and the debug command must refuse to install one while the other is installed.
  - **Still open:** how much each upgrade lowers the volume, the cap, whether it offsets the extra loudness from having many upgrades, and whether it also quiets the Explosive approach sound (a stealth kamikaze drone is fun but maybe harsh in PvP). All values would be config entries.
- **Transparent drone upgrade** (`idea`): an upgrade that makes the drone (semi-)transparent or invisible. Could be part of the Quiet upgrade above as one "Stealth" upgrade, or separate.

## Drone GUI and visuals

- **Hide non-living entity types from targets** (`idea`): decide whether non-living entity types (item frames, arrows, boats, minecarts and so on) can be target entries at all, and whether the target auto-complete (DESIGN.md §7.2) suggests them. Today they are valid entries and are suggested. Hiding them makes the lists cleaner, but a player might want to target boats or minecarts.
- **Drone size by upgrade count** (`idea`, direction agreed): a drone's **model** gets slightly bigger the more upgrades it has. It's **visual only**: the hitbox stays fixed (0.75 × 0.4 today), so path finding (the flying node size), the clear-path raycasts (§3.4 and §8.4), the fit checks at follow and patrol positions and the deploy obstruction checks are all unaffected.
  - The growth is **slight**: a fully upgraded drone must never reach 2.5× the base size.
  - The base model can be slightly smaller than it is now, to make room for the growth.
  - Growth rate and max scale would be config entries (client-side rendering, driven by the synced upgrade count).
  - It fits with the upgrade-dependent energy usage (DESIGN.md §5.1) and louder drones with many upgrades (**Drone and machine sounds**): a heavily upgraded drone is bigger, hungrier and louder.
  - It depends on the new drone model (ROADMAP.md M9). Tell the artist so the model and animations work at any scale.

## Sounds

- **Drone and machine sounds** (`idea`, direction agreed): add custom sounds, registered through the sounds `DeferredRegister` (DESIGN.md §8). Today only the Siren has its own sound event (`seekerdrones:drone_siren`, with the raid horn as a placeholder). Hurt, destroyed and pickup use vanilla sounds, and flying and the machines are silent. Samples come from **free libraries** (e.g. CC0 on freesound.org, with the licenses checked and credited where required). There's no sound designer, and the M9 commission covers art only.
  - **Flying loop:** a looping hum or rotor sound for every deployed drone. It's played on the client (a tickable sound instance per drone), so there are no server packets. Pitch and volume follow the drone's velocity (already synced), so hovering is a low hum and chasing is higher and louder. Minecraft has a limited pool of sound channels, so **only the N nearest drones play the loop** (re-sorted about once a second). Farther drones are silent.
  - **Explosive approach:** no separate sound. The flying loop's speed-driven pitch already rises as an Explosive drone accelerates toward its target (§3.4). Caveats to check in playtests:
    - A non-Explosive drone chasing a fast target also flies fast, so pitch alone may not tell a kamikaze from a follower. If needed, give Explosive drones that are chasing a steeper pitch curve. The client would need one synced "Explosive and chasing" flag.
    - An approaching Explosive drone should always be in the nearest-N loop set, or it could be silent in a swarm.
    - If it still isn't distinct enough, add a speeding-up beep layer later.
  - **Siren:** a custom siren to replace the raid horn (DESIGN.md §4). It repeats every `upgrades.siren.repeatInterval` (100 ticks), so it should be a ~3–4 s wail that doesn't overlap itself. It's already a variable-range event, so volume > 1 keeps extending the audible range.
  - **Deploy:** a drone spin-up sound whenever a drone spawns, whether by hand or by a Deploying Station. The Deploying Station adds a mechanical launch clunk on top.
  - **Programming Station:** a soft click for each upgrade installed, and a chime when programming is complete.
  - **Charging Station:** a docking clamp sound when a drone docks, a faint electric hum loop while charging (a block loop driven by the `working` block state, DESIGN.md §7.6), and a "charged" chirp when the drone undocks.
  - **Damage and destruction:** a metallic clank on hurt, replacing the iron golem placeholder (DESIGN.md §2.5), and an electrical fizzle before the destruction explosion.
  - **Low power:** a descending "power-down" chirp when the drone starts RETURNING to charge.
  - **Others:** a short, quiet lock-on beep when a drone starts chasing (useful for drones without a Siren), a short "scanning" sound when it loses its target, a power-down sound on pickup (replacing the vanilla item pickup), Factory crafting sounds, and sounds for the Jammer block and EMP grenade if those are made.
  - **Louder with more upgrades:** `volume = base × (1 + perUpgrade × totalUpgrades)`, capped (e.g. at 2× base). Heavier drones also play at a slightly lower pitch, matching their bigger model (**Drone size by upgrade count**). It applies to the flying loop and the drone's own one-shot sounds. The Siren keeps its own volume rules. The **Quiet upgrade** reduces the result. Values are server config entries.
  - **Categories:** drone sounds use NEUTRAL (as today), and machine sounds use BLOCKS, so players can control them with the vanilla volume sliders.
  - **Subtitles:** every sound gets a subtitle lang entry and a `sounds.json` entry.
  - **Client config:** a client-side config (a new config file) for the flying loop volume and the max number of looping drones (N above). These are personal audio preferences, not gameplay values, so they belong in a client config rather than the server config.

## Items and interaction

- **Drone Remote** (`idea`): a handheld item for controlling drones remotely, e.g. recalling them or sending them to recharge. Specifics TBD. It's direct player interaction, so it checks operator permissions (DESIGN.md §6.3). It may overlap with the v2 drone dashboard (ROADMAP.md).
- **Drone Tracker** (`idea`): a compass-like item that points to a specific drone, e.g. to find one that ran out of energy and dropped as an item. Specifics TBD.

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
