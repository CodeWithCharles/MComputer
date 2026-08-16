# The shell

What the computer runs, and how to add to it.

## Booting

The Java boot chunk is this system's BIOS. It finds the filesystem component,
reads `/boot.lua`, compiles it and runs it. That is all it does, and it is the
same job an EEPROM does in the mod this one takes its concepts from.

`/boot.lua` is the shell. It reads a line, splits it into a command word and
arguments, finds a file for the word and runs it. `lua` is one of those files
rather than the heart of the system.

The system installs itself onto a fresh disk from resources in the mod jar,
one file at a time and only when the disk has none. So a file you edit is
never overwritten, a file you delete comes back at the next boot, and an
install interrupted halfway is finished by the boot after it.

## Finding a command

A word with a slash in it is a path, and `PATH` is not consulted. A word
without one is looked for in `PATH`, which is `{ '.', '/bin' }`: the current
directory first, then the system commands. If the name as written finds
nothing, `.lua` is added and the search runs again. So `ls`, `ls.lua`,
`/bin/ls` and `bin/ls.lua` all reach the same file, and a script of your own
called `ls.lua` in the current directory wins over the built-in one.

A word that finds nothing is reported and nothing runs.

## The API a command is given

A command is a chunk compiled with no environment of its own, so it sees the
shell's globals and calls them the way `/boot.lua` does. There is no `require`:
the sandbox installs no package library, so globals are the only channel there
is.

| global | what it is |
|---|---|
| `gpu`, `fs` | the addresses of the two components |
| `invoke(address, method, ...)` | call a component |
| `write(text)` | put text on the screen, `\n` starts a row |
| `readLine(prompt)` | read one line from the keyboard |
| `readFile(path)` | the whole contents of a file |
| `resolve(path)` | a path made absolute against `cwd`, and canonical |
| `run(path, ...)` | run a file |
| `execute(source, name, ...)` | compile a string and run it |
| `firstLine(message)` | the first row of an error |
| `cwd`, `PATH`, `width`, `height` | the shell's state and the screen's size |

Arguments arrive as `...`, so a command starts with `local args = { ... }`.

A command that sets `cwd` moves the shell, because there is no isolation
between the two. That is what makes `cd` five lines, and it is also why a
command that overwrites `write` breaks the shell until the next boot.

## Writing one

Put a `.lua` file in `src/main/resources/assets/mcomputer/lua/bin/` and add its
disk path to `SYSTEM_FILES` in `ComputerBlockEntity`. The resource path is
derived from the disk path, so `/bin/mine.lua` is read from
`assets/mcomputer/lua/bin/mine.lua`.

Or write it from inside the machine, with `lua`, and skip the jar entirely. It
will not survive breaking the block, which takes the disk with it.

## One call is one tick

A component call crosses to the server thread and waits for the next tick, so
it costs fifty milliseconds. A screen drawn cell by cell would take a hundred
seconds.

The way around it is to build the whole picture as one string and send it in a
single `write`: `writeLine` reads the newlines and lays out every row at once,
so a full screen arrives in one tick.

`computer.pullSignal(seconds)` is the closest thing to a sleep. It blocks for
that long, spends no instruction budget while it waits, and returns early if a
key is pressed. The same call paces an animation and gives it its exit key.

## Pictures

A picture is a file of one raw byte per cell. The terminal font gives five
levels of density to build them from: a space, then bytes 176, 177, 178 and
219. `cat` displays one in a single call, so it appears at once.

`glyphs` shows all 256 characters the screen can draw, with the byte number of
each row. Byte 10 is the one the screen reads instead of storing, so it is the
only one that cannot travel inside a string.

`/art/heart.art` is computed from the implicit equation of a heart rather than
drawn, so the file can be regenerated. To make your own from an image, resize
it to 80 by 22 with the vertical squashed by two, because a cell is eight
pixels wide and sixteen tall, then map brightness onto the five bytes above.
Line art wants inverting first: dark ink on paper has to become bright ink on
a black screen.

Art files carry bytes above 127, so `.gitattributes` marks `*.art` binary. A
line ending normalised inside one would rewrite the picture.

## Known limits

The line you are typing does not wrap. Past the width of the screen it slides
sideways and shows its tail, and the line kept on screen afterwards is that
tail rather than the whole of it. Real wrapping wants a `gpu.copy`, which the
graphics card does not have yet.

A `/boot.lua` that does not compile leaves the computer unusable, because
deleting it would need a shell. Breaking the block is the way out, and it takes
the disk with it.
