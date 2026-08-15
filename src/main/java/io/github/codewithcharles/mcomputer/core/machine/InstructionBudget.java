package io.github.codewithcharles.mcomputer.core.machine;

/**
 * How many Lua instructions one run may execute per tick.
 *
 * <p>A rate rather than a total: a run that is a shell never ends, so a
 * lifetime allowance is a countdown to a death no player can explain. Spending
 * it all is the ordinary state of a script that works, once per tick, so
 * exhaustion waits for the next grant instead of killing anything.
 *
 * <p><b>The wait is interruptible, and that is what makes it safe:</b>
 * {@code Machine.stop()} interrupts the Lua thread, so a parked script is a
 * stopped script and not a leak.
 *
 * <p>Both sides are synchronised because the two threads meet here: the Lua
 * thread spends, the server thread grants.
 */
public final class InstructionBudget {

    private final int perTick;
    private int remaining;

    /**
     * @param perTick the allowance one tick grants. A multiple of the interval
     *                the hook checks at, or the last check of a tick overspends
     *                by most of one interval.
     */
    public InstructionBudget(int perTick) {
        if (perTick <= 0) {
            throw new IllegalArgumentException("perTick must be > 0, got " + perTick);
        }
        this.perTick = perTick;
        this.remaining = perTick;
    }

    /**
     * Takes {@code instructions} out of the allowance, waiting for a grant when
     * there is nothing left.
     *
     * @throws InterruptedException when the run is stopped while waiting
     */
    public synchronized void spend(int instructions) throws InterruptedException {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Refills for one tick. The allowance is set rather than added, so a script
     * parked in {@code pullSignal} banks nothing and cannot come back in a
     * burst; what a tick overspent is carried, so the average holds.
     */
    public synchronized void grant() {
        throw new UnsupportedOperationException("not implemented");
    }
}