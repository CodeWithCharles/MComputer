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
- Chunkloader - keeps the chunk loaded so a program survives the player walking
  away

### The "mini Linux"

- Sandboxed Lua VM running server-side (LuaJ)
- A minimal Lua-scripted OS (OpenOS-style): basic shell, virtual filesystem,
  core commands (`ls`, `edit`, `run`)
- **No VM state persistence.** A computer that was on when the world unloaded
  reboots on load rather than resuming. The filesystem persists; execution does
  not. See "Execution model" below.

### Development milestones

1. **Done.** A computer block that boots: on/off, right-click toggle, state
   persisted in NBT and replayed on load, a `lit` blockstate so the state is
   visible. The static boot screen was moved to milestone 3, where the text
   rendering it needs already lives.
2. **Done.** Embedded Lua VM: a hardcoded script runs on its own thread, its
   output reaches the server log, and the sandbox holds.
3. **Next.** Screen and basic terminal - in-game text rendering, writable from
   Lua.
4. Real modular components - computer inventory, CPU/RAM affecting the VM
5. Persistent filesystem - save/load, minimal Lua shell

*Reaching milestone 3 already yields something playable and satisfying.*

The whole of the core, including the VM, is covered by tests that never launch
Minecraft. That was set as a binary acceptance criterion on day one, and it is
what makes iteration on the Lua side survivable.

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

## Execution model

The server ticks at 20 TPS - 50 ms per tick - so Lua cannot run on the server
thread. Slicing execution into per-tick instruction budgets would require
pausing the VM at arbitrary points, which LuaJ cannot do.

**Each computer therefore gets its own Java thread.** The Lua thread runs freely
and blocks when it needs anything from the game, handing the request to the
server thread through a queue; the server thread executes it during the tick and
wakes the Lua thread with the result. This is OpenComputers' model.

- The Lua thread never touches the world directly. Everything goes through the
  queue.
- **The machine owns the thread; the VM does not.** The VM is synchronous and
  knows nothing about threads, which keeps the sandbox, the budget and `print`
  testable as ordinary unit tests.
- **Compilation happens on the server thread**, before the thread is started, so
  a script that does not compile stops the computer from ever turning on rather
  than killing it a moment later from a thread nobody can see.
- An instruction budget, enforced via a debug hook armed every thousand
  instructions, kills infinite loops. CPU tier will map to the size of that
  budget.
- The hook is also the only place a stop request can be noticed, because a Lua
  loop ignores thread interruption entirely. Switching a computer off shuts its
  queue down, then interrupts.
- A run that ends, well or badly, turns the computer off on the next tick.
- Cost: one thread per *running* computer, daemon so it can never keep the JVM
  alive. Fine at this scale.

A wall-clock timeout is still on the list and not built. The instruction budget
covers a runaway loop; it does not cover a component method that blocks.

**Consequence for persistence:** resuming execution mid-script would require
serialising the VM. CC: Tweaked maintains a whole LuaJ fork (Cobalt) to make
that possible and still chooses to reboot computers on world load. We reboot
too. A well-written Lua program saves its state to disk and reloads it at boot -
the constraint becomes a gameplay mechanic.

## Sandboxing

A player's script is untrusted code running on a server. The globals it can see
are therefore **composed by hand, library by library**. LuaJ's
`JsePlatform.standardGlobals()` is never called: it installs `luajava`, LuaJ's
Java reflection library, and one line of Lua would then be arbitrary code
execution on the server.

The parts of this that are not guessable from the API are worth stating, because
every one of them was measured rather than assumed:

- **`BaseLib` installs `dofile` and `loadfile`, and appoints itself resource
  finder** - inside a Fabric mod that reads every resource of every loaded jar.
  Both globals are removed and the finder is nulled.
- **`DebugLib` has to be loaded** for the instruction hook to exist, and the
  `debug` table has to be removed afterwards, or a script calls
  `debug.sethook(nil)` and disarms the guard.
- **`pcall` catches `LuaError` and `java.lang.Exception`.** A hook raising either
  is swallowed by `while true do pcall(function() while true do end end) end`,
  which defeats the budget entirely while it appears to be installed. The hook
  throws a Java `Error`, which `pcall` cannot catch.
- **Chunks are loaded in text mode only, with no undumper installed**, so
  precompiled Lua bytecode cannot be handed to the VM.
- **Every LuaJ library except `BaseLib` requires a `package.loaded` table** to
  install at all, so a whitelist that excludes `PackageLib` has to provide one
  and then drop it.

Where a second lock is cheap, there are two. Text-only mode and no undumper.
Removed globals and a nulled finder. Each is one hole with two independent doors.

What a script can name is asserted in Lua, not in Java: what matters is not what
the globals table holds, but what a player can reach.

## Technical stack

| Item | Choice                                                                                                                                                             |
|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Minecraft version | 26.2 (Mojang's new numbering, formerly the 1.21.x line)                                                                                                            |
| Loader | Fabric (Loader + API) - lighter than NeoForge, better for solo iteration                                                                                           |
| Build system | Gradle 9.6.1 + Fabric Loom 1.17                                                                                                                                    |
| Loom plugin | `net.fabricmc.fabric-loom` - since 26.1 Minecraft ships unobfuscated, so **no remapping**. The older `fabric-loom-remap` plugin only applies to 1.21.11 and below. |
| Multi-version | Stonecutter 0.9.7, starting with a single target (26.2) so the plumbing exists without the upfront cost                                                            |
| Java | JDK 25 (required by Fabric from 26.1 onwards)                                                                                                                      |
| Lua VM | LuaJ 3.0.1, embedded jar-in-jar. Dormant since 2015, which is fine: with VM state persistence off the table, nothing more is needed from it                       |
| Fabric Loader | 0.19.3                                                                                                                                                             |
| Fabric API | 0.152.2+26.2                                                                                                                                                       |
| IDE | IntelliJ IDEA 2025.3+ (required for mixin annotation processing)                                                                                                   |
| Multi-loader (Architectury) | **Rejected** for now - unnecessary complexity for a solo MVP. Revisit if NeoForge support becomes desirable.                                                       |

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

- The screen and a basic terminal (milestone 3). A script's output and its
  errors already travel on the same injected sink, so this is a change of sink
  rather than a change to the VM.
- First question to settle there: whether the screen is a face of the computer
  block or a separate linked block.
- Then components as real items, which is where component addressing stops being
  a design note and meets Minecraft's data components.