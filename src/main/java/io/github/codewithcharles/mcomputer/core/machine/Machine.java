package io.github.codewithcharles.mcomputer.core.machine;

import io.github.codewithcharles.mcomputer.core.component.ComponentBus;
import io.github.codewithcharles.mcomputer.core.component.ComponentRegistry;
import io.github.codewithcharles.mcomputer.core.vm.Vm;
import io.github.codewithcharles.mcomputer.core.vm.VmException;

import java.util.Objects;

/**
 * One computer, seen from the side that does not know Minecraft exists.
 *
 * <p><b>Its {@code Run} is its state:</b> a machine is on exactly when it has
 * one. No flag beside it, so the queues and the Lua thread cannot disagree
 * about whether the machine is on.
 *
 * <p>A run is one run. {@code CallQueue.shutdown()} is terminal and a VM does
 * not resume, so {@code stop()} discards the whole run and {@code start()}
 * builds another. A machine reboots; it does not resume.
 *
 * <p>{@code start()} spawns the Lua thread but compiles on the calling one, so
 * a boot script that does not compile stops the machine from ever being on
 * instead of killing it a moment later from a thread the caller cannot see.
 *
 * <p>Not thread-safe, and it does not need to be: {@code start}, {@code stop}
 * and {@code tick} are called from the server thread, and all the Lua thread
 * touches is what {@code RunAccess} hands it.
 */
public final class Machine {

    private final int maxTasksPerTick;
    private final VmFactory vmFactory;
    private final byte[] bootChunk;
    private final String bootChunkName;
    private final int signalQueueCapacity;

    /**
     * Hardware as opposed to execution: installed components belong to the
     * machine and survive a reboot, where the queues belong to the run and die
     * with it. Mutated by the adapter on the server thread, machine on or off.
     */
    private final ComponentRegistry components = new ComponentRegistry();
    private final ComponentBus componentBus = new ComponentBus(components);

    /**
     * Everything that belongs to one run and dies with it. Three nullable
     * fields could disagree about whether the machine is on; one record cannot.
     * The VM is not in here - the thread body is its only reader and captures
     * it.
     */
    private record Run(CallQueue queue, SignalQueue signals, Thread luaThread) {
    }

    private Run run;

    /**
     * @param maxTasksPerTick upper bound handed to {@link CallQueue#drain(int)}
     *                        on each tick
     * @param vmFactory       produces the VM of one run and receives that run's
     *                        plumbing
     * @param bootChunk       the script this computer runs, as bytes
     * @param bootChunkName   what its error messages call it
     * @param signalQueueCapacity bound of the per-run signal queue. 256 is
     *                            OpenComputers' default and minimum.
     */
    public Machine(
            int maxTasksPerTick,
            VmFactory vmFactory,
            byte[] bootChunk,
            String bootChunkName,
            int signalQueueCapacity)
    {
        this.maxTasksPerTick = maxTasksPerTick;
        this.vmFactory = Objects.requireNonNull(vmFactory, "vmFactory");
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
     * Hands the machine an event. Returns {@code false} when the machine is off
     * or the queue is full, where {@link #signalQueue()} throws: a key pressed
     * at a stopped computer is the emitter's normal life, asking a stopped
     * machine for its queue is a caller bug.
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

    public ComponentRegistry components() {
        return components;
    }

    public ComponentBus componentBus() {
        return componentBus;
    }

    public void start() {
        if (run != null) {
            return;
        }
        // Built before the VM, which cannot be constructed without them. What
        // waits for the load is the assignment of run, not these two lines: a
        // boot script that does not compile must leave the machine off.
        CallQueue queue = new CallQueue();
        SignalQueue signals = new SignalQueue(signalQueueCapacity);

        Vm vm = vmFactory.create(new RunAccess(queue, signals, componentBus));
        vm.load(bootChunk, bootChunkName);

        // Daemon: a Lua thread that outlives its machine must never be the
        // reason the JVM stays up.
        Thread luaThread = new Thread(() -> {
            try {
                vm.run();
            } catch (VmException e) {
                // Already on the script's output channel, where the player
                // reads it. Note the exact type: a bug of ours is not a
                // VmException, so it still escapes and is still loud.
            }
        }, "mcomputer-lua");
        luaThread.setDaemon(true);
        run = new Run(queue, signals, luaThread);
        luaThread.start();
    }

    public void stop() {
        if (run == null) {
            return;
        }
        // Order matters: the shutdown releases a thread parked in submit(), the
        // interrupt reaches one that is spinning. No join - this runs on the
        // server thread, and waiting for a script here would put its timing
        // inside the tick.
        run.queue().shutdown();
        run.luaThread().interrupt();
        run = null;
    }

    public void tick() {
        if (run == null) {
            return;
        }
        if (!run.luaThread().isAlive()) {
            // The run is over, well or badly. stop() is safe on a dead thread.
            stop();
            return;
        }
        run.queue().drain(maxTasksPerTick);
    }
}
