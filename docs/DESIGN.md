# Seeker Drones — Design Document

| | |
|---|---|
| Mod ID | `seekerdrones` |
| Display name | Seeker Drones |
| Java package | `com.elpinho.seekerdrones` |
| Minecraft | 1.21.1 |
| Loader | NeoForge (21.1.x), Java 21 |

> **Scope rule:** Everything in sections 1–10 is **v1 (MVP)** scope. Section 11 (**Future / Out of Scope**) lists ideas that are agreed on but **must not be implemented yet**. Do not build them, stub them, or design v1 code around speculative needs for them unless the user explicitly asks.
>
> All numbers in this document are **placeholder defaults**. Every tunable value must be exposed in the server config (see section 9).

---

## 1. Overview

Seeker Drones adds autonomous flying drones that hunt a configured kind of entity. When a drone spots a target, it chases it. Depending on its upgrades, it then follows, alerts or explodes.

Drones are **mid-game** items and fully upgraded drones are **late-game** items. The mod is built around an automatable pipeline:

```
Drone Factory ──> Drone Programming Station ───> Drone Deploying Station ──> (drone in world)
 (build drone)     (apply upgrades + config)      (auto-deploy)                  │
                                                                                 ▼
                                                             Drone Charging Station (recharge)
```

Every step accepts automation (pipes, hoppers, conveyors). None of the machines require a player to be present.

---

## 2. The Drone

### 2.1 Item vs. entity

- The **Drone item** carries all drone state in a custom data component (`seekerdrones:drone_data`, see section 8.2).
- The **Drone entity** is the deployed, flying form. Converting between item and entity is lossless: energy, health, upgrades, config, operator group, persistent drone ID, label and color are all preserved.
- **Item tooltip:** the first line is `<label> - <drone ID>` (or just the drone ID if there is no label), drawn in the drone's color using the dye's text color (`DyeColor.getTextColor`) so dark colors stay readable. There is no separate color line. The second line is `Owner: <name>` if the drone has an owner (section 6.3), shown even when the drone has a group. Below it the tooltip lists energy, health, installed upgrades (if any) and the number of target entries.

### 2.2 Deploying

| Method | Who | Behavior |
|---|---|---|
| **Shift + right-click** with the drone item (hand-deploy) | Operators of the drone only (section 6.3; anyone for an unowned drone) | Spawns in front of the player (`drone.deploySpawnDistance`) and **inherits the player's velocity**, plus a small throw impulse along the look direction (`drone.deployThrowSpeed`). Deploying fails if the spawn spot is obstructed. If the drone has no owner yet, the deploying player becomes its owner (section 6.3). The velocity is multiplied by `drone.deployDrag` each tick until it drops below `drone.deployRestSpeed`, then the drone comes to rest and hovers. Its rest position is recorded (patrol center fallback, section 3.2). If it spots a target while drifting, it starts chasing immediately, and the position where it spotted the target is recorded as its rest position instead. |
| **Drone Deploying Station** | Anyone / automation (no permission check) | Spawns above the station **with no velocity** and hovers. |

### 2.3 Picking up

- **Shift + right-click** a drone entity with an empty hand. Operators of the drone only (section 6.3).
- The drone becomes an item with all its data and goes into the player's inventory, or drops at their feet if the inventory is full.

### 2.4 Drone GUI

- Operators can open a read-only status screen by right-clicking a drone entity, or by right-clicking (without Shift) while holding a drone item. For an item, the state shows as "Not deployed" and the screen doesn't refresh.
- It shows the drone ID, label, energy, health, installed upgrades, target configuration, current state (idle / patrolling / chasing / following / returning to charge / charging) and, only if the drone has a Patrol upgrade, its patrol center, patrol radius (with the max) and patrol altitude.
- The drone's configuration is **not** editable here. Configuration is done in the Drone Programming Station.

### 2.5 Health and destruction

- Drones have HP. Base HP is configurable and **Health upgrades** increase it.
- Drones take damage from every normal source: players, mobs, projectiles (including other mods' weapons), fire, lava and explosions.
- Drones are immune to fall damage (they don't fall) and to drowning. Instead, while **in water** (not rain) a drone takes `drone.waterDamage` HP every `drone.waterDamageInterval` ticks.
- Taking damage has no mob-style feedback: no knockback (from hits or explosions), no red hurt flash, and a drone-specific damage sound instead of the generic one (placeholder: the iron golem damage sound until a custom sound exists).
- Drones don't take part in entity pushing: they never push other entities (including other drones) and are never pushed by them. This also means they never take entity-cramming damage. (Performance decision, from M3 profiling.)
- Drones do **not** regenerate health on their own. HP is restored only while docked at a Charging Station (section 5.3).
- When HP reaches 0, the drone is **destroyed and lost**. It plays a small explosion effect (particles and sound only, with no damage and no block breaking) and disappears. It leaves no drop, and its upgrades are lost. This applies whether or not it has Explosive upgrades.
- An Explosive drone that **reaches its target** explodes and is **consumed**, leaving no drop. It is a suicide drone.

### 2.6 Base configuration (always present, not tied to an upgrade)

| Setting | Description |
|---|---|
| **Targets** | A list of entries. Each entry has its own kind: an **entity type ID** (e.g. `minecraft:zombie`), an **entity tag** (e.g. `#minecraft:raiders`) or, with Player Seek only, a **player name**. Kinds can be mixed freely within one list. See section 2.7 for how many entries are allowed. |
| **Follow distance** | How far a non-Explosive drone keeps from its target while following it (blocks). |
| **Label** | Optional short text label for identification. It is shown in the drone GUI, in Transmitter messages and as the drone's nameplate. Set by the Programming Station. |
| **Color** | One of the 16 dye colors, which tints part of the drone's model and item. Set by the Programming Station. Defaults to **blue** (the closest dye color to indigo). |

### 2.7 Target slots

- A base drone can have **1** target entry. Each **Multi-target upgrade** adds more slots: `allowedTargets = 1 + multiTargetCount × perUpgrade`, where `perUpgrade` defaults to 1.
- In the Programming Station, the target list the player can edit is sized by the **programmed** Multi-target count, not the count currently installed on the drone. This lets a player configure a drone before it is fully upgraded.
- **Runtime fail-safe:** a flying drone only uses the first `allowedTargets` entries, based on the Multi-target upgrades it **actually** has. It also ignores player-name entries unless it has Player Seek. Entries beyond that are kept in the data but ignored.

### 2.8 Drone ID

- Every drone gets a **persistent, random but readable ID** when the Factory builds it, e.g. `K7F3-Q9MX`.
- The ID is 8 random uppercase letters and digits (A–Z, 0–9), shown with a dash in the middle.
- It never changes, is kept through every item/entity conversion, and is shown in the drone GUI, the item tooltip and Transmitter messages.
- A drone item without an ID (e.g. from the creative tab or `/give`) shows "Unassigned" in its tooltip and gets a new ID the first time it is deployed.

---

## 3. Drone Behavior (AI)

### 3.1 States

```
          ┌──────────── target lost ─────────────┐
          ▼                                      │
 IDLE / PATROLLING ── target spotted ──> CHASING ──> (Explosive) EXPLODE → removed
          │                                 │
          │                                 └──> (non-Explosive) FOLLOWING ── target lost ──┐
          │                                                                                 │
          └──── energy below return threshold (see 5.2) ─────────> RETURNING ──> CHARGING ──┘
                                                                      │               (resumes
                                                     no reachable station /           patrol)
                                                     energy hits 0 ──> drops as item
```

- **IDLE (hover):** A drone without a Patrol upgrade hovers where it is and scans for targets. This is a stationary sentry.
- **PATROLLING:** A drone with at least one Patrol upgrade flies in a circle around its **patrol center** and scans for targets.
- **CHASING:** The drone flies toward the target (section 3.4). An Explosive drone gets faster the closer it gets.
- **FOLLOWING** (non-Explosive only): Once within follow distance, the drone keeps that distance and tracks the target. It holds its current bearing (the horizontal direction from the target to the drone) at the follow distance, `drone.followHeightOffset` blocks above the target's eyes.
  The chasing drone flies toward this follow position. It counts as FOLLOWING once it is within `drone.followEnterDistance` of it, and goes back to CHASING when it is more than `drone.followExitDistance` away (hysteresis, so the state doesn't flip back and forth).
  - **Smoothed target position:** the follow position is measured from a smoothed copy of the target's position, not the exact one. Each tick it moves `drone.followSmoothing` of the way toward the target, so hops, head turns and jitter are filtered out.
  - **Leash:** once the drone reaches the follow position (within `drone.followEnterDistance`), it brakes to a stop and holds still. It only moves again when the follow position is more than `drone.followSlack` away. A target moving around a little doesn't move the drone at all. A target walking away is followed smoothly.
  - **Blocked follow position:** on the staggered tick (and soon after bumping into a block), the drone checks that it fits at the follow position. If not, it tries in order: lower heights (at the target's eye level, then around its chest, e.g. under a ceiling), then half the follow distance (e.g. against a wall), then other bearings around the target (45° steps, nearest first). It keeps the first free spot until the next check. If none is free, it aims for the ideal spot and path finding gets as close as it can.
  - **Facing:** a following drone faces its target loosely. It only turns once it faces more than `drone.facingTolerance` away, then eases into the turn at up to `drone.turnSpeed`.
- **EXPLODE** (Explosive only): An Explosive drone never follows. It chases straight at the center of the target's hitbox and stays CHASING. Once its own center is within `drone.explosionTriggerDistance` of that point, it explodes and is removed. The explosion damages entities but **never breaks blocks** in v1 (a block damage option is future work, section 11). Its power is `basePower + perUpgrade × (count − 1)`.
- **RETURNING / CHARGING:** See section 5.

### 3.2 Patrol center

The patrol center is chosen in this order:
1. The position configured on the Patrol upgrade in the Programming Station. It must be in the same dimension as the drone.
2. Otherwise, where a hand-deployed drone came to rest (or, if it spotted a target while still drifting, where it spotted it).
3. Otherwise, the Deploying Station's position.
4. Otherwise (e.g. a drone spawned by `/summon`), where the drone is when it first needs a patrol center. That position is recorded like a rest position.

The configured center is stored with its dimension. A center in another dimension is ignored and the next fallback applies. The drone's rest position is dimension-bound the same way.

The number of Patrol upgrades sets the **max patrol radius**: `maxPatrolRadius = base + perUpgrade × (count − 1)`. Each drone can also have a configured **patrol radius** (Patrol upgrade config, set by the Programming Station or `/seekerdrones config patrolradius`). The drone patrols at the configured radius capped at the max, or at the max if none is configured. A configured radius above the max is kept in the data, so it takes effect once enough Patrol upgrades are installed.

**Patrol altitude:** each drone can also have a configured **patrol altitude**, an absolute Y level (Patrol upgrade config, set by the Programming Station or `/seekerdrones config patrolaltitude`). The drone patrols at that height, kept within the dimension's build height. Without one, it patrols at the patrol center's height.

**Patrol flight:** the circle is split into evenly spaced waypoints (`upgrades.patrol.waypointSpacing` blocks apart along the circle, at least 8 waypoints) at the patrol height. The drone flies through them in order without stopping, counter-clockwise seen from above, at `upgrades.patrol.speed`. When it starts or resumes patrolling (e.g. after losing a target) it heads for the nearest waypoint. Changing the center, radius or altitude also restarts from the nearest waypoint of the new circle.
- **Climbing over obstacles:** if the drone doesn't fit at a waypoint (a hill, a building, a tree), the waypoint is raised to `upgrades.patrol.climbClearance` above the highest block under the drone (the motion-blocking heightmap). It is never lowered, so the drone climbs over obstacles and never dives into caves or under overhangs.
- **Climbing ahead:** the straight legs to both neighboring waypoints are checked the same way, every 0.5 blocks. A waypoint is raised to the highest climb needed on either leg (if the drone fits there). The drone climbs one waypoint before an obstacle, crosses it level and comes back down to the patrol height one waypoint after it. It never flies into an obstacle's face, and the climbs happen over open ground, so they need no path finding. Inertia (section 3.4) makes these height changes gradual.
- Waypoints that would need to be raised more than `upgrades.patrol.maxClimb`, are in an unloaded chunk or are unreachable by path finding are skipped. If every waypoint is skipped, the drone hovers at the patrol center (at the patrol height). It uses the same straight-line-or-path steering as when chasing (section 3.4).

### 3.3 Detection

- **Sight range** is how far away a drone can first spot a target. It is low by default and increased by **Sight upgrades**.
- A valid target matches one of the drone's **allowed** target entries (section 2.7). Players can only be targeted with the **Player Seek upgrade** (section 4). **The drone's operators (its group, or its owner if it has no group, section 6.3) are never targeted.** Spectators and creative-mode players are ignored.
- **Line of sight is required** unless the drone has the **X-ray upgrade**. Without X-ray, the drone must have a clear ray to the target's eyes (block collision raycast). A drone with X-ray skips the raycast entirely and detects targets through walls within its sight range.
- If several valid targets are visible, the drone picks the **nearest**.
- Targets are **sticky**: while chasing or following, the drone doesn't scan for other targets and never switches to a nearer one. It keeps its target until it loses it (section 3.5).

**Performance (required — there may be dozens of drones):**
- Drones scan for targets every N ticks (default 10), not every tick. Scans are **staggered** using `(tickCount + entityId) % N`, so drones spread the work over different ticks.
- The scan is cheap filtering first, raycast last:
  1. Get candidates with an AABB entity query sized to the sight range.
  2. Filter by target match, then by squared distance to the drone's sight *sphere*.
  3. Sort by distance and raycast **nearest-first**, stopping at the first visible candidate.
  4. Cap the raycasts per scan (default 4).
- While chasing or following, the drone re-checks line of sight at the same staggered interval, not every tick. X-ray drones skip this check.

### 3.4 Chase speed and flight

**Chase speed.** The speed is always capped at `maxSpeed`, and it depends on the kind of drone:
- **Explosive drones** accelerate as the distance to the target shrinks, so they hit hard. The curve is `speed = min(maxSpeed, cruiseSpeed × e^(k × (1 − min(d, sightRange) / sightRange)))`, where `d` is the current distance. `cruiseSpeed`, `maxSpeed` and `k` are configurable. `d` is clamped to the sight range, so the speed never drops below `cruiseSpeed`. Beyond sight range (but still within pursuit range) the drone flies at `cruiseSpeed`.
- **Non-Explosive drones** chase and follow at `min(maxSpeed, cruiseSpeed + targetSpeed)`, where `targetSpeed` is how fast the smoothed target position (section 3.1) moves. They keep up with fast targets without rushing at slow ones.
- `maxSpeed` must stay low enough to avoid clipping through blocks and outrunning chunk loading. The suggested hard ceiling is about 1.5 blocks per tick.

**Inertia.** Drones never jump to a new speed or direction. Each tick the drone works out the velocity it wants, and its actual velocity changes toward it by at most `drone.acceleration` blocks/tick² (`drone.explosiveAcceleration` for an Explosive drone chasing its target, so it can turn fast enough to hit it):
- Toward a spot where it should stop (the follow position, the patrol center when hovering), it brakes in time to stop there. The end of a path counts, but path nodes along the way don't.
- Patrol waypoints and an Explosive drone's target are flown through at full speed.
- While the wanted direction points away from its heading, the drone slows down (to at least 20% speed), so it turns tightly instead of swinging wide past corners and waypoints.
- A drone that stops steering (idle, or after losing its target) drifts to a stop with the drift drag (section 2.2).

**Facing.** A drone turns as a whole. A following drone faces its target (section 3.1). Every other drone faces its direction of travel, with the same tolerance and eased turning.

**Obstacles.** Drones fly using flying-mob navigation (vanilla `FlyingPathNavigation`). They go around obstacles and never pass through blocks. The drone flies straight while its whole box can fly the line to the goal (rays from the center and the corners of its box), and follows a path otherwise. Bumping into a block triggers a new check, at most every 5 ticks.

### 3.5 Losing the target

The drone loses its target when any of these happen:
- The target dies, despawns or changes dimension.
- The target moves beyond the **pursuit range** (`sightRange × pursuitMultiplier`, default 2×).
- Line of sight is lost continuously for longer than the **lost-sight timeout** (default 5 s). This never happens to X-ray drones.

After losing the target, a drone with a Patrol upgrade goes back to patrolling. A drone without one stops and hovers where it is.

### 3.6 Chunk loading

- **Drones only operate in loaded chunks.** A drone in an unloaded chunk freezes like any vanilla entity and resumes when the chunk loads again.
- There is **no** chunk loading in v1, not even behind a config option.

---

## 4. Upgrades

- Upgrades are craftable items with expensive recipes. The materials are still to be decided (TBD), and the recipes are plain data-driven crafting recipes.
- Each drone has a **total upgrade slot limit** and a **per-type cap**. Both are configurable.
- Upgrades are installed and removed only in the **Drone Programming Station**. Removal gives a **full refund** of the upgrade item.

| Upgrade | Stacks | Default cap | Effect | Per-upgrade config (set in Programming Station) |
|---|---|---|---|---|
| **Patrol** | Yes | 4 | Enables patrolling. Each extra upgrade increases the max patrol radius. | Patrol center (x, y, z), patrol radius (capped by the upgrade count) and patrol altitude (section 3.2) |
| **Sight** | Yes | 8 | Increases sight (detection) range. | — |
| **Explosive** | Yes | 4 | The drone explodes on reaching its target and is consumed. Explosion power scales with the count. | — |
| **Siren** | Yes | 3 | Plays a siren sound when a target is spotted. More upgrades increase the audible radius (sound volume > 1.0). | — |
| **Transmitter** | No | 1 | Sends a chat message to **all online operators** of the drone (its group, or its owner if it has no group) when a target is spotted, including the drone ID, label, target type and coordinates. The message is rate-limited per drone. | — |
| **Energy** | Yes | 4 | Increases max energy (FE). | — |
| **Health** | Yes | 4 | Increases max HP. | — |
| **Player Seek** | No | 1 | Allows player names as target entries. Player-name entries use target slots like any other entry. The drone's operators (section 6.3) are still exempt. | — (names go in the Targets list) |
| **Multi-target** | Yes | 3 | Each upgrade adds target slots (section 2.7). | — |
| **X-ray** | No | 1 | Detection and tracking ignore line of sight, so targets are found through walls (section 3.3). | — |

- The default **total slot limit** is 24.
- The caps are enforced when upgrades are installed (Programming Station, debug command). A flying drone uses its installed counts as they are, even if the config was lowered afterwards.
- **Energy and Health upgrades arrive full:** installing one also adds the extra capacity to the drone's current energy / HP. Removing one lowers the max, and anything above the new max is lost.
- The Siren fires once per target acquisition and repeats every N seconds (configurable) while the target is being chased or followed. Its volume is `baseVolume + perUpgrade × (count − 1)`, and vanilla hears a sound of volume `v > 1` from `16 × v` blocks. Placeholder sound: the vanilla raid horn, until a custom sound exists.
- The Transmitter fires on target acquisition only (not while following). The message gives the drone's label and ID, the target's name (the player name for players, otherwise the entity type's name) and the target's block coordinates. After a message, that drone sends no other message for `upgrades.transmitter.cooldown` ticks. The cooldown isn't saved. It goes to the drone's online operators (section 6.3): the group's, or the owner if the drone has no group. An unowned drone or one with an unknown group notifies nobody.
- Transmitter messages sent while operators are offline are **not** queued in v1.
- **Upgrade items:** one item per type, `seekerdrones:<type>_upgrade` (e.g. `seekerdrones:multi_target_upgrade`), with placeholder recipes until the final ones (section 11).

---

## 5. Energy

### 5.1 Consumption

- Drones store **FE**. The drain is applied every N ticks (default 20) to keep it cheap:
  - **Distance cost:** FE per block flown, which gives the drone its "range".
  - **Hover cost:** a small FE per tick while airborne.
- Energy upgrades increase max FE.

### 5.2 Returning to charge

- The **return threshold** is dynamic: the energy needed to reach the nearest usable Charging Station and wait there for a while, which is `distance × FE-per-block × safetyMargin + returnWaitBuffer × hoverFE-per-tick` (defaults 1.25 and 600 ticks). The wait buffer lets a drone queue at a busy station (section 5.3) without running dry.
- The drone looks for stations within a fixed radius (default 500 blocks) using the station registry (section 8.3), not a block scan.
- A **usable** station is in the same dimension, and its placer is an operator of the drone (section 6.3): an operator of the drone's group, or the drone's owner if it has no group. An **unowned** drone may use **any** station. A station with no recorded placer (placed by a non-player) is usable only by unowned drones.
- Once the threshold is reached, returning overrides every other state, including chasing, **except** for a drone with an **Explosive** upgrade that is currently chasing. That drone keeps chasing, since it will be consumed anyway. If an Explosive drone loses its target while below the threshold, it returns to charge as normal.
- A returning drone doesn't scan for targets and flies at `drone.cruiseSpeed`. A station farther than 32 blocks is approached in legs of 32 blocks, raised over obstacles like patrol waypoints (section 3.2), so path finding stays short.
- If the chosen station turns out to be **unreachable** (path finding can't reach it, or the drone makes no progress toward it), the drone skips that station for `drone.unreachableStationCooldown` ticks and picks the next-nearest usable one.
- If no usable station is in range (or all are being skipped), the drone carries on normally until its energy hits 0. It then **drops as an item** with its data intact.

### 5.3 Charging queue

- Each Charging Station charges **one drone at a time**. A drone claims the station once it is within a few blocks of it and renews the claim every tick until it is done. A claim that isn't renewed (the drone was picked up, destroyed or unloaded) lapses after a second.
- If the chosen station is busy, the drone checks for another free usable station within 10 blocks of it and goes there. If there is none, it waits by hovering very close to the busy station.
- While docked, the drone also regains `chargingStation.healPerTick` HP per tick, up to its max HP. Healing costs no FE, but only happens while the station has FE stored. Charging finishes when the drone is at full energy **and** full health.
- A docked drone has no hover drain. If the station runs out of FE, the drone stays docked and waits, charging again as soon as FE arrives.
- When charging finishes, the drone returns to its patrol center and resumes patrolling, or hovers if it has no Patrol upgrade. A drone without a Patrol upgrade returns to where it was when it left.

---

## 6. Drone Operators

Drone Operators control who can interact with drones. A drone's operators come from its Operator Group, or from its owner if it has no group (section 6.3).

### 6.1 Operator Groups

- Each **Drone Factory** owns one **Operator Group**. The group is stored in world saved data (section 8.3) under a unique group ID.
- The **group owner** is the player who first placed the Factory. The owner is always an operator.
- Only the **group owner** can edit the operator list, from the Factory GUI. Operators are added by player name, which is resolved to a UUID, and stored by UUID.
- Every drone built by a Factory records that Factory's **group ID**. Permissions are checked against the group's **current** membership, so changes to the list apply at once to all existing drones, including revoking access.
- Breaking the Factory **does not delete the group**. The Factory item keeps the group ID in a data component, so placing it again reconnects to the same group.
- Ownership transfer is out of scope for v1.

### 6.2 Operator permissions

| Action | Requires operator? |
|---|---|
| Pick up a drone (Shift + right-click) | Yes |
| Hand-deploy a drone | Yes |
| Open the drone status GUI | Yes |
| Receive Transmitter notifications | Yes (all online operators) |
| Exempt from being targeted by Player Seek | Yes |
| Insert/extract drones in machines (manually or by automation) | **No**. Machines never check permissions, so automation keeps working. |
| Drone may charge at a Charging Station | The station's placer must be an operator of the drone (section 6.3). An unowned drone may charge at any station (section 5.2). |

Edge cases for the direct-interaction checks (pick up, hand-deploy, status GUI):
- An **unowned** drone (no group and no owner, e.g. fresh from the creative tab or `/give`) may be used by **anyone**. The first player to hand-deploy it becomes its owner.
- A drone whose group ID **doesn't exist** in saved data (foreign or wiped data) has no operators: **nobody** may interact with it, except via the bypass below. Its owner field doesn't help, since a group ID is present.
- **Server operators** (permission level ≥ 2) bypass the operator check for these three actions only. The bypass does **not** make them exempt from Player Seek and does **not** make them receive Transmitter messages. Those stay operator-only.
- Transmitter messages and the Player Seek exemption go to the operators from section 6.3. An unowned drone notifies nobody and exempts nobody.
- A blocked player gets an action-bar message saying they are not an operator of this drone.

The Programming, Deploying and Charging Stations have no access control in v1. Charging Stations only record their placer's UUID, which is used for the usability rule above.

### 6.3 Drone owner

Not every player should need a group: a solo player's creative, `/give` or otherwise groupless drones would otherwise have no operators, and creating a group per player doesn't scale on large servers. So each drone also stores an **owner**.

- The owner is the player who **first hand-deploys** the drone. It is set only if the drone has no owner yet, and is then kept through every item/entity conversion. The Deploying Station never sets it.
- The drone's **operators** are decided like this:
  1. With a group ID: the group's current operators (section 6.1). The owner field is **ignored**, so removing someone from the group still revokes their access.
  2. With no group but an owner: the owner is the **only** operator.
  3. With neither: the drone is **unowned** and has no operators.
- Every "requires operator" rule in section 6.2 uses this definition.

---

## 7. Machines

All machines accept energy through the NeoForge `IEnergyStorage` capability, items through `IItemHandler`, and (where relevant) fluids through `IFluidHandler`, on every side.

### 7.1 Drone Factory

- v1 is a **single block**.
- It consumes **items + fluid + FE** over a processing time to build one Drone.
- Recipes use a custom, data-driven recipe type `seekerdrones:drone_assembly`, which defines item ingredients, a fluid ingredient and amount, total FE and processing time. The materials and the fluid are TBD.
- The output drone is **fully charged** at base capacity, has no upgrades and has default config. It is linked to this Factory's Operator Group and gets a new persistent drone ID.
- The GUI has input slots, a fluid tank, an energy bar, progress, an output slot and an **Operator list** tab (owner only).

### 7.2 Drone Programming Station

- **Slots:** one drone slot, an upgrade input inventory, and a refund output buffer for removed upgrades.
- **Program:** the station stores a desired configuration:
  - the target count for each upgrade type,
  - per-upgrade config (patrol center, patrol radius, patrol altitude),
  - base config: the targets list (sized by the programmed Multi-target count, section 2.7), follow distance, **Label**, and **Color** (a button that cycles through the 16 dye colors on each click).
- **Operation:** with a drone in the slot, the station works toward the program one step at a time:
  - It installs a missing upgrade from the input inventory, paying FE.
  - It writes the configured settings (targets, follow distance, patrol center, patrol radius, patrol altitude, label, color) onto the drone.
  - If required upgrades are missing from the input inventory, it waits.
- **Manual removal (v1):** a player can remove installed upgrades one at a time from the GUI. Removed upgrades go into the refund buffer (a full refund), and removal costs no FE. The station does **not** automatically remove upgrades beyond the program in v1. A drone with more upgrades than the program asks for doesn't match, so it is never auto-output, and the GUI shows a warning.
- **Refund buffer output:** the refund buffer can be set to push into an adjacent inventory on one configured face. It can also always be extracted through the item capability.
- **FE cost per upgrade installed:**
  - The base cost is `baseCost[type] × n`, where `n` is the index of the upgrade being installed within its type (1st, 2nd…). The scaling is configurable.
  - Installing an **Energy** upgrade also costs the FE capacity that the upgrade adds. That FE goes into the drone, so the new capacity arrives full.
  - Removing an Energy upgrade lowers capacity, and any charge above the new capacity is lost.
- **Output mode:**
  - **Manual** (default): the drone stays in the slot and a player takes it out.
  - **Auto-output when complete:** once the drone matches the program exactly, the station pushes it into an adjacent inventory on configured faces. Until then it is never output.
- A drone that would exceed the slot or per-type caps under the program is invalid, and the GUI must prevent saving that program.

### 7.3 Drone Deploying Station

- It has one drone input slot and an **auto-deploy** toggle.
- With auto-deploy on, a drone inserted by a player or automation is deployed above the station, stationary, if the space is clear. With auto-deploy off, a GUI button deploys it manually.
- It deploys drones as they are and does not charge them.
- It **uses FE per deploy** (a configurable amount) and won't deploy without enough stored FE.

### 7.4 Drone Charging Station

- It accepts FE and charges one docked drone at a time at a configurable FE/tick rate. It also restores the docked drone's HP (section 5.3).
- It records its placer's UUID and registers itself in the station registry on placement. It unregisters when broken.
- Drone docking and queuing follow section 5.3.

---

## 8. Technical Notes

### 8.1 Registration

- Use NeoForge `DeferredRegister` for blocks, items, block entities, entity types, menus, recipe types and serializers, data component types, sounds and creative tabs.
- Capabilities are registered through `RegisterCapabilitiesEvent`.

### 8.2 Drone data component (`seekerdrones:drone_data`)

The component is a record with a `Codec` and a `StreamCodec`, holding:
- `droneId` (string): the persistent, readable ID from section 2.8. It survives item/entity conversion and is also meant for future features such as a map or the Camera upgrade.
- `groupId` (optional UUID): the Operator Group. Empty on drones that weren't built by a Factory (e.g. creative/`/give`). See section 6.2 and 6.3 for how permission checks treat a drone without a group or with an unknown group.
- `ownerId` (optional UUID): the player who first hand-deployed the drone (section 6.3). Only used while the drone has no group.
- `ownerName` (string, empty if unknown): the owner's name, saved when ownership is set and refreshed when the owner redeploys the drone. It's only for the tooltip, because clients can't resolve offline players' names.
- `energy` (int). Max energy is derived from the upgrades and config, not stored.
- `health` (float). Max health is derived.
- `upgrades` (map of upgrade type to count).
- `config`: targets (a list of entries, each with its own kind: entity type / tag / player name), follow distance, patrol center (optional, position plus dimension), patrol radius (optional), patrol altitude (optional Y level), label, color.

The drone entity saves the same data in its entity NBT. Only the fields the client needs (e.g. status for the GUI and renderer) are synced.

### 8.3 Saved data

- **Operator Groups:** global `SavedData` stored on the overworld, mapping group ID to owner UUID and the operator UUID set.
- **Charging Station registry:** a `SavedData` per dimension, mapping block position to owner UUID. The nearest-station search iterates this registry instead of scanning blocks.

### 8.4 Performance guidelines

- Target scans are staggered, AABB-first and raycast-last (section 3.3).
- Energy drain is batched every N ticks.
- The station search goes through the registry and should be cached briefly (don't query it every tick).
- Entity sync to clients is kept minimal.
- Path finding is the most expensive part of a drone's tick. Drones fly straight whenever the line to the goal is clear for their whole box (9 raycasts, on the staggered tick or after a bump), and only path find when it isn't. A moving goal (a target) is re-pathed on the staggered tick. A fixed goal (a patrol waypoint) is only re-pathed when it changes or the current path has ended.
- Profiled in M5 with 100 drones: the energy drain and return-threshold check add no measurable cost (patrolling over open ground 9.8 µs per drone per tick, idle 3.0 µs). The `charging` scenario (100 drones going low at the same moment, 25 stations) costs about 13 µs per drone. Without a wait buffer 75 of 100 drones ran dry while queuing. With `drone.returnWaitBuffer` at 600 ticks, 84 survived. The rest were in lines longer than the buffer covers at the busiest stations, which only happens when every drone goes low at once.
- Re-profiled after the smooth-flight rework (inertia, box clear-path check, climbing ahead over patrol obstacles): patrolling over open ground about 7–10 µs per drone per tick, patrolling through 7-high walls about 24 µs (climbing ahead removes most path searches), following over open ground about 7 µs and through walls about 13 µs.
- Profiled in M4 with 100 drones (`scripts/profile-drones.ps1`): patrolling over open ground costs about 7 µs per drone per tick. Patrolling through 7-high walls that cross every circle costs about 45 µs, almost all of it path finding. Each leg that crosses a wall needs a real search over the wall, about 1 ms each, compared with about 0.2 ms for follow paths.

---

## 9. Configuration (server config)

All values below are placeholders.

| Key | Default | Notes |
|---|---|---|
| `drone.baseMaxEnergy` | 100 000 FE | |
| `drone.energyPerBlock` | 20 FE | Distance cost |
| `drone.hoverEnergyPerTick` | 1 FE | |
| `drone.energyDrainInterval` | 20 ticks | |
| `drone.returnSafetyMargin` | 1.25 | |
| `drone.returnWaitBuffer` | 600 ticks | Hover time added to the return threshold, for waiting at a busy station (section 5.2) |
| `drone.chargingSearchRadius` | 500 blocks | |
| `drone.chargingAlternateRadius` | 10 blocks | Alternate free station search |
| `drone.unreachableStationCooldown` | 1200 ticks | How long an unreachable station is skipped (section 5.2) |
| `drone.baseMaxHealth` | 20 | |
| `drone.baseSightRange` | 8 blocks | |
| `drone.pursuitMultiplier` | 2.0 | |
| `drone.lostSightTimeout` | 100 ticks | |
| `drone.scanInterval` | 10 ticks | |
| `drone.maxRaycastsPerScan` | 4 | |
| `drone.cruiseSpeed` | 0.4 blocks/tick | |
| `drone.maxSpeed` | 1.2 blocks/tick | Hard ceiling of about 1.5 |
| `drone.chaseAccelerationK` | 2.0 | Explosive chase speed curve (section 3.4) |
| `drone.acceleration` | 0.04 blocks/tick² | Max change in velocity per tick (inertia, section 3.4) |
| `drone.explosiveAcceleration` | 0.15 blocks/tick² | Same, for an Explosive drone chasing its target |
| `drone.turnSpeed` | 12 °/tick | Max turn rate of the drone's facing |
| `drone.facingTolerance` | 20° | How far the drone may face away from where it wants to face before it turns |
| `drone.defaultFollowDistance` | 4 blocks | |
| `drone.followHeightOffset` | 1.5 blocks | Height above the target's eyes while following |
| `drone.followEnterDistance` | 1.0 block | Distance to the follow position at which a chasing drone starts following |
| `drone.followExitDistance` | 3.0 blocks | Distance to the follow position at which a following drone goes back to chasing; kept at least `followEnterDistance` |
| `drone.followSlack` | 2.0 blocks | How far the follow position may move from a settled drone before it moves again (leash, section 3.1); kept at least `followEnterDistance` |
| `drone.followSmoothing` | 0.15 | Fraction of the way the smoothed target position moves toward the target each tick (section 3.1) |
| `drone.repathDistance` | 1.0 block | Path recompute threshold between staggered ticks (section 3.4) |
| `drone.explosionTriggerDistance` | 1.5 blocks | |
| `drone.deploySpawnDistance` | 1.0 block | Distance in front of the player's eyes |
| `drone.deployThrowSpeed` | 0.15 blocks/tick | Added along the look direction on hand-deploy |
| `drone.deployDrag` | 0.9 | Velocity multiplier per tick while drifting |
| `drone.deployRestSpeed` | 0.01 blocks/tick | Below this speed the drone comes to rest |
| `drone.waterDamage` | 1 HP | |
| `drone.waterDamageInterval` | 20 ticks | |
| `upgrades.totalSlots` | 24 | |
| `upgrades.<type>.maxCount` | see section 4 | |
| `upgrades.patrol.baseRadius` / `perUpgrade` | 16 / 16 blocks | |
| `upgrades.patrol.speed` | 0.25 blocks/tick | Patrol flight speed |
| `upgrades.patrol.waypointSpacing` | 8 blocks | Distance between patrol waypoints along the circle (at least 8 waypoints) |
| `upgrades.patrol.maxClimb` | 16 blocks | How far above the patrol height a waypoint may be raised to clear an obstacle; beyond that it is skipped |
| `upgrades.patrol.climbClearance` | 1.0 block | Gap kept above the obstacle under a raised waypoint |
| `upgrades.sight.perUpgrade` | 8 blocks | |
| `upgrades.explosive.basePower` / `perUpgrade` | 2.0 / 1.0 | TNT = 4.0 |
| `upgrades.siren.baseVolume` / `perUpgrade` / `repeatInterval` | 2.0 / 2.0 / 100 ticks | |
| `upgrades.transmitter.cooldown` | 200 ticks | |
| `upgrades.energy.perUpgrade` | 100 000 FE | |
| `upgrades.health.perUpgrade` | 10 | |
| `upgrades.multiTarget.perUpgrade` | 1 | Extra target slots per upgrade |
| `programmingStation.baseCost.<type>` | 10 000 FE | Multiplied by index `n` |
| `chargingStation.capacity` | 100 000 FE | FE the station can store |
| `chargingStation.chargeRate` | 1 000 FE/tick | |
| `chargingStation.healPerTick` | 0.1 HP/tick | |
| `deployingStation.energyPerDeploy` | 5 000 FE | |

---

## 10. Implementation Order

See [`ROADMAP.md`](ROADMAP.md) for milestones M0–M8 (v1) and the post-v1 outlook.

---

## 11. Future / Out of Scope — DO NOT IMPLEMENT YET

These are agreed ideas for later versions. **Do not implement, stub or scaffold them** unless the user explicitly asks.

- **Drone Factory 3×3×3 multiblock** with a central build animation (replaces the single block).
- **Camera upgrade:** view through a drone's POV (operators only).
- **Drone dashboard:** remote screen with drone POV and a **map of drone positions**.
- **Transmitter notification queue:** deliver notifications to operators who were offline, when they log in.
- **Server-safety configs:** explosion block damage toggle / respect for `mobGriefing`, global toggle for targeting players, max drones per player.
- **Chunk loading** by drones (in any form, including behind a config).
- **Target Tagger** item to mark one specific entity as a target.
- **Operator Group ownership transfer.**
- **Automatic upgrade removal** by the Programming Station program (removing upgrades beyond the programmed counts). v1 only supports manual removal.
- Charging Stations that charge more than one drone at a time.
- Final upgrade/factory recipes and materials (TBD, balancing pass).
