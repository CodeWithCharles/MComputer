package io.github.codewithcharles.mcomputer.luaj;

import io.github.codewithcharles.mcomputer.core.component.Arguments;
import io.github.codewithcharles.mcomputer.core.component.ComponentException;
import io.github.codewithcharles.mcomputer.core.machine.MachineAccess;

import io.github.codewithcharles.mcomputer.core.machine.Signal;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Objects;

/**
 * The face a script sees of its own computer: the {@code component} and
 * {@code computer} tables. {@link LuaJVm} says how a VM is composed and run;
 * this says what it exposes, and it is the half that grows.
 *
 * <p><b>It owns a boundary, and not the sandbox's.</b> A
 * {@code ComponentException} is the script's fault and becomes an ordinary Lua
 * error, which {@code pcall} can catch, so a shell survives a bad call.
 * Anything else is ours and leaves inside a {@link HostFailure}, which
 * {@code pcall} cannot touch.
 */
final class MachineGlobals {

    private final MachineAccess machine;
    private final ValueConverter converter;

    MachineGlobals(MachineAccess machine, ValueConverter converter) {
        this.machine = Objects.requireNonNull(machine, "machine");
        this.converter = Objects.requireNonNull(converter, "converter");
    }

    void install(Globals globals) {
        LuaTable component = new LuaTable();
        component.set("list", new ComponentList());
        component.set("invoke", new ComponentInvoke());
        globals.set("component", component);

        LuaTable computer = new LuaTable();
        computer.set("pullSignal", new PullSignal());
        globals.set("computer", computer);
    }

    /** Backs {@code component.list()}: address to type, as a Lua table. */
    private final class ComponentList extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            try {
                // Boxed into one value: toLua speaks in return lists.
                return converter.toLua(new Object[] { machine.listComponents() });
            } catch (RuntimeException ours) {
                // No argument crosses here, so no failure is the script's.
                throw new HostFailure(ours);
            } catch (InterruptedException interrupted) {
                throw Stopped.afterInterruption();
            }
        }
    }

    /** Backs {@code component.invoke(address, method, ...)}. */
    private final class ComponentInvoke extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            try {
                // Address and method name cross as boundary values like any
                // other, so Arguments brings the UTF-8 rule, the
                // bad argument #N idiom, and one entry budget for the call.
                Arguments arguments = new Arguments(converter.toJava(args), "invoke");
                String address = arguments.checkText(0);
                String methodName = arguments.checkText(1);

                // count() >= 2 here, or checkText(1) would have thrown.
                Object[] rest = new Object[arguments.count() - 2];
                for (int i = 0; i < rest.length; i++) {
                    rest[i] = arguments.raw(i + 2);
                }

                return converter.toLua(machine.invoke(address, methodName, rest));
            } catch (ComponentException expected) {
                throw new LuaError(expected.getMessage());
            } catch (RuntimeException ours) {
                throw new HostFailure(ours);
            } catch (InterruptedException interrupted) {
                throw Stopped.afterInterruption();
            }
        }
    }

    /** Backs {@code computer.pullSignal([seconds])}. */
    private final class PullSignal extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            try {
                Arguments arguments = new Arguments(converter.toJava(args), "pullSignal");
                double seconds = arguments.optDouble(0, Double.POSITIVE_INFINITY);

                // Only a positive infinity waits without a bound. A negative one
                // would otherwise block forever, where returning at once is what
                // any negative timeout means.
                Signal signal = seconds == Double.POSITIVE_INFINITY
                        ? machine.pullSignal()
                        : machine.pullSignal(millis(seconds));

                return signal == null ? NONE : unpack(signal);
            } catch (ComponentException expected) {
                throw new LuaError(expected.getMessage());
            } catch (RuntimeException ours) {
                throw new HostFailure(ours);
            } catch (InterruptedException interrupted) {
                throw Stopped.afterInterruption();
            }
        }
    }

    /**
     * The cast saturates rather than wrapping, so a huge finite timeout cannot
     * come out negative and return at once. NaN gives 0.
     */
    private static long millis(double seconds) {
        return (long) (seconds * 1000.0);
    }

    /**
     * Name first, then the values. The name is encoded here because it is
     * ours, the way a failure message is; a script's own bytes are never
     * decoded.
     */
    private Varargs unpack(Signal signal) {
        return LuaValue.varargsOf(
                LuaValue.valueOf(signal.name()), converter.toLua(signal.values()));
    }
}
