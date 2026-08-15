package io.github.codewithcharles.mcomputer.luaj;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import io.github.codewithcharles.mcomputer.core.component.BoundaryLimits;
import io.github.codewithcharles.mcomputer.core.machine.InstructionBudget;
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
     * even if this string changed - which is also why {@link Load} has to force
     * this mode onto a script's own {@code load}.
     */
    private static final String TEXT_SOURCE_ONLY = "t";

    /**
     * The grain at which the budget is spent and a stop request is noticed. A
     * Lua loop ignores an interrupt, so this interval is the machine's whole
     * response time to {@code stop()}.
     */
    private static final int INSTRUCTIONS_PER_CHECK = 1_000;

    private final VmOutput output;
    private final Globals globals;
    private final LuaValue setHook;
    private final LuaValue baseLoad;
    private final InstructionBudget budget;
    private LuaValue loaded;
    private String chunkName;

    /**
     * @param output            where the script's output goes
     * @param budget            how many instructions this computer may run per
     *                          tick. Injected so a test can set a thousand.
     * @param machine           what the script may do to its own computer
     * @param limits            how much structure one value may carry across
     */
    public LuaJVm(
            VmOutput output,
            InstructionBudget budget,
            MachineAccess machine,
            BoundaryLimits limits)
    {
        this.output = Objects.requireNonNull(output, "output");
        this.budget = Objects.requireNonNull(budget, "budget");

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

        // Captured before being replaced, like sethook below.
        this.baseLoad = this.globals.get("load");
        this.globals.set("load", new Load());

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
                    chunkName,
                    TEXT_SOURCE_ONLY,
                    globals);
        } catch (LuaError e) {
            throw failure(withoutQuoting(e.getMessage(), chunkName), e);
        }
        this.chunkName = chunkName;
    }

    @Override
    public void run() {
        if (loaded == null) {
            throw new IllegalStateException("nothing loaded");
        }
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
        } catch (LuaError e) {
            throw failure(e.getMessage(), e);
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
     * Repairs the one location LuaJ renders as {@code [string "boot.lua"]:1:},
     * which is what it does with a chunk name it was not told is a file.
     *
     * <p>Lua's own marker, {@code @}, buys the clean form here and leaks into
     * every runtime error instead - including the string a {@code pcall} hands
     * back to a script, which never reaches our catch. So the name stays bare
     * and this message, which only ever reaches us, is the one repaired.
     */
    private static String withoutQuoting(String message, String chunkName) {
        String quoted = "[string \"" + chunkName + "\"]";
        return message.startsWith(quoted) ? chunkName + message.substring(quoted.length()) : message;
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
     * Replaces BaseLib's load, whose default mode is {@code "bt"}.
     * {@code Globals.loadPrototype} tests the binary mode first, so with no
     * undumper - and there is none, deliberately - it answers "No undumper."
     * for every input, valid source included.
     *
     * <p>The mode is forced, not defaulted: a caller asking for {@code "b"}
     * gets text compilation. Everything else is delegated, so both reader
     * forms, the chunk name and the environment keep BaseLib's behaviour.
     */
    private final class Load extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            LuaValue mode = LuaValue.valueOf(TEXT_SOURCE_ONLY);
            // The fourth argument is forwarded only when the caller gave one.
            // BaseLib tells an absent environment from a nil one, and a nil one
            // compiles a chunk that cannot see a single global.
            if (args.narg() >= 4) {
                return baseLoad.invoke(LuaValue.varargsOf(new LuaValue[] {
                        args.arg(1), args.arg(2), mode, args.arg(4) }));
            }
            return baseLoad.invoke(
                    LuaValue.varargsOf(new LuaValue[] { args.arg(1), args.arg(2), mode }));
        }
    }

    private final class Hook extends ZeroArgFunction {

        @Override
        public LuaValue call() {
            if (Thread.currentThread().isInterrupted()) {
                throw new Stopped();
            }
            try {
                budget.spend(INSTRUCTIONS_PER_CHECK);
            } catch (InterruptedException stopped) {
                throw Stopped.afterInterruption();
            }
            return NIL;
        }
    }
}
