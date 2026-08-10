package io.github.codewithcharles.mcomputer.core.machine;

/**
 * One computer, seen from the side that does not know Minecraft exists.
 *
 * <p>Its {@link CallQueue} <b>is</b> its state: a machine is on exactly when it
 * has one. There is no separate flag, so "running with no queue" is not a state
 * this class can be in.
 *
 * <p><b>A queue lives for one run, not for the machine.</b> {@code shutdown()}
 * is terminal, so {@code stop()} discards the queue and {@code start()} builds
 * a fresh one. This is the same statement as "a machine reboots, it does not
 * resume", made where the compiler can see it.
 *
 * <p>Not thread-safe: every method here is called from the server thread.
 */
public final class Machine {

    private final int maxTasksPerTick;

    private CallQueue callQueue;

    /**
     * @param maxTasksPerTick upper bound handed to {@link CallQueue#drain(int)}
     *                        on each tick
     */
    public Machine(int maxTasksPerTick) {
        this.maxTasksPerTick = maxTasksPerTick;
    }

    public boolean isRunning() {
        return callQueue != null;
    }

    /**
     * The queue of the current run, for the Lua thread to submit through.
     *
     * <p>A queue belongs to one run: the instance handed out here is shut down
     * and dropped by {@link #stop()}, and {@link #start()} builds another. A
     * caller must not hold one across a restart.
     *
     * @throws IllegalStateException if the machine is off - asking a stopped
     *         machine for its queue is a caller bug, not a state to branch on
     */
    public CallQueue callQueue() {
        if (callQueue == null) {
            throw new IllegalStateException("machine is off");
        }
        return callQueue;
    }

    /** Turns the machine on, giving it a fresh queue. */
    public void start() {
        if (callQueue != null) {
            return;
        }
        callQueue = new CallQueue();
    }

    /**
     * Turns the machine off: releases everything waiting on the queue, then
     * drops it. Called when the block is broken, the chunk unloads or the
     * server stops, as well as by the player.
     */
    public void stop() {
        if (callQueue == null) {
            return;
        }
        callQueue.shutdown();
        callQueue = null;
    }

    /** Called once per server tick, whatever state the machine is in. */
    public void tick() {
        if (callQueue == null) {
            return;
        }
        callQueue.drain(maxTasksPerTick);
    }
}
