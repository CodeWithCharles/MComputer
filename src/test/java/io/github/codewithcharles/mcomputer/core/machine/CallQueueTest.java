package io.github.codewithcharles.mcomputer.core.machine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CallQueueTest {

    /**
     * A Lua thread, seen from the test: it submits, then blocks. What it got
     * back is only readable once it has terminated.
     */
    private static final class Submitter {
        Thread thread;
        volatile Object[] result;
        volatile Throwable failure;

        void awaitCompletion() throws InterruptedException {
            thread.join(Duration.ofSeconds(2).toMillis());
            assertFalse(thread.isAlive(), "the submitting thread never returned");
        }
    }

    private static Submitter submit(CallQueue queue, CallQueue.ServerTask task) {
        Submitter submitter = new Submitter();
        submitter.thread = new Thread(() -> {
            try {
                submitter.result = queue.submit(task);
            } catch (Throwable caught) {
                submitter.failure = caught;
            }
        }, "submitter");
        submitter.thread.setDaemon(true);
        submitter.thread.start();
        return submitter;
    }

    private static void drainUntil(CallQueue queue, int tasks) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        int ran = 0;
        while (ran < tasks && System.nanoTime() < deadline) {
            ran += queue.drain(16);
            Thread.onSpinWait();
        }
        assertEquals(tasks, ran, "the queue never ran the expected number of tasks");
    }

    /**
     * Spins until the submitter is parked inside submit(). WAITING is observable
     * and has no other cause here, so this is a precondition rather than a
     * timing assumption - without it, shutdown() can win the race and the tests
     * below pass for the wrong reason.
     */
    private static void awaitBlocked(Submitter submitter) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (submitter.thread.getState() == Thread.State.WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        fail("the submitter never blocked inside submit()");
    }

    @Test
    void anEmptyQueueDrainsNothing() {
        assertEquals(0, new CallQueue().drain(16));
    }

    /**
     * "Executed on the server thread" is the whole point of this class. Had the
     * task run on the submitting thread, or on one of its own, this fails.
     */
    @Test
    void aTaskRunsOnTheThreadThatDrains() throws InterruptedException {
        CallQueue queue = new CallQueue();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        Submitter submitter = submit(queue, () -> {
            ranOn.set(Thread.currentThread());
            return new Object[0];
        });

        drainUntil(queue, 1);
        submitter.awaitCompletion();

        assertSame(Thread.currentThread(), ranOn.get());
    }

    /**
     * The backbone assertion: this value cannot exist before the task has run,
     * so getting it proves the submitter waited - without observing a clock.
     */
    @Test
    void theSubmitterGetsWhatTheTaskReturned() throws InterruptedException {
        CallQueue queue = new CallQueue();
        Submitter submitter = submit(queue, () -> new Object[] { 42.0, Boolean.TRUE });

        drainUntil(queue, 1);
        submitter.awaitCompletion();

        assertNull(submitter.failure);
        assertArrayEquals(new Object[] { 42.0, Boolean.TRUE }, submitter.result);
    }

    /**
     * The same instance, not a wrapper: a ComponentException must arrive at the
     * Lua thread as a ComponentException, or the script-error / our-bug split
     * dies at the thread boundary.
     */
    @Test
    void anExceptionFromATaskReachesTheSubmitterUnchanged() throws InterruptedException {
        CallQueue queue = new CallQueue();
        IllegalStateException boom = new IllegalStateException("boom");
        Submitter submitter = submit(queue, () -> { throw boom; });

        drainUntil(queue, 1);
        submitter.awaitCompletion();

        assertSame(boom, submitter.failure);
    }

    /**
     * One misbehaving computer cannot break the tick for the whole server.
     */
    @Test
    void aFailingTaskDoesNotStopTheQueue() throws InterruptedException {
        CallQueue queue = new CallQueue();
        Submitter failing = submit(queue, () -> { throw new IllegalStateException("boom"); });
        Submitter healthy = submit(queue, () -> new Object[] { 1.0 });

        drainUntil(queue, 2);

        failing.awaitCompletion();
        healthy.awaitCompletion();
        assertArrayEquals(new Object[] { 1.0 }, healthy.result);
    }

    @Test
    void submittingAfterShutdownIsRefused() {
        CallQueue queue = new CallQueue();
        queue.shutdown();

        assertThrows(IllegalStateException.class, () -> queue.submit(() -> new Object[0]));
    }

    @Test
    void shutdownWakesAThreadAlreadyWaiting() throws InterruptedException {
        CallQueue queue = new CallQueue();
        Submitter submitter = submit(queue, () -> new Object[0]);
        awaitBlocked(submitter);

        queue.shutdown();

        submitter.awaitCompletion();
        assertInstanceOf(IllegalStateException.class, submitter.failure);
    }

    @Test
    void aTaskQueuedAtShutdownIsNeverRun() throws InterruptedException {
        CallQueue queue = new CallQueue();
        AtomicBoolean ran = new AtomicBoolean();
        Submitter submitter = submit(queue, () -> {
            ran.set(true);
            return new Object[0];
        });
        awaitBlocked(submitter);

        queue.shutdown();
        submitter.awaitCompletion();

        assertEquals(0, queue.drain(16));
        assertFalse(ran.get());
    }

    @Test
    void anInterruptedSubmitterLeavesNothingBehind() throws InterruptedException {
        CallQueue queue = new CallQueue();
        Submitter submitter = submit(queue, () -> new Object[0]);
        awaitBlocked(submitter);

        submitter.thread.interrupt();
        submitter.awaitCompletion();

        assertInstanceOf(InterruptedException.class, submitter.failure);
        assertEquals(0, queue.drain(16));
    }
}