package io.github.codewithcharles.mcomputer.core.machine;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

/**
 * The hand-off between a Lua thread and the server thread. A Lua thread that
 * needs the game submits work here and blocks; the server thread drains during
 * its tick, runs the work, and wakes the waiter with the result.
 *
 * <p>Because the submitter blocks until its own task has run, there is at most
 * one task in flight per machine, so the queue only ever holds as many entries
 * as there are running computers.
 *
 * <p>Knows nothing about components: it moves work across a thread boundary,
 * which makes it testable with two plain threads and a lambda.
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
            throw new ShutDown();
        }

        Entry entry = new Entry(task);
        pending.add(entry);
        // The only delicate line here. Without it the first check can pass,
        // shutdown() can run to completion, and the add land afterwards: the
        // entry arrives after the sweep and nobody ever wakes it. If the remove
        // succeeds nobody else holds the entry, so throwing is safe; if it
        // fails, shutdown already marked it and await returns at once.
        if (shutdown && pending.remove(entry)) {
            throw new ShutDown();
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
     * <p>An exception thrown by a task is delivered to the thread that submitted
     * it and must not escape here: one misbehaving computer cannot break the
     * tick for the whole server.
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
     * error. Without it, a Lua thread blocked on a queue nobody drains again
     * waits forever. Queued tasks do not run: shutdown happens while the machine
     * is being torn down, so they would touch the world on the way out.
     *
     * <p>{@link #drain} stays legal afterwards and finds an empty queue.
     */
    public void shutdown() {
        shutdown = true;
        for (Entry entry = pending.poll(); entry != null; entry = pending.poll()) {
            entry.fail(new ShutDown());
        }
    }

    /** Work to be run on the server thread. */
    @FunctionalInterface
    public interface ServerTask {
        Object[] run();
    }

    /**
     * The queue is shut down and nothing will run again. A subclass, so callers
     * that only need "the queue refused" keep working against
     * IllegalStateException, while the one that has to tell teardown from a
     * component's own failure can.
     */
    static final class ShutDown extends IllegalStateException {
        ShutDown() {
            super("call queue shut down");
        }
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

        /**
         * Rethrown to the submitter as the same instance, never wrapped: a
         * ComponentException has to arrive as one for the boundary to make a
         * Lua error of it, and a NullPointerException has to arrive as itself
         * so we see it.
         *
         * <p>An Error is not caught. An OutOfMemoryError delivered as though
         * one component had misbehaved keeps a broken server running.
         */
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
