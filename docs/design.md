# Project: OpenComputers-inspired Minecraft mod

## Context

A Minecraft mod written for fun and learning, inspired by the cult mod
**OpenComputers**. After surveying the existing repositories (the original
OpenComputers, the OC2R fork, various 1.20.1 ports), every porting effort found
was inactive. Decision: **start from scratch**, borrowing the concept rather
than porting or forking the existing code (Scala, dated architecture).

## Key decision: start from scratch

**Upside:**

- Java, modern stack, fully under control
- Clean architectural choices from day one
- Controllable scope - no obligation to reimplement everything
- More motivating long-term than debugging third-party code

**What we keep from the original concept** (MIT-licensed, free inspiration, no
code copied):

- A modular "computer" block with components you insert and remove to unlock
  features
- The core idea: the computer boots a small Lua-scriptable OS

**What we drop:**

- Autonomous robots and drones, and anything involving movement or pathfinding
- Cable networks and components distributed across the world - everything stays
  inside the block
- 3D screens, holograms, 3D printers, cross-mod integrations - all deferred
  until the project proves itself

## MVP scope

### The computer block

- A placed block with an internal inventory for components
- On/off state, drives the boot sequence

### Modular components (cards inserted into the computer)

- CPU (execution speed / tier)
- RAM (memory ceiling for the Lua script)
- Hard drive / floppy (persistent storage, simple filesystem)
- Graphics card + screen (linked block, text and pixel output)
- Network card - out of MVP scope, later

### The "mini Linux"

- Sandboxed Lua VM running server-side (likely LuaJ on the JVM for the MVP -
  easier to integrate than a native binding such as Eris)
- A minimal Lua-scripted OS (OpenOS-style): basic shell, virtual filesystem,
  core commands (`ls`, `edit`, `run`)
- Persistence: state resumes where it stopped when the chunk reloads

### Suggested development milestones

1. A computer block that boots (on/off, static boot screen, no components)
2. Embedded Lua VM - run a hardcoded script, output to the server log
3. Screen and basic terminal - in-game text rendering, writable from Lua
4. Real modular components - computer inventory, CPU/RAM affecting the VM
5. Persistent filesystem - save/load, minimal Lua shell

*Reaching milestone 3 already yields something playable and satisfying.*

## Filesystem persistence

**How the original does it (for reference):** OpenComputers uses a "SaveHandler"
that stores machine state in **external files** rather than directly in the
block's NBT, avoiding NBT size limits, organised hierarchically by dimension and
chunk.

**Approach chosen for the MVP:**

- The block entity's NBT holds only lightweight metadata (disk UUID, on/off
  state, inserted components)
- The actual filesystem contents (files, Lua scripts) live separately, in a file
  under the world save directory, e.g. `world/data/mcomputer/disks/<uuid>.dat`
- Lazy loading: the file is read only when the VM needs filesystem access, not
  every tick
- No need for a real block/inode filesystem in the MVP - a simple
  path-to-content map is enough

## Technical stack

| Item | Choice |
|---|---|
| Minecraft version | 26.2 (Mojang's new numbering, formerly the 1.21.x line) |
| Loader | Fabric (Loader + API) - lighter than NeoForge, better for solo iteration |
| Build system | Gradle 9.5.1 + Fabric Loom 1.17 |
| Loom plugin | `net.fabricmc.fabric-loom` - since 26.1 Minecraft ships unobfuscated, so **no remapping**. The older `fabric-loom-remap` plugin only applies to 1.21.11 and below. |
| Multi-version | Stonecutter 0.9.7, starting with a single target (26.2) so the plumbing exists without the upfront cost |
| Java | JDK 25 (required by Fabric from 26.1 onwards) |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.152.2+26.2 |
| IDE | IntelliJ IDEA 2025.3+ (required for mixin annotation processing) |
| Multi-loader (Architectury) | **Rejected** for now - unnecessary complexity for a solo MVP. Revisit if NeoForge support becomes desirable. |

## Explored and rejected

- **MightyPirates/OpenComputers** (original) - Scala, no official plan to port to
  recent versions
- **North-Western-Development/oc2r** - active fork of OpenComputers II
  (RISC-V/Linux VM, not the sandboxed-Lua concept) - rejected as a different
  approach from classic OC
- **SirDavidLudwig/OpenComputers-Reimagined** - Architectury rewrite attempt -
  inactive
- **TheRealM18 / North-Western-Development - OpenComputers-1.20.1-port** -
  direct port of the original - inactive

## Next steps

- Project skeleton (Stonecutter + Loom, `fabric.mod.json`)
- First basic computer block (milestone 1)
- Detailed design of the Lua VM and the LuaJ integration
