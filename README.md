# Voxel

A simple Minecraft-like voxel engine built with Kotlin and the Kool Engine, using Vulkan (via JVM).

## Features

- **Voxel Rendering**: Efficiently renders large chunks of voxels using a custom shader and texture arrays.
- **Infinite Generation**: Procedural world generation using Simplex noise.
- **Player Physics**: Includes walking, jumping, collision detection, and step assistance.
- **Noclip Mode**: Fly through the world with adjustable speed.
- **Dynamic Loading**: Chunks are loaded and generated around the player based on render distance.

## Controls

- **W, A, S, D**: Move forward, left, backward, and right.
- **Space**: Jump (or fly up in noclip).
- **Shift**: Descend (in noclip).
- **N**: Toggle Noclip mode.
- **R**: Regenerate world with a new random seed.
- **Enter**: Respawn at the center of the world.
- **Esc**: Unlock cursor.
- **Left/Right/Middle Click**: Lock cursor to control camera.
- **Mouse Scroll**: Adjust flight speed (when in noclip).

## Prerequisites

- **Java 25**: This project uses the latest Java features (like foreign function access for Vulkan).
- **Vulkan Compatible Hardware**: Ensure your drivers support Vulkan.

## Getting Started

To run the project, use the Gradle wrapper:

```bash
./gradlew run
```

## Project Structure

- `src/main/kotlin/main.kt`: Entry point and scene setup.
- `src/main/kotlin/world.kt`: World and chunk management, procedural generation logic.
- `src/main/kotlin/player.kt`: Player state, physics, and collision logic.
- `src/main/kotlin/PlayerControls.kt`: Input handling and camera control.
- `src/main/kotlin/WorldManager.kt`: Bridges the world data and the rendering engine (mesh generation).
- `src/main/kotlin/noise.kt`: Simplex noise implementation.

## Dependencies

- [Kool Engine](https://github.com/fabmax/kool): A high-performance multi-platform game engine for Kotlin.

## License

This project is licensed under the terms of the LICENSE file included in the repository.
