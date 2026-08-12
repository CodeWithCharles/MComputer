package io.github.codewithcharles.mcomputer.luaj;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.luaj.vm2.*;
import org.luaj.vm2.compiler.LuaC;

import io.github.codewithcharles.mcomputer.core.vm.Vm;
import io.github.codewithcharles.mcomputer.core.vm.VmException;
import io.github.codewithcharles.mcomputer.core.vm.VmOutput;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

/**
 * The LuaJ implementation of {@link Vm}.
 *
 * <p><b>{@code JsePlatform.standardGlobals()} is never called here.</b> It
 * installs {@code luajava}, LuaJ's Java reflection library, which is arbitrary
 * code execution on the server and defeats the entire value boundary in one
 * line of Lua. The globals are composed library by library instead, as a
 * whitelist: what is not added is absent.
 *
 * <p>Corollary, and it is guessable from no tutorial: {@code DebugLib} must be
 * <b>loaded</b> for the instruction hook to exist, and {@code debug} must then
 * be <b>removed</b> from the globals - otherwise a script calls
 * {@code debug.sethook(nil)} and disarms the guard that was just installed.
 */
public final class LuaJVm implements Vm {

    /**
     * Text source only. {@code "b"} would accept <b>precompiled Lua bytecode</b>,
     * which reaches the VM below every check this project owns - a malformed
     * chunk is an attack on LuaJ itself, not on our value boundary. Belt and
     * braces: {@code LoadState.install} is deliberately never called either, so
     * no undumper exists even if this string were ever changed.
     */
    private static final String TEXT_SOURCE_ONLY = "t";

    /**
     * Lua's convention for a chunk name that denotes a <b>file</b> rather than a
     * fragment of source. Without it LuaJ renders the location as
     * {@code [string "boot.lua"]:1:}, because a bare name is taken to be the
     * source text itself. Verified by running the embedded jar, not assumed.
     *
     * <p><b>The two error paths want opposite names, and only one can be given.</b>
     * {@code LexState} strips this marker when it builds a compile error's
     * location, and so does the traceback - but {@code LuaClosure}'s runtime
     * prefix copies {@code source} verbatim, marker included. A bare name fixes
     * the runtime path and breaks the compile one; {@code =} leaks exactly like
     * {@code @}. Hence the marker stays and {@link #withoutFileMarker} repairs
     * the one path that needs it.
     */
    private static final String NAMES_A_FILE = "@";

    /**
     * How often the guard regains control. It is not the budget: the budget is a
     * total, this is the grain at which it is spent and at which a stop request
     * is noticed. A Lua loop ignores an interrupt entirely, so this interval is
     * the machine's whole response time to {@code stop()} - a thousand
     * instructions is microseconds, and the check is two field reads.
     */
    private static final int INSTRUCTIONS_PER_CHECK = 1_000;

    private final VmOutput output;
    private final int instructionBudget;
    private final Globals globals;
    private final LuaValue setHook;
    private LuaValue loaded;
    private String chunkName;
    private int remaining;

    /**
     * @param output            where the script's output goes
     * @param instructionBudget how many Lua instructions this run may execute
     *                          before it is killed. Injected, not hardcoded,
     *                          for the same reason as {@code BoundaryLimits}:
     *                          a test wants a ceiling of a thousand, not of a
     *                          million.
     */
    public LuaJVm(VmOutput output, int instructionBudget) {
        this.output = Objects.requireNonNull(output, "output");
        if (instructionBudget <= 0) {
            throw new IllegalArgumentException(
                    "instructionBudget must be > 0, got " + instructionBudget);
        }
        this.instructionBudget = instructionBudget;

        this.globals = new Globals();
        LuaC.install(this.globals);
        this.globals.load(new BaseLib());

        // Two independent locks on one hole, the same shape as TEXT_SOURCE_ONLY
        // plus the absent undumper. BaseLib installs dofile and loadfile AND
        // implements ResourceFinder, so it quietly appoints itself as the reader
        // of the classpath - every resource of every loaded jar. Removing the
        // globals closes the door; nulling the finder removes the room.
        this.globals.set("dofile", LuaValue.NIL);
        this.globals.set("loadfile", LuaValue.NIL);
        this.globals.finder = null;

        this.globals.set("print", new Print());

        // Every LuaJ library except BaseLib ends its installation with
        //     env.get("package").get("loaded").set(name, lib)
        // and PackageLib is deliberately absent, so that line indexes nil and the
        // library fails to load at all. Verified on the embedded jar: DebugLib,
        // StringLib, TableLib, MathLib, CoroutineLib, Bit32Lib and OsLib all carry
        // it. So the registry they write into is built here by hand and dropped
        // below, before a single line of script runs. Installing PackageLib to
        // satisfy them would mean installing java_searcher - a Java class loader -
        // in order to delete it again, which is the wrong direction for a
        // whitelist.
        LuaTable registry = new LuaTable();
        registry.set("loaded", new LuaTable());
        this.globals.set("package", registry);

        this.globals.load(new DebugLib());

        // The corollary that is guessable from no tutorial: the library must be
        // LOADED for the hook to exist, and the table must then be REMOVED, or a
        // script calls debug.sethook(nil) and disarms the guard. The function is
        // captured here because it is the only moment it is reachable.
        this.setHook = this.globals.get("debug").get("sethook");
        this.globals.set("debug", LuaValue.NIL);

        // The scaffold leaves with them. The "package" case of
        // aForbiddenGlobalIsAbsent has been free until now; it is what guards
        // this line.
        this.globals.set("package", LuaValue.NIL);
    }

    @Override
    public void load(byte[] chunk, String chunkName) {
        try {
            this.loaded = globals.load(
                    new ByteArrayInputStream(chunk),
                    NAMES_A_FILE + chunkName,
                    TEXT_SOURCE_ONLY,
                    globals);
        } catch (LuaError e) {
            throw failure(e.getMessage(), e);
        }
        this.chunkName = chunkName;
    }

    @Override
    public void run() {
        if (loaded == null) {
            throw new IllegalStateException("nothing loaded");
        }
        remaining = instructionBudget;
        setHook.invoke(LuaValue.varargsOf(new LuaValue[] {
                new Hook(), LuaValue.EMPTYSTRING, LuaValue.valueOf(INSTRUCTIONS_PER_CHECK) }));
        try {
            loaded.call();
        } catch (Stopped e) {
            // Expected teardown, so no VmException: Machine.stop() asked for
            // this, nothing failed. The interrupt flag is left standing -
            // nothing here cleared it - so a caller that cares can still tell
            // why the run ended.
        } catch (BudgetExhausted e) {
            throw failure(
                    chunkName + ": instruction budget exhausted (" + instructionBudget + ")", e);
        } catch (LuaError e) {
            throw failure(withoutFileMarker(e.getMessage()), e);
        }
    }

    /**
     * Reports a script failure on the script's own output channel, then hands
     * back the exception for the caller to throw.
     *
     * <p>Two audiences, two mechanisms - and now genuinely two <b>contents</b>.
     * The player reads <b>one line</b> on a screen twenty-five rows tall, where a
     * traceback costs four for one error and three of them are noise to him. The
     * exception keeps everything, so nothing is destroyed, only withheld from the
     * audience it does not serve. A single-line message reaches both untouched.
     *
     * <p>Consequence, named because it is real: nothing catches the exception
     * today, so the traceback currently goes nowhere. That is acceptable while a
     * boot script is two lines. It stops being acceptable when a shell calls
     * functions, and that is the moment to give it a destination.
     *
     * <p>The only place this class encodes text on the way out, and it is
     * legitimate exactly because the message is <b>ours</b>, not the script's
     * bytes. A line printed by the script still travels as bytes.
     *
     * <p>Returns rather than throws, so call sites keep {@code throw failure(...)}
     * and the compiler still sees a throw where it is written - the same choice
     * as {@code ComponentException.badArgument}.
     */
    private VmException failure(String message, Throwable cause) {
        output.write(firstLine(message).getBytes(StandardCharsets.UTF_8));
        return new VmException(message, cause);
    }

    private static String firstLine(String message) {
        int lineBreak = message.indexOf('\n');
        return lineBreak < 0 ? message : message.substring(0, lineBreak);
    }

    /**
     * Removes the marker LuaJ copies verbatim into a runtime error's location.
     * See {@link #NAMES_A_FILE} for why this exists on one path and not the
     * other. Expressed against the constant rather than a literal, so the day
     * the marker changes this follows it.
     */
    private String withoutFileMarker(String message) {
        return message.startsWith(NAMES_A_FILE + chunkName)
                ? message.substring(NAMES_A_FILE.length())
                : message;
    }

    /**
     * Replaces BaseLib's print, which renders through {@code tojstring()} - a
     * UTF-8 decode that mutilates the first non-text byte a script prints. That
     * is the exact corruption the byte[] boundary exists to prevent, so the
     * library's version cannot be kept.
     *
     * <p>Rendering is delegated to the globals' own {@code tostring}, which
     * costs nothing and buys the {@code __tostring} metamethod for free.
     */
    private final class Print extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            LuaValue tostring = globals.get("tostring");
            Buffer line = new Buffer();
            for (int i = 1; i <= args.narg(); i++) {
                if (i > 1) {
                    line.append((byte) '\t');
                }
                line.append(tostring.call(args.arg(i)).checkstring());
            }
            output.write(LuaStrings.bytesOf(line.tostring()));
            return NONE;
        }
    }

    /**
     * The budget's trigger, and it is an {@link Error} on purpose.
     *
     * <p>Measured against the embedded jar rather than assumed: {@code pcall}
     * and {@code xpcall} catch {@code LuaError} <b>and</b>
     * {@code java.lang.Exception}. A hook raising either is swallowed by
     * {@code while true do pcall(function() while true do end end) end}, and the
     * budget is defeated while looking installed. Only a {@code Throwable} that
     * is not an {@code Exception} gets through.
     *
     * <p>No stack trace: it would be the interpreter's Java frames, which say
     * nothing about the script that overran.
     */
    private static final class BudgetExhausted extends Error {
        BudgetExhausted() {
            super("instruction budget exhausted", null, false, false);
        }
    }

    /**
     * The run was stopped from outside, and that is not a failure.
     *
     * <p>An {@link Error} for the same measured reason as {@link BudgetExhausted}:
     * {@code pcall} catches {@code LuaError} and {@code java.lang.Exception}, so
     * anything else would let a script swallow its own shutdown and keep a
     * thread alive after the block was broken.
     */
    private static final class Stopped extends Error {
        Stopped() {
            super("stopped", null, false, false);
        }
    }

    private final class Hook extends ZeroArgFunction {

        @Override
        public LuaValue call() {
            if (Thread.currentThread().isInterrupted()) {
                throw new Stopped();
            }
            remaining -= INSTRUCTIONS_PER_CHECK;
            if (remaining <= 0) {
                throw new BudgetExhausted();
            }
            return NIL;
        }
    }
}
