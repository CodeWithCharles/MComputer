package io.github.codewithcharles.mcomputer.luaj;

import io.github.codewithcharles.mcomputer.core.component.BoundaryLimits;
import io.github.codewithcharles.mcomputer.core.component.ComponentException;
import io.github.codewithcharles.mcomputer.core.machine.InstructionBudget;
import io.github.codewithcharles.mcomputer.core.machine.MachineAccess;
import io.github.codewithcharles.mcomputer.core.machine.Signal;
import io.github.codewithcharles.mcomputer.core.vm.VmException;
import io.github.codewithcharles.mcomputer.core.vm.VmOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class LuaJVmTest {

    /**
     * Not read by anything yet - the hook that spends it lands in a later wave.
     * Named rather than inlined so the day it becomes load-bearing, the call
     * sites do not have to be found again.
     */
    private static final int SOME_BUDGET = 1_000_000;

    private static final String CHUNK_NAME = "boot.lua";

    private static final String ADDRESS = "11111111-2222-3333-4444-555555555555";

    private final List<byte[]> _written = new ArrayList<>();

    private final FakeMachine _machine = new FakeMachine();

    private final LuaJVm _vm =
            new LuaJVm(_written::add, new InstructionBudget(SOME_BUDGET), _machine,
                    BoundaryLimits.defaults());

    /**
     * Asserts on the exact type, never on {@code RuntimeException}: while a body
     * still throws {@code UnsupportedOperationException}, a supertype assertion
     * would go green against a VM that does nothing.
     */
    private VmException loadFailureOf(String source) {
        return assertThrows(VmException.class,
                () -> _vm.load(source.getBytes(UTF_8), CHUNK_NAME));
    }

    private VmException runFailureOf(String source) {
        _vm.load(source.getBytes(UTF_8), CHUNK_NAME);
        return assertThrows(VmException.class, _vm::run);
    }

    private void runSource(String source) {
        _vm.load(source.getBytes(UTF_8), CHUNK_NAME);
        _vm.run();
    }

    private LuaJVm vm(InstructionBudget budget) {
        return new LuaJVm(_written::add, budget, _machine, BoundaryLimits.defaults());
    }

    /**
     * The machine this VM talks to, and the reason the Lua face is testable
     * here at all. A real RunAccess would need a CallQueue and a thread
     * draining it, so every assertion in this suite would become a concurrency
     * assertion - which is what the port was introduced to avoid.
     *
     * <p>Two overrides drop the {@code throws InterruptedException} they are
     * allowed to drop, which says in the signature that they cannot block.
     */
    private static final class FakeMachine implements MachineAccess {
        final Map<String, byte[]> components = new LinkedHashMap<>();
        final List<Object[]> calls = new ArrayList<>();
        Object[] result = new Object[0];
        RuntimeException failure;
        Signal next;
        boolean interruptOnPull;
        boolean pulledWithoutTimeout;
        long lastTimeoutMillis = Long.MIN_VALUE;
        boolean interruptOnInvoke;

        @Override
        public Map<String, byte[]> listComponents() {
            return components;
        }

        @Override
        public Object[] invoke(String address, String methodName, Object[] arguments)
                throws InterruptedException
        {
            calls.add(new Object[] { address, methodName, arguments });
            if (interruptOnInvoke) {
                throw new InterruptedException();
            }
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public Signal pullSignal() throws InterruptedException {
            pulledWithoutTimeout = true;
            return pulled();
        }

        @Override
        public Signal pullSignal(long timeoutMillis) throws InterruptedException {
            lastTimeoutMillis = timeoutMillis;
            return pulled();
        }

        private Signal pulled() throws InterruptedException {
            if (interruptOnPull) {
                throw new InterruptedException();
            }
            return next;
        }
    }

    /**
     * Guards the shape rather than the mechanism. The chunk name used to appear
     * twice - once in our prefix, once in LuaJ's location - which on eighty
     * columns is width spent saying the same thing. LuaJ locates a compile
     * failure itself, so we add nothing.
     */
    @Test
    public void aCompileFailureStartsWithTheChunkNameOnce() {
        VmException thrown = loadFailureOf("local x =");

        assertTrue(thrown.getMessage().startsWith(CHUNK_NAME + ":"),
                "message was: " + thrown.getMessage());
    }

    /**
     * The same assertion on the other path, and it is not redundant: measured on
     * the embedded jar, the two paths render the chunk name differently.
     * {@code LexState} strips the '@' that marks a file name and so does the
     * traceback, but {@code LuaClosure}'s own runtime prefix copies the source
     * verbatim - so a player reads {@code @boot.lua:2}. No chunk name satisfies
     * both paths, which is why one of them is repaired by hand.
     */
    @Test
    public void aRuntimeFailureStartsWithTheChunkNameOnce() {
        VmException thrown = runFailureOf("local f = nil; f()");

        assertTrue(thrown.getMessage().startsWith(CHUNK_NAME + ":"),
                "message was: " + thrown.getMessage());
    }

    /**
     * Also the happy path, observed from behind. A runtime error can only be
     * reached by a chunk that compiled <b>and</b> ran, so this fails if either
     * half is missing - which is more than a test asserting that a valid chunk
     * does not throw could ever say.
     *
     * <p>It is now also the only test proving {@code load} and {@code run}
     * compose: the split would be undetectable from the two failure tests alone.
     */
    @Test
    public void aRuntimeErrorRaisesVmException() {
        VmException thrown = runFailureOf("local f = nil; f()");

        assertTrue(thrown.getMessage().contains("attempt to call"),
                "message was: " + thrown.getMessage());
    }

    /**
     * Guards a decision rather than a mechanism. Without it, {@code load} could
     * hand LuaJ a literal in place of its {@code chunkName} parameter and the
     * other tests would stay green.
     *
     * <p>Asserted on the <b>compile</b> failure on purpose: {@code LexState}
     * builds {@code chunkname:line:} itself, whereas a runtime error only gains
     * a location once {@code DebugLib} is installed - which it is not yet.
     */
    @Test
    public void theChunkNameReachesLuaJ() {
        VmException thrown = loadFailureOf("local x =");

        assertTrue(thrown.getMessage().contains(CHUNK_NAME + ":1:"),
                "message was: " + thrown.getMessage());
    }

    /**
     * Not a state to branch on. The same call as {@code Machine.callQueue()}
     * throwing when the machine is off: asking a VM to run what it was never
     * given is the caller's bug, and returning quietly would let a machine
     * report itself as running while executing nothing.
     */
    @Test
    public void runningBeforeLoadingIsACallerBug() {
        assertThrows(IllegalStateException.class, _vm::run);
    }

    /**
     * Load-bearing for the test below it, and the reason it comes first. Every
     * absence assertion is written <i>in Lua</i>, so a VM carrying no library at
     * all would satisfy all of them at once - the suite would be green against a
     * sandbox that is merely empty rather than deliberately pruned. This is what
     * makes the absences mean something.
     */
    @Test
    public void aScriptCanCallTheBaseLibrary() {
        assertDoesNotThrow(() -> runSource("assert(type(1) == 'number')"));
    }

    /**
     * Asked in Lua rather than in Java on purpose: what matters is not what the
     * globals table holds, it is what a player's script can name.
     *
     * <p>{@code debug} is in the list although {@code DebugLib} is not installed
     * yet, so it passes for free today. That is deliberate - it is the guard
     * waiting for the wave that loads the library for its hook and must then
     * remove the table, which is the one manoeuvre in this class that is
     * guessable from no tutorial.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "luajava", "require", "package", "io", "os",
            "dofile", "loadfile", "debug" })
    public void aForbiddenGlobalIsAbsent(String name) {
        assertDoesNotThrow(() -> runSource(
                "if " + name + " ~= nil then error('" + name + " is reachable') end"));
    }

    @Test
    public void printReachesTheSink() {
        runSource("print('hello')");

        assertEquals(1, _written.size());
        assertArrayEquals("hello".getBytes(UTF_8), _written.get(0));
    }

    /**
     * One {@code write} per {@code print}, arguments joined by a tab - Lua's own
     * convention. The line break is deliberately not appended: {@link VmOutput}
     * takes a line, and how a line is terminated is the sink's business, not the
     * VM's.
     */
    @Test
    public void printJoinsItsArgumentsWithATab() {
        runSource("print('a', 1, true)");

        assertEquals(1, _written.size());
        assertArrayEquals("a\t1\ttrue".getBytes(UTF_8), _written.get(0));
    }

    /**
     * Guards the decision rather than the mechanism, and re-enacts the bug the
     * whole value boundary exists to prevent. {@code '\255'} is a one-byte Lua
     * string produced by the lexer alone - no StringLib needed. Rendered through
     * {@code tojstring()} it comes back as U+FFFD, three bytes, and a player's
     * binary file is corrupted on its first round trip.
     */
    @Test
    public void printKeepsBytesThatAreNotText() {
        runSource("print('\\255')");

        assertArrayEquals(new byte[] { (byte) 0xFF }, _written.get(0));
    }

    /**
     * The rate made visible. What is asserted is that the thread parks rather
     * than that the run ends, and a budget of a thousand rather than the
     * field's million is what proves the argument reaches the hook.
     */
    @Test
    public void anEndlessLoopParksOnTheBudget() {
        LuaJVm vm = vm(new InstructionBudget(1_000));
        vm.load("while true do end".getBytes(UTF_8), CHUNK_NAME);
        Thread thread = new Thread(() -> {
            try {
                vm.run();
            } catch (Throwable ignored) {
                // The interrupt below ends this thread; nothing here reads it.
            }
        }, "lua-under-test");
        thread.setDaemon(true);
        thread.start();

        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertEquals(Thread.State.WAITING, thread.getState());
        thread.interrupt();
    }

    /**
     * The attack the Error exists for. The hook now raises only Stopped, so
     * this is where "pcall must not catch it" is guarded. Earn its red by
     * making Hook throw a LuaError for ten seconds and watching it hit the
     * deadline while its neighbours stay green.
     */
    @Test
    public void aScriptCannotSwallowAStopWithPcall() throws InterruptedException {
        LuaJVm vm = vm(new InstructionBudget(Integer.MAX_VALUE));
        vm.load("while true do pcall(function() while true do end end) end"
                .getBytes(UTF_8), CHUNK_NAME);
        Thread thread = new Thread(vm::run, "lua-under-test");
        thread.setDaemon(true);
        thread.start();

        thread.interrupt();

        thread.join(Duration.ofSeconds(5).toMillis());
        assertFalse(thread.isAlive(), "the script swallowed its own stop");
    }

    /**
     * The budget is {@code Integer.MAX_VALUE} on purpose. With a small one this
     * test would pass against a hook that ignores the interrupt entirely - the
     * loop would simply die of exhaustion and the thread would end all the same.
     * Only an unreachable budget makes the interrupt the sole possible cause.
     *
     * <p>No precondition instrument here, unlike MachineTest: an interrupt sets
     * a flag that persists until something reads it, so it cannot be delivered
     * too early. Interrupting before the loop has even begun works.
     */
    @Test
    public void anInterruptedScriptStopsWithoutFailing() throws InterruptedException {
        LuaJVm vm = vm(new InstructionBudget(Integer.MAX_VALUE));
        vm.load("while true do end".getBytes(UTF_8), CHUNK_NAME);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                vm.run();
            } catch (Throwable caught) {
                failure.set(caught);
            }
        }, "lua-under-test");
        thread.setDaemon(true);
        thread.start();
        thread.interrupt();

        thread.join(Duration.ofSeconds(5).toMillis());
        assertFalse(thread.isAlive(), "the script never answered the interrupt");
        assertNull(failure.get(), "stopping a machine is not a failure");
    }

    /**
     * Inverted deliberately. It used to assert that both audiences receive the
     * same string; the javadoc of {@code failure} has always claimed "two
     * audiences, two mechanisms", and until now the two contents were identical,
     * so the claim cost nothing. A screen twenty-five rows tall makes it cost
     * four for one error, three of which say nothing to a player.
     *
     * <p>The second assertion is what makes this a guard: without it the test
     * would still pass against a VM that truncated the exception too, and the
     * traceback would be gone from both places at once.
     */
    @Test
    public void thePlayerGetsOneLineAndTheExceptionKeepsTheRest() {
        VmException thrown = runFailureOf("local f = nil; f()");

        assertEquals(1, _written.size());
        String shown = new String(_written.get(0), UTF_8);

        assertEquals(thrown.getMessage().lines().findFirst().orElseThrow(), shown);
        assertTrue(thrown.getMessage().length() > shown.length(),
                "the traceback should have stayed in the exception");
    }

    @Test
    public void aChunkThatDoesNotCompileIsReportedTheSameWay() {
        VmException thrown = loadFailureOf("local x =");

        assertEquals(1, _written.size());
        assertEquals(thrown.getMessage(), new String(_written.get(0), UTF_8));
    }

    /**
     * Asked in Lua, like every reachability assertion in this suite: what
     * matters is what a script can read, not what the globals table holds.
     */
    @Test
    public void componentListGivesWhatTheMachineHolds() {
        _machine.components.put(ADDRESS, "gpu".getBytes(UTF_8));

        runSource("local found = component.list()['" + ADDRESS + "']\n"
                + "assert(found == 'gpu', 'got ' .. tostring(found))");
    }

    @Test
    public void componentInvokeForwardsTheAddressTheMethodAndTheArguments() {
        runSource("component.invoke('" + ADDRESS + "', 'set', 1, 'x')");

        Object[] call = _machine.calls.get(0);
        assertEquals(ADDRESS, call[0]);
        assertEquals("set", call[1]);
        assertArrayEquals(new Object[] { 1.0, "x".getBytes(UTF_8) }, (Object[]) call[2]);
    }

    /**
     * Several return values, which is why ComponentMethod has returned an array
     * since the first day. A face collapsing them to one would pass every other
     * test here.
     */
    @Test
    public void componentInvokeGivesEveryReturnValueBackToLua() {
        _machine.result = new Object[] { 42.0, Boolean.TRUE };

        runSource("local a, b = component.invoke('" + ADDRESS + "', 'ping')\n"
                + "assert(a == 42 and b == true, 'got ' .. tostring(a) .. ' ' .. tostring(b))");
    }

    /**
     * The script-error half of the split, and its shape was measured rather
     * than assumed: LuaJ wraps any RuntimeException a Java function throws into
     * a LuaError reading "vm error: &lt;java class&gt;: ...". Left on that path a
     * ComponentException would show a player the name of one of our classes.
     *
     * <p>The pcall is not decoration. Milestone 5 rests on a shell surviving a
     * user script that calls a component badly, and this is where that becomes
     * true rather than hoped for.
     */
    @Test
    public void aComponentErrorReachesTheScriptAsAnOrdinaryLuaError() {
        _machine.failure = new ComponentException(
                "bad argument #1 to 'set' (string expected, got number)");

        runSource("local ok, err = pcall(component.invoke, '" + ADDRESS + "', 'set')\n"
                + "assert(ok == false, 'the bad call did not fail')\n"
                + "print(err)");

        String shown = new String(_written.get(0), UTF_8);
        assertTrue(shown.contains("bad argument #1 to 'set' (string expected, got number)"),
                "message was: " + shown);
        assertFalse(shown.contains("ComponentException"), "message was: " + shown);
    }

    /**
     * The other half, and the one measurement rewrote. Our own fault has to
     * leave as itself, so that Machine's thread body - which catches
     * VmException and only that - lets it reach the JVM's default handler.
     *
     * <p>The source wraps the call in a pcall on purpose: proving it escapes
     * one proves it escapes a bare call too, and it is the same property the
     * budget test above relies on.
     */
    @Test
    public void aBugInTheHostIsNotReportedAsTheScriptsFault() {
        IllegalStateException boom = new IllegalStateException("boom");
        _machine.failure = boom;
        _vm.load(("pcall(component.invoke, '" + ADDRESS + "', 'ping')").getBytes(UTF_8), CHUNK_NAME);

        assertSame(boom, assertThrows(IllegalStateException.class, _vm::run));
    }

    /**
     * The interrupt-to-Stopped translation on the component path, for the same
     * measured reason as the hook's: pcall catches java.lang.Exception, so an
     * InterruptedException let loose would be swallowed and a machine asked to
     * stop would carry on.
     *
     * <p>Thread.interrupted() rather than isInterrupted(): it asserts and
     * clears in one call, and the flag must not be left standing on the JUnit
     * thread, where the next test's hook would read it.
     */
    @Test
    public void anInterruptDuringAComponentCallStopsTheRunWithoutFailing() {
        _machine.interruptOnInvoke = true;
        _vm.load(("pcall(component.invoke, '" + ADDRESS + "', 'ping')\nprint('still running')")
                .getBytes(UTF_8), CHUNK_NAME);

        assertDoesNotThrow(_vm::run);
        boolean flagWasPutBack = Thread.interrupted();

        assertTrue(_written.isEmpty(), "the script kept running after being stopped");
        assertTrue(flagWasPutBack, "the interrupt flag was not put back");
    }

    /**
     * The default timeout is infinite, and this sandbox cannot name math.huge:
     * MathLib is not installed, so passing no argument is the ordinary route
     * to it. Without this test a default of some large millisecond count
     * passes everything else, and a shell parked on pullSignal wakes for
     * nothing.
     */
    @Test
    public void pullSignalWithNoArgumentWaitsWithoutATimeout() {
        _machine.next = new Signal("key_down", new Object[0]);

        runSource("computer.pullSignal()");

        assertTrue(_machine.pulledWithoutTimeout, "a timeout was passed");
    }

    /** The other route to the same branch, and the one an OC script takes. */
    @Test
    public void anInfiniteTimeoutWaitsWithoutATimeout() {
        _machine.next = new Signal("key_down", new Object[0]);

        runSource("computer.pullSignal(1/0)");

        assertTrue(_machine.pulledWithoutTimeout, "a timeout was passed");
    }

    /** Lua speaks seconds, core speaks milliseconds. 0.25 is exact in both. */
    @Test
    public void pullSignalConvertsSecondsToMilliseconds() {
        runSource("computer.pullSignal(0.25)");

        assertEquals(250L, _machine.lastTimeoutMillis);
    }

    /**
     * A signal arrives unpacked, name first. The name is a String on the Java
     * side and the converter refuses one as a value, so it cannot travel that
     * way and this is what pins the shape.
     */
    @Test
    public void aPulledSignalArrivesUnpacked() {
        _machine.next = new Signal("key_down",
                new Object[] { ADDRESS.getBytes(UTF_8), 97.0, 30.0 });

        runSource("local name, address, char = computer.pullSignal()\n"
                + "assert(name == 'key_down', 'name was ' .. tostring(name))\n"
                + "assert(address == '" + ADDRESS + "', 'address was ' .. tostring(address))\n"
                + "assert(char == 97, 'char was ' .. tostring(char))");
    }

    @Test
    public void aPullThatTimesOutReturnsNothing() {
        runSource("assert(computer.pullSignal(0) == nil)");
    }

    /**
     * Same translation as on the component path, on a second call site that
     * could forget it. Thread.interrupted() asserts and clears in one call: the
     * flag must not be left standing on the JUnit thread.
     */
    @Test
    public void anInterruptDuringAPullStopsTheRunWithoutFailing() {
        _machine.interruptOnPull = true;
        _vm.load("computer.pullSignal()\nprint('still running')".getBytes(UTF_8), CHUNK_NAME);

        assertDoesNotThrow(_vm::run);
        boolean flagWasPutBack = Thread.interrupted();

        assertTrue(_written.isEmpty(), "the script kept running after being stopped");
        assertTrue(flagWasPutBack, "the interrupt flag was not put back");
    }

    /**
     * The path we cannot repair: what pcall hands back is built by LuaJ and
     * never reaches our catch. This is why the chunk name is bare and the
     * compile message is the one repaired, and it is the reverse of the
     * 2026-08-12 choice - made before a script could catch anything.
     */
    @Test
    public void theMessageAScriptCatchesCarriesNoMarker() {
        runSource("local ok, err = pcall(function() local f = nil; f() end)\nprint(err)");

        String shown = new String(_written.get(0), UTF_8);
        assertTrue(shown.startsWith(CHUNK_NAME + ":"), "message was: " + shown);
    }

    /**
     * BaseLib's load defaults to mode "bt", and Globals.loadPrototype tests the
     * binary mode first, so with no undumper installed it answers
     * "No undumper." for every input - measured on the jar. A shell compiling a
     * user's file would never get a chunk back.
     */
    @Test
    public void aScriptCanCompileSource() {
        runSource("local f = load('return 1 + 1')\n"
                + "assert(f, 'load returned nil')\n"
                + "assert(f() == 2, 'the chunk did not run')");
    }

    /**
     * The other half: a syntax error has to come back as one, or a shell cannot
     * tell a user's typo from a broken sandbox.
     */
    @Test
    public void aScriptGetsASyntaxErrorFromLoad() {
        runSource("local f, err = load('local x =')\n"
                + "assert(f == nil, 'load accepted a bad chunk')\n"
                + "print(err)");

        String shown = new String(_written.get(0), UTF_8);
        assertTrue(shown.contains("unexpected symbol"), "message was: " + shown);
    }

    /**
     * Guards a decision. The mode belongs to this class, not to the caller:
     * precompiled bytecode reaches the VM below every check the project owns.
     * A script asking for it gets text compilation instead, so the request is
     * ignored rather than refused - the second lock, an absent undumper, is
     * untouched either way.
     */
    @Test
    public void aScriptCannotAskForBinaryChunks() {
        runSource("local f, err = load('return 3', 'user', 'b')\n"
                + "assert(f ~= nil, 'the mode was not overridden: ' .. tostring(err))\n"
                + "assert(f() == 3, 'the chunk did not run')");
    }
}