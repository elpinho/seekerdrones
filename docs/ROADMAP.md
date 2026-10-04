# Seeker Drones — Roadmap

This roadmap is broad on purpose. The behavior spec is in [`DESIGN.md`](DESIGN.md).

> **Scope rule:** Milestones **M0–M9** make up **v1**. The **Post-v1** section is only an outlook. **Do not implement anything from it** unless the user explicitly asks (see DESIGN.md §11).

Each milestone ends in something playable or testable. The drone and its AI come before the machines so gameplay can be tested and tuned early, using debug commands in place of the machines. The debug commands (`/seekerdrones ...`, permission level 2) are never removed: they stay as admin/testing tools after the machines exist. The Factory comes before the Programming Station because the Programming Station needs drones as input.

---

## v1 (MVP)

### M0 — Project setup
- NeoForge 1.21.1 MDK, Gradle, mod ID `seekerdrones`, package `com.elpinho.seekerdrones`, `git init`.
- Registry skeleton (`DeferredRegister`s), server config (`ModConfigSpec`), creative tab.
- Data generation working: lang, models, loot tables, recipes.
- **Done when:** the mod loads in a dev client and a dedicated server, and the config file is generated.

### M1 — Drone core
- `seekerdrones:drone_data` component: readable drone ID, energy, health, upgrades, config, group ID, label, color.
- Drone item (with tooltip) and Drone entity (placeholder model).
- Hand-deploy with inherited velocity and drag, hover, and Shift+right-click pickup.
- Health, destruction (cosmetic explosion, no drop), and the read-only status GUI.
- **Done when:** you can throw a drone, it drifts to a stop, and picking it up keeps all its data.

### M2 — Operators
- Operator Group saved data, with permission checks on pickup, hand-deploy and GUI.
- A debug command (`/seekerdrones group`, permission level 2) to create, edit and assign groups. It stays after the Factory exists (M6).
- **Done when:** non-operators are blocked from direct interaction with drones.

### M3 — Seeking AI
- Targets (entity types, tags and player names) and the allowed-target fail-safe.
- Staggered detection with line of sight.
- The detection effects of the Sight and Player Seek upgrades, read from the drone's upgrade counts. The fail-safe needs these counts, so they come in with the AI. Their items come in M4.
- Chase speed curve, following at a set distance, losing the target, idle hover.
- A debug command (`/seekerdrones config`, permission level 2) to set targets and follow distance on a held drone or on drone entities. It stays after the Programming Station exists (M7) as an admin/testing tool.
- **Done when:** a hand-deployed drone finds a zombie and follows it, and dozens of drones don't noticeably hurt TPS (profiled).

### M4 — Upgrades
- All upgrade items (placeholder recipes): Patrol, Sight, Explosive, Siren, Transmitter, Energy, Health, Player Seek. (Multi-target and X-ray existed until they were removed, DESIGN.md section 4.)
- The remaining upgrade effects: Patrol, Explosive, Siren and Transmitter. Sight and Player Seek already work from M3.
- Total slot limit and per-type caps.
- A debug command (`/seekerdrones upgrade`, permission level 2) to install and remove upgrades on a held drone or on drone entities, respecting the caps. It stays after the Programming Station exists (M7) as an admin/testing tool.
- **Done when:** every upgrade's effect can be tested in game.

### M5 — Energy and charging
- Energy drain (per block flown and while hovering), batched.
- Drone Charging Station block and the charging station registry.
- Dynamic return threshold (including the Explosive exception), charging queue, and dropping as an item at 0 energy.
- A debug command (`/seekerdrones energy`, permission level 2) to read and set energy on a held drone or on drone entities. It stays as an admin/testing tool.
- **Done when:** a patrolling drone runs low, charges and resumes its patrol on its own.

### M6 — Drone Factory
- Single-block machine taking items, fluid and FE, with the `seekerdrones:drone_assembly` recipe type.
- Intermediate components (Drone Rotor, Seeker Core) and the Factory block, with exclusive base / Mekanism recipe variants (DESIGN.md §7.5).
- Charging Station repair fluid: a tank taking Lava, or Ethene with Mekanism, that healing now consumes (DESIGN.md §5.3, §7.4).
- Output drones are fully charged, get a new drone ID and are linked to the Factory's Operator Group.
- The GUI has an Operator list tab (owner only), and the Factory item keeps its group ID when broken.
- The M2 debug group command stays as an admin/testing tool.
- **Done when:** a Factory builds drones in survival and operator list changes apply to existing drones.

### M7 — Drone Programming Station
- Program editing: upgrade counts, targets (one row per target slot), patrol center, follow distance, label and color.
- Upgrades installed one step at a time with FE costs, including the Energy upgrade's extra fill.
- Direct mode (edit the drone in the slot by hand) and Template mode (bring every inserted drone to a stored program).
- Manual upgrade removal, refunded into the player's inventory.
- Automation can pull the drone out once it matches the template.
- The M4 debug upgrade command stays as an admin/testing tool.
- **Done when:** a drone fed in by automation comes out programmed and upgraded exactly as configured.

### M8 — Deploying Station and full pipeline
- Drone Deploying Station with auto-deploy (or a Deploy button and redstone pulse when it's off), a small upward launch and FE per deploy.
- End-to-end test: Factory → Programming Station → Deploying Station through pipes, with no player involved.
- **Done when:** the full automated pipeline works in survival.

### M9 — Art and polish
- Commission an artist (paid) to remake the textures of every block, item and GUI.
- Remake the drone's 3D model and textures (same artist), including animations (e.g. rotors, idle and chase states).
- Sounds, lang, and a basic balance pass on the config defaults.
- Machine particles are done (DESIGN.md section 7.6). Tell the artist the machines have a `working` block state, so the textures can include lit "on" variants.
- Give the artist the GUI kit and texture style rules (DESIGN.md section 7.7) and the machine faces (section 7.6). The layouts and faces are final, so only the art changes.
- **Done when:** the commissioned art is in the game, and **v1.0** is tagged.

---

## Post-v1 — outlook only, DO NOT IMPLEMENT YET

- **v1.x — Quality of life:** final recipes and balance, server-safety configs, queued Transmitter notifications, charging stations for multiple drones.
- **v2 — Visibility:** Camera upgrade, drone dashboard with POV and a map of drone positions.
- **v3 — Spectacle and depth:** 3×3×3 Drone Factory multiblock with animation, Target Tagger, Operator Group ownership transfer, and chunk loading (to be decided).
