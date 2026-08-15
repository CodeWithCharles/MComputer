package io.github.codewithcharles.mcomputer.core.machine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InstructionBudgetTest {

    private static final int PER_TICK = 1000;

    private final InstructionBudget _budget = new InstructionBudget(PER_TICK);

    /**
     * The Lua thread, seen from the test. A deliberate copy of CallQueueTest's
     * Submitter, so that suite stays out of this diff.
     */
    private static final class Spender {
        Thread thread;
        volatile boolean returned;
        volatile Throwable failure;

        void awaitCompletion() throws InterruptedException {
            thread.join(Duration.ofSeconds(2).toMillis());
            assertFalse(thread.isAlive(), "the spending thread never returned");
        }
    }

    private static Spender spend(InstructionBudget budget, int instructions) {
        Spender spender = new Spender();
        spender.thread = new Thread(() -> {
            try {
                budget.spend(instructions);
                spender.returned = true;
            } catch (Throwable caught) {
                spender.failure = caught;
            }
        }, "spender");
        spender.thread.setDaemon(true);
        spender.thread.start();
        return spender;
    }

    /**
     * A precondition, not an assertion about timing: it must really be parked.
     */
    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.WAITING, thread.getState());
    }

    /**
     * Born green, the constructor being the one implemented body. Its red is
     * earned by deleting the guard for ten seconds.
     */
    @Test
    void aRateOfZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new InstructionBudget(0));
    }

    /**
     * A run starts owing nothing, or its first thousand instructions wait a tick.
     */
    @Test
    void theFirstTickIsAlreadyGranted() {
        assertDoesNotThrow(() -> _budget.spend(PER_TICK));
    }

    @Test
    void spendingPastTheAllowanceWaitsForAGrant() throws InterruptedException {
        _budget.spend(PER_TICK);
        Spender spender = spend(_budget, PER_TICK);
        awaitBlocked(spender.thread);

        _budget.grant();

        spender.awaitCompletion();
        assertTrue(spender.returned);
    }

    /**
     * A script parked in pullSignal must not come back in a burst. Two grants
     * are worth one, so the second spend still waits.
     */
    @Test
    void aGrantDoesNotBank() throws InterruptedException {
        _budget.spend(PER_TICK);
        _budget.grant();
        _budget.grant();
        _budget.spend(PER_TICK);

        Spender spender = spend(_budget, 1);

        awaitBlocked(spender.thread);
        assertFalse(spender.returned);
    }

    /**
     * The decision test on the refill. Without the carry the grant would set a
     * full allowance and the last spend would go through, so the average would
     * drift up by whatever each tick overspent.
     */
    @Test
    void whatATickOverspentIsCarried() throws InterruptedException {
        _budget.spend(PER_TICK + 10);
        _budget.grant();
        _budget.spend(PER_TICK - 10);

        Spender spender = spend(_budget, 1);

        awaitBlocked(spender.thread);
        assertFalse(spender.returned);
    }

    @Test
    void aWaitingSpenderIsInterrupted() throws InterruptedException {
        _budget.spend(PER_TICK);
        Spender spender = spend(_budget, 1);
        awaitBlocked(spender.thread);

        spender.thread.interrupt();

        spender.awaitCompletion();
        assertInstanceOf(InterruptedException.class, spender.failure);
    }
}