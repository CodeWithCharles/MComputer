package io.github.codewithcharles.mcomputer.core.vm;

/**
 * The script is at fault: it does not compile, it raised an error, or it ran
 * past its instruction budget.
 *
 * <p>Unrelated to {@code ComponentException} by inheritance. A component error
 * becomes a Lua error inside a script that carries on; this one ends the run. A
 * shared supertype would let one {@code catch} confuse them.
 *
 * <p>A LuaJ error is wrapped here rather than escaping the {@code luaj} package
 * as a LuaJ type, which would break the dependency rule through an exception
 * instead of through an import. The cause is kept, so the Lua stack survives.
 */
public class VmException extends RuntimeException {

    public VmException(String message) {
        super(message);
    }

    public VmException(String message, Throwable cause) {
        super(message, cause);
    }
}
