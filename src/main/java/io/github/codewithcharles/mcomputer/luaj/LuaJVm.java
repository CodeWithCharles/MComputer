package io.github.codewithcharles.mcomputer.luaj;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import io.github.codewithcharles.mcomputer.core.component.BoundaryLimits;
import io.github.codewithcharles.mcomputer.core.machine.MachineAccess;
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
 * The LuaJ implementation of {@link Vm}: the sandbox, the chunk, the budget.
 * What the machine exposes to a script lives in {@link MachineGlobals}.
 *
 * <p><b>{@code JsePlatform.standardGlobals()} is never called here.</b> It
 * installs {@code luajava}, LuaJ's Java reflection library, which is arbitrary
 * code execution on the server. The globals are composed library by library
 * instead: what is not added is absent.
 *
 * <p>{@code DebugLib} must be loaded for the instruction hook to exist, and
 * {@code debug} must then be removed, or a script calls
 * {@code debug.sethook(nil)} and disarms the guard.
 */
public final class LuaJVm implements Vm {

    /**
     * Text source only. {@code "b"} would accept precompiled Lua bytecode,
     * which reaches the VM below every check this project owns.
     * {@code LoadState.install} is never called either, so no undumper exists
     * even if this string changed.
     */
    private static final String TEXT_SOURCE_ONLY = "t";

    /**
     * Lua's marker for a chunk name denoting a file. Without it LuaJ renders
     * the location as {@code [string "boot.lua"]:1:}.
     *
     * <p>The two error paths want opposite names and only one can be given:
     * {@code LexState} and the traceback strip the marker, {@code LuaClosure}'s
     * runtime prefix copies it verbatim, and {@code =} leaks exactly like
     * {@code @}. So the marker stays and {@link #withoutFileMarker} repairs the
     * one path that needs it.
     */
    private static final String NAMES_A_FILE = "@";

    /**
     * The grain at which the budget is spent and a stop request is noticed. A
     * Lua loop ignores an interrupt, so this interval is the machine's whole
     * response time to {@code stop()}.
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
     *                          before it is killed. Injected so a test can set
     *                          a ceiling of a thousand.
     * @param machine           what the script may do to its own computer
     * @param limits            how much structure one value may carry across
     */
    public LuaJVm(
            VmOutput output,
            int instructionBudget,
            MachineAccess machine,
            BoundaryLimits limits)
    {
        this.output = Objects.requireNonNull(output, "output");
        if (instructionBudget <= 0) {
            throw new IllegalArgumentException(
                    "instructionBudget must be > 0, got " + instructionBudget);
        }
        this.instructionBudget = instructionBudget;

        this.globals = new Globals();
        LuaC.install(this.globals);
        this.globals.load(new BaseLib());

        // BaseLib installs dofile and loadfile AND implements ResourceFinder,
        // so it appoints itself reader of the classpath - every resource of
        // every loaded jar. Removing the globals closes the door; nulling the
        // finder removes the room.
        this.globals.set("dofile", LuaValue.NIL);
        this.globals.set("loadfile", LuaValue.NIL);
        this.globals.finder = null;

        this.globals.set("print", new Print());

        // Every LuaJ library except BaseLib ends its installation with
        //     env.get("package").get("loaded").set(name, lib)
        // and PackageLib is absent, so that line indexes nil and the library
        // fails to load. The registry they write into is built here by hand and
        // dropped below, before any script runs. Installing PackageLib instead
        // would mean installing java_searcher, a Java class loader, in order to
        // delete it again.
        LuaTable registry = new LuaTable();
        registry.set("loaded", new LuaTable());
        this.globals.set("package", registry);

        this.globals.load(new DebugLib());

        // sethook is captured here because this is the only moment it is
        // reachable: the table goes immediately after.
        this.setHook = this.globals.get("debug").get("sethook");
        this.globals.set("debug", LuaValue.NIL);

        // The scaffold leaves with it. The "package" case of
        // aForbiddenGlobalIsAbsent is what guards this line.
        this.globals.set("package", LuaValue.NIL);

        // Last, after the scaffold has gone. What those tables contain is
        // MachineGlobals' business; this class decides only that they exist and
        // that nothing else does.
        new MachineGlobals(machine, new ValueConverter(limits)).install(this.globals);
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
            // stop() asked for this, so no VmException. The interrupt flag is
            // standing here: the hook never clears it, and the paths that catch
            // an InterruptedException put it back.
        } catch (HostFailure ours) {
            // Leaves as itself, with the stack trace of the code that is
            // actually broken. Machine's thread body catches VmException and
            // only that, so this stays loud.
            throw (RuntimeException) ours.getCause();
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
     * <p>Two audiences, two contents. The player reads one line on a screen
     * twenty-five rows tall; the exception keeps the traceback, which nothing
     * catches today and which wants a destination the day a shell calls
     * functions.
     *
     * <p>The only place this class encodes text on the way out, and the message
     * is ours rather than the script's bytes. A printed line still travels as
     * bytes.
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
     * Removes the marker LuaJ copies into a runtime error's location. See
     * {@link #NAMES_A_FILE} for why it exists on one path and not the other.
     */
    private String withoutFileMarker(String message) {
        return message.startsWith(NAMES_A_FILE + chunkName)
                ? message.substring(NAMES_A_FILE.length())
                : message;
    }

    /**
     * Replaces BaseLib's print, which renders through {@code tojstring()} - a
     * UTF-8 decode that mutilates the first non-text byte a script prints.
     * Rendering is delegated to the globals' own {@code tostring}, which buys
     * the {@code __tostring} metamethod for free.
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
     * The budget's trigger. An {@link Error} for the reason {@link Stopped}
     * gives: a hook raising anything else is swallowed by
     * {@code while true do pcall(function() while true do end end) end}, and
     * the budget is defeated while looking installed.
     */
    private static final class BudgetExhausted extends Error {
        BudgetExhausted() {
            // No stack trace: it would be the interpreter's frames.
            super("instruction budget exhausted", null, false, false);
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
