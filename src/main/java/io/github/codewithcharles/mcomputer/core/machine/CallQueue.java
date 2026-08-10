package io.github.codewithcharles.mcomputer.core.machine;

/**
 * The hand-off between a Lua thread and the server thread.
 *
 * <p>A Lua thread that needs the game submits work here and <b>blocks</b>. The
 * server thread drains the queue during its tick, runs the work, and wakes the
 * waiter with the result.
 *
 * <p><b>Invariant worth stating:</b> because the submitting thread blocks until
 * its own task has run, there is at most <b>one task in flight per machine</b>.
 * The queue only ever holds as many entries as there are running computers.
 *
 * <p>This class knows nothing about components. It moves work across a thread
 * boundary, which makes it testable with two plain Java threads and a lambda.
 */
public final class CallQueue {

    /**
     * Called from a Lua thread. Blocks until the server thread has run the task.
     *
     * @return whatever the task returned
     * @throws InterruptedException if the calling thread is interrupted, which
     *         is how a machine gets stopped
     * @throws IllegalStateException if the queue has been shut down
     */
    public Object[] submit(ServerTask task) throws InterruptedException {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Called from the server thread, once per tick.
     *
     * <p>An exception thrown by a task must be delivered to the thread that
     * submitted it and must not escape this method - one misbehaving computer
     * cannot be allowed to break the tick for the whole server. This is the
     * single most important behaviour of this class.
     *
     * @param maxTasks upper bound for one tick
     * @return how many tasks were run
     */
    public int drain(int maxTasks) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Refuses further submissions and releases every waiting thread with an
     * error. Without this, a Lua thread blocked on a queue nobody drains again
     * (server stopping, chunk unloaded, ...) waits forever.
     */
    public void shutdown() {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Work to be run on the server thread. */
    @FunctionalInterface
    public interface ServerTask {
        Object[] run();
    }
}
