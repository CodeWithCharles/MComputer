package io.github.codewithcharles.mcomputer.core.machine;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

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

    private final Queue<Entry> pending = new ConcurrentLinkedQueue<>();
    private volatile boolean shutdown;

    /**
     * Called from a Lua thread. Blocks until the server thread has run the task.
     *
     * @return whatever the task returned
     * @throws InterruptedException if the calling thread is interrupted, which
     *         is how a machine gets stopped
     * @throws IllegalStateException if the queue has been shut down
     */
    public Object[] submit(ServerTask task) throws InterruptedException {
        Objects.requireNonNull(task, "task");
        if (shutdown) {
            throw shutDown();
        }

        Entry entry = new Entry(task);
        pending.add(entry);
        if (shutdown && pending.remove(entry)) {
            throw shutDown();
        }

        try {
            entry.done.await();
        } catch (InterruptedException interrupted) {
            pending.remove(entry);
            throw interrupted;
        }

        if (entry.failure != null) {
            throw entry.failure;
        }

        return entry.result;
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
        int ran = 0;
        while (ran < maxTasks) {
            Entry entry = pending.poll();
            if (entry == null) {
                break;
            }
            entry.run();
            ran++;
        }
        return ran;
    }

    /**
     * Refuses further submissions and releases every waiting thread with an
     * error. Without this, a Lua thread blocked on a queue nobody drains again
     * (server stopping, chunk unloaded, ...) waits forever.
     */
    public void shutdown() {
        shutdown = true;
        for (Entry entry = pending.poll(); entry != null; entry = pending.poll()) {
            entry.fail(shutDown());
        }
    }

    private static IllegalStateException shutDown() {
        return new IllegalStateException("call queue shut down");
    }

    /** Work to be run on the server thread. */
    @FunctionalInterface
    public interface ServerTask {
        Object[] run();
    }

    /** One submission: the work, where its result lands, and the waiter's gate. */
    private static final class Entry {

        private final ServerTask task;
        private final CountDownLatch done = new CountDownLatch(1);
        private Object[] result;
        private RuntimeException failure;

        Entry(ServerTask task) {
            this.task = task;
        }

        void run() {
            try {
                result = task.run();
            } catch (RuntimeException thrown) {
                failure = thrown;
            } finally {
                done.countDown();
            }
        }

        void fail (RuntimeException cause) {
            failure = cause;
            done.countDown();
        }
    }
}
