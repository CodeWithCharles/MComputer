package io.github.codewithcharles.mcomputer.luaj;

import io.github.codewithcharles.mcomputer.core.vm.VmException;
import io.github.codewithcharles.mcomputer.core.vm.VmOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class LuaJVmTest {

    /**
     * Not read by anything yet - the hook that spends it lands in a later wave.
     * Named rather than inlined so the day it becomes load-bearing, the call
     * sites do not have to be found again.
     */
    private static final int SOME_BUDGET = 1_000_000;

    private static final String CHUNK_NAME = "boot.lua";

    private final List<byte[]> _written = new ArrayList<>();

    private final LuaJVm _vm = new LuaJVm(_written::add, SOME_BUDGET);

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
     * The budget is given as 1000 rather than reusing the field's million, so
     * that the constructor argument is proven to reach the hook. Without a small
     * value here nothing distinguishes an injected budget from a literal.
     *
     * <p>The timeout is liveness, not synchronisation - the same instrument as
     * {@code join(timeout)} in CallQueueTest, and the same rule: a working budget
     * never approaches the deadline, only a broken one waits. It has to be
     * preemptive because a Lua loop does not return on its own.
     */
    @Test
    public void anEndlessLoopExhaustsTheBudget() {
        LuaJVm vm = new LuaJVm(_written::add, 1_000);
        vm.load("while true do end".getBytes(UTF_8), CHUNK_NAME);

        VmException thrown = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(VmException.class, vm::run));

        assertTrue(thrown.getMessage().contains("budget"),
                "message was: " + thrown.getMessage());
    }

    /**
     * The attack the Error exists for. Born green against the implementation
     * above, so earn its red: make {@code Hook} throw
     * {@code new LuaError("budget")} for ten seconds and watch this test hit its
     * deadline while the two above stay green. That divergence is the whole
     * argument, and a comment would not fail the day someone tidies the Error
     * away.
     */
    @Test
    public void aScriptCannotSwallowTheBudgetWithPcall() {
        LuaJVm vm = new LuaJVm(_written::add, 1_000);
        vm.load("while true do pcall(function() while true do end end) end"
                .getBytes(UTF_8), CHUNK_NAME);

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(VmException.class, vm::run));
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
        LuaJVm vm = new LuaJVm(_written::add, Integer.MAX_VALUE);
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
}