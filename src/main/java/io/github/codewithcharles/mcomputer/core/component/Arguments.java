package io.github.codewithcharles.mcomputer.core.component;

/**
 * A read-only, checked view over the arguments of one component call.
 *
 * <p>The values are already known to be boundary values - the converter
 * rejected anything outside the closed list before this object existed. What is
 * checked here is the <b>method's own contract</b>: arity, and the type each
 * position is expected to hold.
 *
 * <p><b>Indexing is zero-based</b>, as everywhere else in Java. Error messages
 * report one-based positions, because that is what the player sees in Lua. The
 * shift lives here and nowhere else.
 *
 * <p>A missing argument and an explicit {@code nil} are not distinguished. Lua
 * itself barely distinguishes them, and no component has a reason to.
 */
public final class Arguments {

    private final Object[] values;

    public Arguments(Object[] values) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** How many arguments the caller passed. */
    public int count() {
        throw new UnsupportedOperationException("not implemented");
    }

    /** True if the position is past the end, or holds {@code null}. */
    public boolean isNull(int index) {
        throw new UnsupportedOperationException("not implemented");
    }

    // --- required ---------------------------------------------------------
    // Each throws ComponentException.badArgument if absent or of the wrong type.

    public boolean checkBoolean(int index) {
        throw new UnsupportedOperationException("not implemented");
    }

    public double checkDouble(int index) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * A Lua number that must be a whole one. Fails if the double has a
     * fractional part or falls outside {@code int} range - Lua has no integers,
     * so this check cannot be pushed onto the caller.
     */
    public int checkInt(int index) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** The raw bytes of a Lua string. Use this unless you truly mean text. */
    public byte[] checkBytes(int index) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * A Lua string decoded as UTF-8. Only for genuinely textual arguments -
     * a file path, a colour name. Never for file contents.
     */
    public String checkText(int index) {
        throw new UnsupportedOperationException("not implemented");
    }

    // --- optional ---------------------------------------------------------
    // Return the fallback when the argument is null or absent; still fail on a
    // present value of the wrong type.

    public boolean optBoolean(int index, boolean fallback) {
        throw new UnsupportedOperationException("not implemented");
    }

    public double optDouble(int index, double fallback) {
        throw new UnsupportedOperationException("not implemented");
    }

    public int optInt(int index, int fallback) {
        throw new UnsupportedOperationException("not implemented");
    }

    public byte[] optBytes(int index, byte[] fallback) {
        throw new UnsupportedOperationException("not implemented");
    }

    public String optText(int index, String fallback) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Escape hatch, for the rare method that inspects a value before deciding
     * what it is. Prefer a typed accessor - this one performs no check and
     * every use is a small hole in the contract.
     */
    public Object raw(int index) {
        throw new UnsupportedOperationException("not implemented");
    }
}
