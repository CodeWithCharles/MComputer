package io.github.codewithcharles.mcomputer.core.machine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MachineTest {

    private static final int MAX_TASKS = 8;

    /**
     * A Lua thread, seen from this suite: it submits and blocks. A deliberate
     * copy of CallQueueTest's helper, not a shared one - that suite is worth
     * more staying outside every diff that touches Machine.
     */
    private static final class Submitter {
        Thread thread;
        volatile Object[] result;
        volatile Throwable failure;
    }

    private static Submitter submitTo(Machine machine, CallQueue.ServerTask task) {
        CallQueue queue = machine.callQueue();
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

    /** A precondition, not a timing assumption - see CallQueueTest. */
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

    private static void awaitCompletion(Submitter submitter) throws InterruptedException {
        submitter.thread.join(Duration.ofSeconds(2).toMillis());
        assertFalse(submitter.thread.isAlive(), "the submitting thread never returned");
    }

    @Test
    void aNewMachineIsOff() {
        assertFalse(new Machine(MAX_TASKS).isRunning());
    }

    @Test
    void startingTurnsItOn() {
        Machine machine = new Machine(MAX_TASKS);

        machine.start();

        assertTrue(machine.isRunning());
    }

    @Test
    void stoppingTurnsItOff() {
        Machine machine = new Machine(MAX_TASKS);
        machine.start();

        machine.stop();

        assertFalse(machine.isRunning());
    }

    /**
     * Not idempotence for its own sake: a second start that swapped the queue
     * would strand the Lua thread of the run already in progress on a queue
     * nobody drains.
     */
    @Test
    void startingAnAlreadyRunningMachineKeepsTheSameQueue() {
        Machine machine = new Machine(MAX_TASKS);
        machine.start();
        CallQueue first = machine.callQueue();

        machine.start();

        assertSame(first, machine.callQueue());
    }

    /** The chunk of an idle computer unloads too, and that path calls stop(). */
    @Test
    void stoppingAMachineThatWasNeverStartedIsHarmless() {
        Machine machine = new Machine(MAX_TASKS);

        assertDoesNotThrow(machine::stop);
        assertFalse(machine.isRunning());
    }

    @Test
    void askingAStoppedMachineForItsQueueIsACallerBug() {
        Machine machine = new Machine(MAX_TASKS);

        assertThrows(IllegalStateException.class, machine::callQueue);
    }

    /** The chunk of an idle computer ticks too. */
    @Test
    void tickingAStoppedMachineIsHarmless() {
        assertDoesNotThrow(new Machine(MAX_TASKS)::tick);
    }

    @Test
    void tickRunsWhatTheQueueHolds() throws InterruptedException {
        Machine machine = new Machine(MAX_TASKS);
        machine.start();
        Submitter submitter = submitTo(machine, () -> new Object[] { 42.0 });
        awaitBlocked(submitter);

        machine.tick();

        awaitCompletion(submitter);
        assertArrayEquals(new Object[] { 42.0 }, submitter.result);
    }

    /**
     * The bound is a constructor argument nothing else reads: a tick() calling
     * drain with a literal passes every other test in this suite.
     */
    @Test
    void tickRunsAtMostMaxTasksPerTick() throws InterruptedException {
        Machine machine = new Machine(1);
        machine.start();
        Submitter first = submitTo(machine, () -> new Object[] { 1.0 });
        awaitBlocked(first);
        Submitter second = submitTo(machine, () -> new Object[] { 2.0 });
        awaitBlocked(second);

        machine.tick();

        awaitCompletion(first);
        assertTrue(second.thread.isAlive(), "the second task ran in the same tick");
        machine.stop();
    }
}