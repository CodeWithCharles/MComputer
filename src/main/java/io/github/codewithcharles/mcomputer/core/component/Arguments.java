package io.github.codewithcharles.mcomputer.core.component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

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
 *
 * <p>The array is <b>taken over, not copied</b>. The converter builds it fresh
 * for one call and does not retain it. A defensive copy would be a half
 * guarantee anyway - the {@code byte[]} elements would stay mutable - paid for
 * with an allocation on every component call.
 */
public final class Arguments {

    private final Object[] values;
    private final String methodName;

    public Arguments(Object[] values, String methodName) {
        this.values = Objects.requireNonNull(values, "values");
        this.methodName = Objects.requireNonNull(methodName, "methodName");
    }

    private Object at(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("argument index " + index + " is negative");
        }
        return index < values.length ? values[index] : null;
    }

    /**
     * The shape shared by every typed accessor: read, test, or report. The Lua
     * name is passed in rather than derived, because it is what the player must
     * read - {@code byte[]} is called {@code string} on that side.
     */
    private <T> T check(int index, Class<T> type, String luaName) {
        Object value = at(index);
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        throw ComponentException.badArgument(methodName, index, luaName, value);
    }

    /** How many arguments the caller passed. */
    public int count() {
        return values.length;
    }

    /** True if the position is past the end, or holds {@code null}. */
    public boolean isNull(int index) {
        return at(index) == null;
    }

    // --- required ---------------------------------------------------------
    // Each throws ComponentException.badArgument if absent or of the wrong type.

    public boolean checkBoolean(int index) {
        return check(index, Boolean.class, "boolean");
    }

    public double checkDouble(int index) {
        return check(index, Double.class, "number");
    }

    /**
     * A Lua number that must be a whole one. Fails if the double has a
     * fractional part or falls outside {@code int} range - Lua has no integers,
     * so this check cannot be pushed onto the caller.
     */
    public int checkInt(int index) {
        double value = checkDouble(index);
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw ComponentException.badArgument(methodName, index,
                    "number has no integer representation");
        }
        return (int) value;
    }

    /** The raw bytes of a Lua string. Use this unless you truly mean text. */
    public byte[] checkBytes(int index) {
        return check(index, byte[].class, "string");
    }

    /**
     * A Lua string decoded as UTF-8. Only for genuinely textual arguments -
     * a file path, a colour name. Never for file contents.
     */
    public String checkText(int index) {
        byte[] bytes = checkBytes(index);
        try {
            return UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw ComponentException.badArgument(methodName, index, "invalid UTF-8 string");
        }
    }

    // --- optional ---------------------------------------------------------
    // Return the fallback when the argument is null or absent; still fail on a
    // present value of the wrong type.

    public boolean optBoolean(int index, boolean fallback) {
        return isNull(index) ? fallback : checkBoolean(index);
    }

    public double optDouble(int index, double fallback) {
        return isNull(index) ? fallback : checkDouble(index);
    }

    public int optInt(int index, int fallback) {
        return isNull(index) ? fallback : checkInt(index);
    }

    public byte[] optBytes(int index, byte[] fallback) {
        return isNull(index) ? fallback : checkBytes(index);
    }

    public String optText(int index, String fallback) {
        return isNull(index) ? fallback : checkText(index);
    }

    /**
     * Escape hatch, for the rare method that inspects a value before deciding
     * what it is. Prefer a typed accessor - this one performs no check and
     * every use is a small hole in the contract.
     */
    public Object raw(int index) {
        return at(index);
    }
}
