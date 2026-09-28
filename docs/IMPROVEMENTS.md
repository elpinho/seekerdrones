# Seeker Drones — Improvements Backlog

Ideas and refinements that are **not tied to a roadmap milestone**. This is a backlog, not a spec:

- Nothing here is v1 scope. Do not implement anything from this file unless the user explicitly asks.
- Items marked `idea` are undecided and may change or be dropped.
- Once an item is decided, its behavior goes into `DESIGN.md` (and a milestone in `ROADMAP.md` if needed), and the item here is marked `decided`. Once it's implemented, the item is **removed** from this file (`DESIGN.md` is the record).
- Items that are agreed but deliberately postponed belong in `DESIGN.md` section 11 instead.

Status: `idea` | `decided`

---

## Drone behavior

- **Shared target claim** (`idea`): if one drone is already tracking an entity, other drones ignore it. Needs a decision on what "tracking" means (chasing only, or also patrol-detected) and on what happens when the claiming drone loses the target or is destroyed. Touches the scan batching rules in DESIGN.md §3.3, so it must stay cheap with dozens of drones.
- **Increase base sight range** (`idea`): the default `drone.baseSightRange` (8 blocks, DESIGN.md §9) may be too short. Raising it also raises the pursuit range (`sightRange × pursuitMultiplier`) and the Explosive chase speed curve (§3.4), and makes the AABB scan query larger (§3.3), so check the profiling numbers afterwards.
- **Drones targeting other drones** (`idea`): drones could target enemy drones (drones of another owner or Operator Group). Needs decisions on what counts as "enemy", how damage works and how this interacts with the target slots.
- **Something that targets drones** (`idea`): a new item, block or mechanic that hunts or counters drones. To be designed. It may overlap with the item above.

## Upgrades and energy

- **Tune energy usage** (`idea`): rebalance the drone's energy config (`drone.energyPerBlock`, `drone.hoverEnergyPerTick`, `drone.baseMaxEnergy`, `upgrades.energy.perUpgrade`, DESIGN.md §5.1 and §9) so a drone's range and hover time feel right. Overlaps with the M8 balance pass (ROADMAP.md).
- **Tune upgrade recipes** (`idea`): replace the placeholder upgrade recipes with final ones (materials and costs). This is listed as future work in DESIGN.md §11 (final recipes, balancing pass), so it needs the user's go-ahead before it becomes v1 work. It should follow §7.5: `c:` tags, and base and Mekanism variants where it makes sense.
- **Solar upgrade** (`idea`): a drone upgrade that generates energy from sunlight. Needs decisions on the conditions (daylight, sky access, weather), the rate and the interaction with the energy batching in DESIGN.md §8.4. All values would be config entries.
- **Charging Station speed upgrades** (`idea`): let Charging Stations accept upgrades that raise their charge rate. Needs a decision on how upgrades are inserted, what the cap is and how it relates to the queue (DESIGN.md §5.3).

## Drone GUI and visuals

- **Improve the Drone Factory GUI** (`idea`): rework the Factory screen (DESIGN.md §7.1), taking inspiration from Industrial Foregoing's machines (e.g. how they lay out the energy bar, fluid tanks, progress and side tabs). Which elements to borrow is still open. The Operator list tab must stay.
- **Increase drone size** (`idea`): make drones bigger. Undecided whether that means a larger base size, or a drone that grows with its upgrade count. The size affects more than the model: the hitbox, path finding (the flying node size), the box clear-path raycasts (§3.4 and §8.4), fitting at follow and patrol positions, and deploy obstruction checks. A size that changes with upgrades would also need to update the entity's dimensions whenever upgrades change.
- **Drone animations** (`idea`): animate the drone entity (for example rotors and idle or chase states).

## Items and interaction

- **Creative-mode deploying** (`idea`): when a drone is deployed in creative mode, it possibly should disappear from the hand like in survival. Currently unclear whether this is wanted, so it needs a decision.

## Mod compatibility

- **Defuse Industrial Foregoing's Infinity Nuke** (`idea`): drones should be able to defuse the Infinity Nuke somehow. Everything is open: how the drone detects it, what "defusing" means and whether it needs an upgrade. This would be an optional integration, so the mod must keep working when Industrial Foregoing is absent. Mekanism is currently the only optional integration (DESIGN.md §7.5).
- **ComputerCraft / CC: Tweaked integration** (`idea`): let computers interact with drones and/or the machines, for example through a peripheral. Scope is open: what can be read (drone state, energy, targets), what can be controlled and whether it needs its own permission rules. It would be an optional integration, so the mod must keep working without CC: Tweaked. Note that machines never check operator permissions (`CLAUDE.md`), so any control API needs an explicit decision on this.
