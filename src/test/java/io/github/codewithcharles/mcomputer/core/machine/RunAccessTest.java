package io.github.codewithcharles.mcomputer.core.machine;

import io.github.codewithcharles.mcomputer.core.component.Component;
import io.github.codewithcharles.mcomputer.core.component.ComponentApi;
import io.github.codewithcharles.mcomputer.core.component.ComponentBus;
import io.github.codewithcharles.mcomputer.core.component.ComponentMethod;
import io.github.codewithcharles.mcomputer.core.component.ComponentRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RunAccessTest {

    private static final UUID GPU = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final CallQueue _calls = new CallQueue();
    private final SignalQueue _signals = new SignalQueue(4);
    private final ComponentRegistry _registry = new ComponentRegistry();
    private final ComponentBus _bus = new ComponentBus(_registry);
    private final RunAccess _access = new RunAccess(_calls, _signals, _bus);

    private final AtomicReference<Thread> _ranOn = new AtomicReference<>();

    /**
     * The Lua thread, seen from this suite: it calls, then blocks. What it got
     * back is only readable once it has terminated. A deliberate copy of
     * CallQueueTest's Submitter and SignalQueueTest's Puller, so both stay out
     * of every diff that touches this class.
     */
    private static final class Caller {
        Thread thread;
        volatile Object result;
        volatile Throwable failure;

        void awaitCompletion() throws InterruptedException {
            thread.join(Duration.ofSeconds(2).toMillis());
            assertFalse(thread.isAlive(), "the calling thread never returned");
        }
    }

    /** One call, whichever of the four it is. */
    @FunctionalInterface
    private interface Call {
        Object make() throws InterruptedException;
    }

    private static Caller call(Call body) {
        Caller caller = new Caller();
        caller.thread = new Thread(() -> {
            try {
                caller.result = body.make();
            } catch (Throwable caught) {
                caller.failure = caught;
            }
        }, "caller");
        caller.thread.setDaemon(true);
        caller.thread.start();
        return caller;
    }

    /**
     * Spins until the caller is parked. WAITING is observable and has no other
     * cause here. In most tests below this is a precondition; in the two that
     * say so it is the assertion itself, because a call that did not cross to
     * the server thread would never park at all.
     */
    private static void awaitBlocked(Caller caller) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (caller.thread.getState() == Thread.State.WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        fail("the caller never blocked inside the call");
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

    private void installGpu(ComponentMethod ping) {
        _registry.add(new Component(GPU, ComponentApi.builder("gpu").method("ping", ping).build()));
    }

    private static Signal signal(String name) {
        return new Signal(name, new Object[0]);
    }

    /**
     * The decision this class exists for. A component method runs on the thread
     * that drains, never on the Lua thread that asked - which is what makes the
     * unsynchronised ComponentRegistry safe.
     */
    @Test
    void aComponentCallRunsOnTheThreadThatDrains() throws InterruptedException {
        installGpu(arguments -> {
            _ranOn.set(Thread.currentThread());
            return new Object[0];
        });
        Caller caller = call(() -> _access.invoke(GPU.toString(), "ping", new Object[0]));
        awaitBlocked(caller);

        drainUntil(_calls, 1);
        caller.awaitCompletion();

        assertSame(Thread.currentThread(), _ranOn.get());
    }

    /**
     * The backbone assertion, and it observes no clock: this value cannot exist
     * before the method ran, so receiving it proves the caller waited.
     */
    @Test
    void theCallerGetsWhatTheComponentReturned() throws InterruptedException {
        installGpu(arguments -> new Object[] { 42.0, Boolean.TRUE });
        Caller caller = call(() -> _access.invoke(GPU.toString(), "ping", new Object[0]));
        awaitBlocked(caller);

        drainUntil(_calls, 1);
        caller.awaitCompletion();

        assertNull(caller.failure);
        assertArrayEquals(new Object[] { 42.0, Boolean.TRUE }, (Object[]) caller.result);
    }

    /**
     * Reading the registry is a server-thread act too, and it is the one that
     * looks harmless enough to be "simplified" into a direct call one day.
     * Here awaitBlocked is the assertion rather than a precondition: a direct
     * read would return without ever parking.
     */
    @Test
    void listingComponentsRunsOnTheThreadThatDrains() throws InterruptedException {
        installGpu(arguments -> new Object[0]);
        Caller caller = call(_access::listComponents);
        awaitBlocked(caller);

        drainUntil(_calls, 1);
        caller.awaitCompletion();

        Map<?, ?> listed = (Map<?, ?>) caller.result;
        assertEquals(1, listed.size());
        assertArrayEquals("gpu".getBytes(UTF_8), (byte[]) listed.get(GPU.toString()));
    }

    /**
     * The counterweight, and it is the reason the two halves of this class are
     * not written the same way. A SignalQueue is safe to read from any thread,
     * so a pull that crossed to the server thread would hold the tick's drain
     * for as long as nobody presses a key.
     */
    @Test
    void pullingASignalDoesNotGoThroughTheCallQueue() throws InterruptedException {
        Signal pushed = signal("key_down");
        _signals.push(pushed);

        Caller caller = call(() -> _access.pullSignal(0));

        caller.awaitCompletion();
        assertNull(caller.failure);
        assertSame(pushed, caller.result);
        assertEquals(0, _calls.drain(16), "the pull went through the call queue");
    }

    /**
     * The no-argument overload waits rather than polling. Without this, a
     * delegation to pull(0) passes every other test here and turns a shell
     * parked on pullSignal into a spin - visible in game, never in a test.
     */
    @Test
    void aPullWithNoTimeoutWaitsForASignal() throws InterruptedException {
        Caller caller = call(_access::pullSignal);
        awaitBlocked(caller);

        Signal pushed = signal("key_down");
        _signals.push(pushed);

        caller.awaitCompletion();
        assertNull(caller.failure);
        assertSame(pushed, caller.result);
    }
}