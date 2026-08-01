# MComputer

A Minecraft (Fabric) mod inspired by OpenComputers: a modular computer block
that boots a minimal, Lua-scriptable OS.

**Status: setup only.** The skeleton builds and runs; no gameplay yet.

## Requirements

- JDK 25 (Temurin recommended)
- IntelliJ IDEA 2025.3 or later — required for mixin annotation processing

## Building and running

```sh
# Launch the development client
./gradlew ":26.2.x:runClient"

# Build the mod jar into build/libs/<version>/
./gradlew buildAndCollect
```

The first run downloads and decompiles Minecraft; expect several minutes.
Subsequent runs start in seconds.

## Versioning

Minecraft targets are managed with [Stonecutter](https://stonecutter.kikugie.dev/).
The single source of truth is `stonecutter.properties.toml`. Currently one
target: `26.2.x`, compiled against Minecraft 26.2.

## Supported versions

| Minecraft | Fabric Loader | Fabric API |
|---|---|---|
| 26.2 | 0.19.3+ | 0.152.2+26.2 |

## Design

See [`docs/design.md`](docs/design.md).

## License

MIT