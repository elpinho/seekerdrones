# Seeker Drones (Minecraft 1.21.1, NeoForge)

Mod ID `seekerdrones`, package `com.elpinho.seekerdrones`, Java 21.

- The design spec is in `docs/DESIGN.md`. Read it before implementing anything.
- The implementation order is in `docs/ROADMAP.md` (milestones M0–M9 make up v1). Work milestone by milestone.
- Only implement **v1 scope** (DESIGN.md sections 1–10). Section 11, "Future / Out of Scope", must **not** be implemented, stubbed or scaffolded unless the user explicitly asks.
- All numeric values are placeholders and must be exposed in the server config, not hardcoded.
- Performance matters: expect dozens of drones. Follow the scan and energy batching rules in DESIGN.md §3.3 and §8.4.
- Machines never check operator permissions (automation must keep working). Only direct player interaction with drones does.
- If a design question isn't answered in DESIGN.md, ask the user instead of inventing behavior, and update DESIGN.md with the decision.
- To boot a dev client/server for testing, use `scripts/smoke-test-server.ps1` rather than running `gradlew runServer`/`runClient` and piping `stop` to it. Gradle doesn't forward stdin to the forked game JVM, and killing the gradlew process tree on Windows doesn't kill the detached child either — both leave an orphaned game process running. The script waits for a boot marker and then kills the actual java process. It passes a unique `-PdevRunTag` that `build.gradle` puts on the game JVM's command line, and kills only the process with that tag, so a client the user is playtesting in (or any other game) is left alone. The profiling script works the same way.
- To apply resource changes to a running `runClient` without restarting, run `scripts/reload-resources.ps1` (`-Datagen` reruns the data generators first, `-Watch` rebuilds when files change), then press F3+T for assets or run `/reload` for data. Java changes still need a restart.
- Mekanism is an optional dev-only dependency (DESIGN.md §7.5): add `-PwithMekanism` to a Gradle run, or `-GradleArgs '-PwithMekanism'` to the smoke-test script, to boot with it and test the Mekanism recipe variants.
- To check TPS impact, run `scripts/profile-drones.ps1` (options: `-Drones`, `-Targets`, `-Scenarios`). It boots the dev server on a throwaway flat world, drives scenarios over RCON, and writes a report to `build/profiling/`. It restores `runs/server/server.properties` afterwards. Pass several scenarios through `powershell -Command "& ./scripts/profile-drones.ps1 -Scenarios a,b,c"`: with `powershell -File` the comma list arrives as one string and is rejected as an unknown scenario.
- The dev runs keep generated `runs/*/config/seekerdrones-server.toml` files, which override changed config defaults. When changing a default, update the value in those files too (they're git-ignored).
- In-game behavior is tested with NeoForge GameTests, added milestone by milestone. Don't write or run them in the main session: after implementing gameplay logic, spawn the `gametest-runner` subagent (`.claude/agents/gametest-runner.md`, runs on Sonnet to save cost) with the behaviors to verify and the DESIGN.md sections they come from. Fix any mod bugs it reports in the main session. The GameTest server can run a subset of the tests, repeated: `-PgametestFilter=<regex>` and `-PgametestRepeat=<N>` (see `GameTestSelection`).
