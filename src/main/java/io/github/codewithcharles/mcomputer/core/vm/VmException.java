package io.github.codewithcharles.mcomputer.core.vm;

/**
 * The script is at fault: it does not compile, it raised an error, or it ran
 * past its instruction budget.
 *
 * <p>Distinct from {@code ComponentException}, and deliberately not related to
 * it by inheritance. The two have different lifetimes: a component error is
 * turned into a Lua error <b>inside</b> the running script, which carries on;
 * a {@code VmException} ends the run. Sharing a supertype would let a
 * {@code catch} confuse the two, and the 2026-08-02 entry on asserting
 * supertypes says what that costs.
 *
 * <p>Wrapping is expected here, unlike at the {@code CallQueue} hop: a LuaJ
 * error must <b>not</b> escape {@code luaj} as a LuaJ type, or the dependency
 * rule is violated by the exception rather than by an import. The cause is
 * kept so the Lua stack survives.
 */
public class VmException extends RuntimeException {

    public VmException(String message) {
        super(message);
    }

    public VmException(String message, Throwable cause) {
        super(message, cause);
    }
}
