package io.github.codewithcharles.mcomputer.core.component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A read-only, checked view over the arguments of one component call.
 *
 * <p>The converter has already rejected anything outside the closed list. What
 * is checked here is the method's own contract: arity, and the type each
 * position is expected to hold.
 *
 * <p>Indexing is zero-based; messages report one-based positions, which is what
 * Lua shows the player. The shift lives here and nowhere else.
 *
 * <p>A missing argument and an explicit nil are not distinguished.
 *
 * <p>The array is taken over, not copied. A copy would leave the {@code byte[]}
 * elements mutable anyway, for an allocation on every component call.
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
     * Read, test, report. The Lua name is passed in rather than derived:
     * {@code byte[]} is called {@code string} on that side.
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
     * A Lua number that must be whole and within {@code int} range. Lua has no
     * integers, so this check cannot be pushed onto the caller.
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
     * A Lua string decoded as UTF-8, reporting malformed input instead of
     * replacing it: two byte sequences that both decoded to U+FFFD would open
     * the same file. For paths and names, never for file contents.
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
    // Fall back when the argument is null or absent; still fail on a present
    // value of the wrong type.

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
     * No check performed. For the rare method that inspects a value before
     * deciding what it is.
     */
    public Object raw(int index) {
        return at(index);
    }
}
