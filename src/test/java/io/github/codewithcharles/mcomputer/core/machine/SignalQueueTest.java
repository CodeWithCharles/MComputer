package io.github.codewithcharles.mcomputer.core.machine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

final class SignalQueueTest {

    private static Signal signal(String name) {
        return new Signal(name, new Object[0]);
    }

    /**
     * The Lua thread, seen from the test: it pulls, then blocks. What it got
     * back is only readable once it has terminated. A deliberate copy of
     * CallQueueTest's Submitter, so that suite stays out of this diff.
     */
    private static final class Puller {
        Thread thread;
        volatile Signal received;
        volatile Throwable failure;

        void awaitCompletion() throws InterruptedException {
            thread.join(Duration.ofSeconds(2).toMillis());
            assertFalse(thread.isAlive(), "the pulling thread never returned");
        }
    }

    private static Puller pull(SignalQueue queue) {
        Puller puller = new Puller();
        puller.thread = new Thread(() -> {
            try {
                puller.received = queue.pull();
            } catch (Throwable caught) {
                puller.failure = caught;
            }
        }, "puller");
        puller.thread.setDaemon(true);
        puller.thread.start();
        return puller;
    }

    /**
     * Spins until the puller is parked inside pull(). WAITING is observable
     * and has no other cause here - without it, the push can win the race and
     * the wake-up tests pass for the wrong reason.
     */
    private static void awaitBlocked(Puller puller) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (puller.thread.getState() == Thread.State.WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        fail("the puller never blocked inside pull()");
    }

    @Test
    void aPushedSignalComesBackAsTheSameInstance() throws InterruptedException {
        SignalQueue queue = new SignalQueue(4);
        Signal pushed = signal("key_down");

        assertTrue(queue.push(pushed));
        assertSame(pushed, queue.pull(0));
    }

    @Test
    void signalsComeOutInTheOrderTheyWentIn() throws InterruptedException {
        SignalQueue queue = new SignalQueue(4);
        queue.push(signal("first"));
        queue.push(signal("second"));

        assertEquals("first", queue.pull(0).name());
        assertEquals("second", queue.pull(0).name());
    }

    @Test
    void pullOnAnEmptyQueueReturnsNullOnceTheTimeoutExpires() throws InterruptedException {
        SignalQueue queue = new SignalQueue(4);

        assertNull(queue.pull(1));
    }

    @Test
    void aSignalRequiresAName() {
        assertThrows(NullPointerException.class, () -> new Signal(null, new Object[0]));
    }

    @Test
    void aFullQueueRefusesTheIncomingSignalAndKeepsTheOldest() throws InterruptedException {
        SignalQueue queue = new SignalQueue(2);
        assertTrue(queue.push(signal("first")));
        assertTrue(queue.push(signal("second")));

        assertFalse(queue.push(signal("third")));
        assertEquals("first", queue.pull(0).name());
    }

    @Test
    void aCapacityBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SignalQueue(0));
    }

    @Test
    void aBlockedPullIsWokenByAPush() throws InterruptedException {
        SignalQueue queue = new SignalQueue(4);
        Puller puller = pull(queue);
        awaitBlocked(puller);

        Signal pushed = signal("key_down");
        queue.push(pushed);

        puller.awaitCompletion();
        assertNull(puller.failure);
        assertSame(pushed, puller.received);
    }

    @Test
    void anInterruptWakesABlockedPuller() throws InterruptedException {
        SignalQueue queue = new SignalQueue(4);
        Puller puller = pull(queue);
        awaitBlocked(puller);

        puller.thread.interrupt();

        puller.awaitCompletion();
        assertInstanceOf(InterruptedException.class, puller.failure);
        assertNull(puller.received);
    }
}