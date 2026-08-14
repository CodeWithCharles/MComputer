package io.github.codewithcharles.mcomputer.core.machine;

import io.github.codewithcharles.mcomputer.core.component.Component;
import io.github.codewithcharles.mcomputer.core.component.ComponentApi;
import io.github.codewithcharles.mcomputer.core.vm.Vm;
import io.github.codewithcharles.mcomputer.core.vm.VmException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class MachineTest {

    private static final int MAX_TASKS = 8;
    private static final int SIGNAL_CAPACITY = 8;
    private static final byte[] BOOT = "print('hi')".getBytes(StandardCharsets.UTF_8);

    private final Vms _vms = new Vms();

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

    /**
     * The port's first test seam actually used, which is what makes {@code Vm}
     * pass the arbiter on its own merits rather than only because ArchUnit
     * forbids core from naming LuaJ. This suite drives a whole boot sequence
     * with no LuaJ on the classpath of the assertion.
     */
    private static final class FakeVm implements Vm {
        byte[] loadedChunk;
        String loadedName;
        VmException failOnLoad;
        volatile VmException failOnRun;
        final CountDownLatch started = new CountDownLatch(1);
        volatile Thread ranOn;
        volatile boolean interrupted;
        boolean blockUntilInterrupted;
        boolean pullUntilSignal;
        volatile MachineAccess access;
        volatile Signal pulled;

        @Override
        public void load(byte[] chunk, String chunkName) {
            if (failOnLoad != null) {
                throw failOnLoad;
            }
            loadedChunk = chunk;
            loadedName = chunkName;
        }

        @Override
        public void run() {
            ranOn = Thread.currentThread();
            started.countDown();
            if (failOnRun != null) {
                throw failOnRun;
            }
            if (pullUntilSignal) {
                try {
                    pulled = access.pullSignal();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                return;
            }
            if (!blockUntilInterrupted) {
                return;
            }
            try {
                // Never counted down: only an interrupt ends this wait. A real
                // Lua loop ignores the flag until its hook looks at it, which is
                // the next step; the observation is the same either way.
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
    }

    private static final class Vms implements VmFactory {
        final List<FakeVm> produced = new ArrayList<>();
        VmException failOnLoad;
        volatile VmException failOnRun;
        boolean blockUntilInterrupted;
        boolean pullUntilSignal;

        @Override
        public Vm create(MachineAccess access) {
            FakeVm vm = new FakeVm();
            vm.access = access;
            vm.blockUntilInterrupted = blockUntilInterrupted;
            vm.pullUntilSignal = pullUntilSignal;
            vm.failOnLoad = failOnLoad;
            vm.failOnRun = failOnRun;
            produced.add(vm);
            return vm;
        }
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

    private Machine machine(int maxTasks) {
        return new Machine(maxTasks, _vms, BOOT, "boot.lua", SIGNAL_CAPACITY);
    }

    private static Signal signal(String name) {
        return new Signal(name, new Object[0]);
    }

    @Test
    void aNewMachineIsOff() {
        assertFalse(machine(MAX_TASKS).isRunning());
    }

    @Test
    void startingTurnsItOn() {
        Machine machine = machine(MAX_TASKS);

        machine.start();

        assertTrue(machine.isRunning());
    }

    @Test
    void stoppingTurnsItOff() {
        Machine machine = machine(MAX_TASKS);
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
        Machine machine = machine(MAX_TASKS);
        machine.start();
        CallQueue first = machine.callQueue();

        machine.start();

        assertSame(first, machine.callQueue());
    }

    /** The chunk of an idle computer unloads too, and that path calls stop(). */
    @Test
    void stoppingAMachineThatWasNeverStartedIsHarmless() {
        Machine machine = machine(MAX_TASKS);

        assertDoesNotThrow(machine::stop);
        assertFalse(machine.isRunning());
    }

    @Test
    void askingAStoppedMachineForItsQueueIsACallerBug() {
        Machine machine = machine(MAX_TASKS);

        assertThrows(IllegalStateException.class, machine::callQueue);
    }

    /** The chunk of an idle computer ticks too. */
    @Test
    void tickingAStoppedMachineIsHarmless() {
        assertDoesNotThrow(machine(MAX_TASKS)::tick);
    }

    @Test
    void tickRunsWhatTheQueueHolds() throws InterruptedException {
        Machine machine = machine(MAX_TASKS);
        _vms.blockUntilInterrupted = true;
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
        Machine machine = machine(1);
        _vms.blockUntilInterrupted = true;
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

    @Test
    void startingLoadsTheBootScript() {
        machine(MAX_TASKS).start();

        assertEquals(1, _vms.produced.size());
        assertArrayEquals(BOOT, _vms.produced.get(0).loadedChunk);
        assertEquals("boot.lua", _vms.produced.get(0).loadedName);
    }

    /**
     * The whole reason load and run are two methods. Compilation happens on the
     * server thread, inside start(), so a script that does not compile stops the
     * machine from ever being on - rather than killing it a moment later from a
     * thread the caller cannot see.
     *
     * <p>It also pins the ordering: the queue must be built <b>after</b> the
     * load succeeds, or a failed boot leaves a machine reporting itself as
     * running.
     */
    @Test
    void aBootScriptThatDoesNotCompileLeavesTheMachineOff() {
        Machine machine = machine(MAX_TASKS);
        _vms.failOnLoad = new VmException("boot.lua does not compile");

        assertThrows(VmException.class, machine::start);
        assertFalse(machine.isRunning());
    }

    /** The same invariant as one CallQueue per run, for the same reason. */
    @Test
    void eachRunGetsItsOwnVm() {
        Machine machine = machine(MAX_TASKS);

        machine.start();
        machine.stop();
        machine.start();

        assertEquals(2, _vms.produced.size());
        assertNotSame(_vms.produced.get(0), _vms.produced.get(1));
    }

    @Test
    void theScriptRunsOnItsOwnThread() throws InterruptedException {
        machine(MAX_TASKS).start();
        FakeVm vm = _vms.produced.get(0);

        assertTrue(vm.started.await(2, TimeUnit.SECONDS), "the script never ran");
        assertNotSame(Thread.currentThread(), vm.ranOn);
    }

    /**
     * Two instruments, because a run can be stopped in two states. A thread
     * parked in submit() is released by the queue's shutdown; a thread that is
     * running is reached by the interrupt. stop() must do both, and this test
     * covers the second - the first is CallQueueTest's.
     */
    @Test
    void stoppingInterruptsTheLuaThread() throws InterruptedException {
        Machine machine = machine(MAX_TASKS);
        _vms.blockUntilInterrupted = true;
        machine.start();
        FakeVm vm = _vms.produced.get(0);
        assertTrue(vm.started.await(2, TimeUnit.SECONDS), "the script never ran");

        machine.stop();

        vm.ranOn.join(Duration.ofSeconds(2).toMillis());
        assertFalse(vm.ranOn.isAlive(), "the Lua thread never returned");
        assertTrue(vm.interrupted, "it returned, but not because of the interrupt");
    }

    /**
     * A run ends, whether well or badly, and the machine has to notice. It is
     * noticed on the tick because that is the one moment the server thread is
     * already looking at this machine - anything else would need the Lua thread
     * to mutate the run it is running in, from the wrong thread.
     */
    @Test
    void aMachineTurnsItselfOffWhenTheScriptEnds() throws InterruptedException {
        Machine machine = machine(MAX_TASKS);
        machine.start();
        FakeVm vm = _vms.produced.get(0);
        assertTrue(vm.started.await(2, TimeUnit.SECONDS), "the script never ran");
        vm.ranOn.join(Duration.ofSeconds(2).toMillis());

        machine.tick();

        assertFalse(machine.isRunning());
    }

    /** The other half, and the one that keeps the check from being "always stop". */
    @Test
    void aTickWhileTheScriptIsStillRunningChangesNothing() {
        Machine machine = machine(MAX_TASKS);
        _vms.blockUntilInterrupted = true;
        machine.start();

        machine.tick();

        assertTrue(machine.isRunning());
    }

    /**
     * The other half of the split, and the reason the catch names VmException
     * rather than RuntimeException: a script's fault is already reported, ours
     * must stay loud.
     */
    @Test
    void aScriptFailureDoesNotEscapeAsAnUncaughtException() throws InterruptedException {
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, caught) -> uncaught.set(caught));
        try {
            Machine machine = machine(MAX_TASKS);
            _vms.failOnRun = new VmException("boom");
            machine.start();
            FakeVm vm = _vms.produced.get(0);
            assertTrue(vm.started.await(2, TimeUnit.SECONDS), "the script never ran");
            vm.ranOn.join(Duration.ofSeconds(2).toMillis());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }

        assertNull(uncaught.get(), "the failure reached the JVM's default handler");
    }

    @Test
    void pushingASignalToAStoppedMachineIsRefused() {
        assertFalse(machine(MAX_TASKS).pushSignal(signal("key_down")));
    }

    @Test
    void askingAStoppedMachineForItsSignalQueueIsACallerBug() {
        assertThrows(IllegalStateException.class, machine(MAX_TASKS)::signalQueue);
    }

    @Test
    void aPushedSignalReachesTheRunningMachinesQueue() throws InterruptedException {
        Machine machine = machine(MAX_TASKS);
        _vms.blockUntilInterrupted = true;
        machine.start();
        Signal pushed = signal("key_down");

        assertTrue(machine.pushSignal(pushed));
        assertSame(pushed, machine.signalQueue().pull(0));
        machine.stop();
    }

    /**
     * The capacity is a constructor argument nothing else reads - same role as
     * tickRunsAtMostMaxTasksPerTick, without which "tune it later" never tunes
     * anything.
     */
    @Test
    void theSignalQueueCapacityIsTheConstructorsNumber() throws InterruptedException {
        Machine machine = new Machine(MAX_TASKS, _vms, BOOT, "boot.lua", 1);
        _vms.blockUntilInterrupted = true;
        machine.start();

        assertTrue(machine.pushSignal(signal("first")));
        assertFalse(machine.pushSignal(signal("second")));
        machine.stop();
    }

    /** A machine reboots, it does not resume - now said about signals too. */
    @Test
    void aRebootStartsWithAFreshSignalQueue() throws InterruptedException {
        Machine machine = machine(MAX_TASKS);
        _vms.blockUntilInterrupted = true;
        machine.start();
        machine.pushSignal(signal("stale"));

        machine.stop();
        machine.start();

        assertNull(machine.signalQueue().pull(0));
        machine.stop();
    }

    /**
     * The registry the machine hands out and the bus it hands out are wired to
     * each other - a bus built on its own private registry passes every other
     * test and resolves nothing.
     */
    @Test
    void aComponentInstalledWhileOffIsVisibleThroughTheBus() {
        Machine machine = machine(MAX_TASKS);

        machine.components().add(
                new Component(UUID.randomUUID(), ComponentApi.builder("gpu").build()));

        assertEquals(1, machine.componentBus().list().size());
    }

    /**
     * Hardware is per MACHINE, execution is per RUN. Installed components
     * survive a reboot; the queues deliberately do not.
     */
    @Test
    void theInstalledComponentsSurviveAReboot() {
        Machine machine = machine(MAX_TASKS);
        machine.components().add(
                new Component(UUID.randomUUID(), ComponentApi.builder("disk").build()));

        machine.start();
        machine.stop();
        machine.start();

        assertEquals(1, machine.componentBus().list().size());
        machine.stop();
    }

    /**
     * The access handed to the VM has to be wired to the queues the run
     * actually uses. A start() that built fresh ones for the RunAccess passes
     * every other test in this suite, and what a player would see is a script
     * blocked forever on a queue nobody writes to, with nothing in the log.
     *
     * <p>No precondition on the Lua thread being parked: the push lands either
     * before the pull or while it waits, and both give the signal back. What
     * discriminates is the bounded join, which only the right queue satisfies.
     */
    @Test
    void theVmPullsFromTheRunsOwnSignalQueue() throws InterruptedException {
        Machine machine = machine(MAX_TASKS);
        _vms.pullUntilSignal = true;
        machine.start();
        FakeVm vm = _vms.produced.get(0);
        assertTrue(vm.started.await(2, TimeUnit.SECONDS), "the script never ran");

        Signal pushed = signal("key_down");
        assertTrue(machine.pushSignal(pushed));

        vm.ranOn.join(Duration.ofSeconds(2).toMillis());
        assertFalse(vm.ranOn.isAlive(), "the Lua thread never returned");
        assertSame(pushed, vm.pulled);
    }
}