package io.github.codewithcharles.mcomputer.core.component;

import java.util.List;
import java.util.Map;

/**
 * An expected failure of a component call, converted into a Lua error at the
 * boundary: the script called wrong, or the component cannot comply right now.
 *
 * <p>Any other unchecked exception out of a {@link ComponentMethod} means our
 * Java code is broken. It stays loud instead of reaching the player as a
 * puzzling Lua error we never see.
 */
public final class ComponentException extends RuntimeException {

    public ComponentException(String message) {
        super(message);
    }

    /**
     * {@code bad argument #1 (string expected, got number)}.
     *
     * @param index zero-based, rendered one-based
     */
    public static ComponentException badArgument(int index, String expected, Object actual) {
        return badArgument(index, describe(expected, actual));
    }

    /**
     * The same envelope with the reason spelled out, for failures where the
     * type is correct and "number expected, got number" would be nonsense: an
     * integer accessor handed 1.5, a text accessor handed invalid UTF-8.
     *
     * @param index zero-based, rendered one-based
     */
    public static ComponentException badArgument(int index, String reason) {
        return new ComponentException(envelope(index, "", reason));
    }

    /**
     * Lua's own idiom, method name included:
     * {@code bad argument #1 to 'set' (string expected, got number)}.
     * {@link Arguments} holds the name and is what calls this.
     *
     * @param index zero-based, rendered one-based
     */
    public static ComponentException badArgument(
            String methodName,
            int index,
            String expected,
            Object actual)
    {
        return badArgument(methodName, index, describe(expected, actual));
    }

    /** @param index zero-based, rendered one-based */
    public static ComponentException badArgument(String methodName, int index, String reason) {
        return new ComponentException(envelope(index, " to '" + methodName + "'", reason));
    }

    private static String envelope(int index, String calledAs, String reason) {
        return "bad argument #" + (index + 1) + calledAs + " (" + reason + ")";
    }

    private static String describe(String expected, Object actual) {
        return expected + " expected, got " + typeName(actual);
    }

    /**
     * The boundary's Java types in Lua's vocabulary. {@code core} may not see
     * {@code org.luaj}, and what arrives here is a converted Java value anyway.
     *
     * <p>The default arm should be unreachable. It reports the Java name: if it
     * ever fires, the converter let something through and the log has to say
     * what, rather than hide it behind a plausible Lua name.
     */
    private static String typeName(Object value) {
        return switch (value) {
            case null -> "nil";
            case Boolean _ -> "boolean";
            case Double _ -> "number";
            case byte[] _ -> "string";
            case Map<?, ?> _ -> "table";
            case List<?> _ -> "table";
            default -> value.getClass().getSimpleName();
        };
    }
}
