package io.github.codewithcharles.mcomputer.core.machine;

import io.github.codewithcharles.mcomputer.core.vm.Vm;
import io.github.codewithcharles.mcomputer.core.vm.VmException;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * One computer, seen from the side that does not know Minecraft exists.
 *
 * <p>Its {@code Run} <b>is</b> its state: a machine is on exactly when it has
 * one. There is no flag beside it, and the queue and the Lua thread cannot
 * disagree about whether the machine is on, because neither can be held without
 * the other.
 *
 * <p><b>A run is what its name says - one run.</b> {@code CallQueue.shutdown()}
 * is terminal and a VM does not resume, so {@code stop()} discards the whole run
 * and {@code start()} builds another. This is "a machine reboots, it does not
 * resume", written where the compiler can see it.
 *
 * <p><b>{@code start()} spawns a thread; compilation does not run on it.</b> The
 * script is compiled here, on the calling thread, so a boot script that does not
 * compile stops the machine from ever being on instead of killing it a moment
 * later from a thread the caller cannot see.
 *
 * <p>Not thread-safe, and it does not need to be: {@code start}, {@code stop} and
 * {@code tick} are called from the server thread, and the only thing the Lua
 * thread ever touches is the {@link CallQueue}, which exists for exactly that.
 */
public final class Machine {

    private final int maxTasksPerTick;
    private final Supplier<Vm> vms;
    private final byte[] bootChunk;
    private final String bootChunkName;
    private final int signalQueueCapacity;

    /**
     * Everything that belongs to one run and dies with it.
     *
     * <p>The 2026-08-10 rule is unchanged, only widened: a machine is on exactly
     * when it has a run. Three separate nullable fields could disagree about
     * whether the machine is on; one cannot. The VM is deliberately not in here
     * - the thread body is its only reader, and it captures it.
     */
    private record Run(CallQueue queue, SignalQueue signals, Thread luaThread) {
    }

    private Run run;

    /**
     * @param maxTasksPerTick upper bound handed to {@link CallQueue#drain(int)}
     *                        on each tick
     * @param vms             produces a fresh VM per <b>run</b>, for the same
     *                        reason the queue is per run: nothing resumes, a
     *                        machine reboots
     * @param bootChunk       the script this computer runs, as bytes
     * @param bootChunkName   what its error messages call it
     * @param signalQueueCapacity bound of the per-run signal queue. 256 is
     *                            OpenComputers' default AND minimum; a full
     *                            queue refuses the incoming signal.
     */
    public Machine(
            int maxTasksPerTick,
            Supplier<Vm> vms,
            byte[] bootChunk,
            String bootChunkName,
            int signalQueueCapacity)
    {
        this.maxTasksPerTick = maxTasksPerTick;
        this.vms = Objects.requireNonNull(vms, "vms");
        this.bootChunk = Objects.requireNonNull(bootChunk, "bootChunk");
        this.bootChunkName = Objects.requireNonNull(bootChunkName, "bootChunkName");
        this.signalQueueCapacity = signalQueueCapacity;
    }

    public boolean isRunning() {
        return run != null;
    }

    public CallQueue callQueue() {
        if (run == null) {
            throw new IllegalStateException("machine is off");
        }
        return run.queue();
    }

    /**
     * Hands the machine an event. Returns {@code false} when the machine is
     * off or the queue is full - deliberately NOT an exception, unlike
     * {@link #signalQueue()}: a key pressed at a stopped computer is a normal
     * occurrence the emitter shrugs at, not a caller bug. Same asymmetry as
     * Arguments' indexing, for the same reason - who produced the case decides
     * how it is reported.
     */
    public boolean pushSignal(Signal signal) {
        if (run == null) {
            return false;
        }
        return run.signals().push(signal);
    }

    public SignalQueue signalQueue() {
        if (run == null) {
            throw new IllegalStateException("machine is off");
        }
        return run.signals();
    }

    public void start() {
        if (run != null) {
            return;
        }
        Vm vm = vms.get();
        vm.load(bootChunk, bootChunkName);

        // Daemon: a Lua thread that outlives its machine must never be the
        // reason the JVM stays up.
        Thread luaThread = new Thread(() -> {
            try {
                vm.run();
            } catch (VmException e) {
                // Already reported to the script's own output channel, which is
                // where the player will read it. Rethrowing would reach nothing
                // but the JVM's default handler. Note the exact type: a bug of
                // OURS still escapes and is still loud - the same split as
                // ComponentException against everything else, one layer up.
            }
        }, "mcomputer-lua");
        luaThread.setDaemon(true);
        run = new Run(new CallQueue(), new SignalQueue(signalQueueCapacity), luaThread);
        luaThread.start();
    }

    public void stop() {
        if (run == null) {
            return;
        }
        // Order matters: the shutdown releases a thread parked in submit() with
        // its error, the interrupt reaches one that is spinning. Deliberately no
        // join - this runs on the server thread, and waiting for a script here
        // would put an arbitrary script's timing inside the tick.
        run.queue().shutdown();
        run.luaThread().interrupt();
        run = null;
    }

    public void tick() {
        if (run == null) {
            return;
        }
        if (!run.luaThread().isAlive()) {
            // The run is over, well or badly. stop() is safe on a dead thread:
            // the shutdown releases nobody and the interrupt reaches no one.
            stop();
            return;
        }
        run.queue().drain(maxTasksPerTick);
    }
}
