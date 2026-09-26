# Seeker Drones

A NeoForge mod for Minecraft 1.21.1 adding autonomous flying drones that hunt configured targets, built and deployed through an automatable machine pipeline.

- Design spec: [`docs/DESIGN.md`](docs/DESIGN.md)
- Implementation roadmap: [`docs/ROADMAP.md`](docs/ROADMAP.md)

## Development

- Java 21 (the Gradle toolchain will fetch it automatically if it isn't installed).
- Run the client: `./gradlew runClient`
- Run a dedicated server: `./gradlew runServer`
- Run data generation: `./gradlew runData`
- Build the mod jar: `./gradlew build`
