# Seeker Drones (Minecraft 1.21.1, NeoForge)

Mod ID `seekerdrones`, package `com.elpinho.seekerdrones`, Java 21.

- The design spec is in `docs/DESIGN.md`. Read it before implementing anything.
- The implementation order is in `docs/ROADMAP.md` (milestones M0–M8 make up v1). Work milestone by milestone.
- Only implement **v1 scope** (DESIGN.md sections 1–10). Section 11, "Future / Out of Scope", must **not** be implemented, stubbed or scaffolded unless the user explicitly asks.
- All numeric values are placeholders and must be exposed in the server config, not hardcoded.
- Performance matters: expect dozens of drones. Follow the scan and energy batching rules in DESIGN.md §3.3 and §8.4.
- Machines never check operator permissions (automation must keep working). Only direct player interaction with drones does.
- If a design question isn't answered in DESIGN.md, ask the user instead of inventing behavior, and update DESIGN.md with the decision.
- To boot a dev client/server for testing, use `scripts/smoke-test-server.ps1` rather than running `gradlew runServer`/`runClient` and piping `stop` to it. Gradle doesn't forward stdin to the forked game JVM, and killing the gradlew process tree on Windows doesn't kill the detached child either — both leave an orphaned game process running. The script waits for a boot marker and then kills the actual java process by matching `--launchTarget` in its command line.
