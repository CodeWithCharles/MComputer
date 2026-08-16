package io.github.codewithcharles.mcomputer.core.screen;

import io.github.codewithcharles.mcomputer.core.vm.VmOutput;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * The screen seen as a {@link VmOutput}: it accepts lines from whichever thread
 * produced them and hands them to a {@link ScreenBuffer} on the thread that
 * drains.
 *
 * <p>It exists because a script's output does not arrive on one thread.
 * {@code print} reaches the sink from the Lua thread, a compile failure from
 * the server thread inside {@code Machine.start()}, and {@code ScreenBuffer}
 * assumes a single thread.
 *
 * <p>Not a {@code CallQueue}: that class makes its submitter wait for the
 * server thread, which would cost the Lua thread up to a tick per printed line.
 * Nobody waits for the result of writing to a screen.
 *
 * <p>The queue is bounded and a full one drops its oldest line. A runaway
 * {@code while true do print("x") end} costs few instructions, so the budget
 * does not bound it. Dropping the oldest keeps the newest, which is where the
 * error that killed the script is.
 */
public final class ScreenOutput implements VmOutput {

    private final ScreenBuffer buffer;
    private final int maxPendingLines;
    private final Queue<byte[]> pending;

    /**
     * @param buffer          where drained lines land
     * @param maxPendingLines how many lines may wait for the next drain,
     *                        strictly positive. Injected so a test can fill it
     *                        with three lines instead of a few hundred.
     * @throws IllegalArgumentException if maxPendingLines is not positive
     */
    public ScreenOutput(ScreenBuffer buffer, int maxPendingLines) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        if (maxPendingLines <= 0) {
            throw new IllegalArgumentException(
                    "maxPendingLines must be positive, got " + maxPendingLines);
        }
        this.maxPendingLines = maxPendingLines;
        this.pending = new LinkedBlockingDeque<>(maxPendingLines);
    }

    /** The buffer being written into. The renderer reads it; nobody else. */
    public ScreenBuffer buffer() {
        return buffer;
    }

    /**
     * Accepts one line, from any thread. Never blocks, never throws on a full
     * queue: output is not an argument and cannot be refused. A {@code while}
     * rather than an {@code if}, or a failed offer racing a concurrent drain
     * would return having written nothing.
     */
    @Override
    public void write(byte[] line) {
        while (!pending.offer(line)) {
            pending.poll();
        }
    }

    /**
     * Writes every waiting line into the buffer. Called on the draining thread
     * and on no other. The cap has a second job: without it, a Lua thread
     * printing while we drain makes this a tick that never returns.
     *
     * @return how many lines were written. Not a signal that the screen
     *         changed: the graphics card writes to the buffer directly, so a
     *         caller watching this number misses everything a shell draws.
     */
    public int drain() {
        int written = 0;
        while (written < maxPendingLines) {
            byte[] line = pending.poll();
            if (line == null) {
                break;
            }
            buffer.writeLine(line);
            written++;
        }
        return written;
    }
}
