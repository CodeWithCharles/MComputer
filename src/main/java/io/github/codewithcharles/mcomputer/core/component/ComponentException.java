package io.github.codewithcharles.mcomputer.core.component;

import java.util.List;
import java.util.Map;

/**
 * An <b>expected</b> failure of a component call, to be converted into a Lua
 * error at the boundary.
 *
 * <p>The distinction this type carries is the point of it: a
 * {@code ComponentException} means the Lua script did something wrong, or asked
 * for something the component cannot do right now (disk full, screen not bound).
 * Any <i>other</i> unchecked exception escaping a {@link ComponentMethod} means
 * <b>our Java code is broken</b> and must not be quietly turned into a Lua
 * error - it gets logged and stops the machine loudly.
 *
 * <p>Without this split, a stray {@code NullPointerException} in a component
 * would surface to the player as a puzzling Lua error and never be seen by us.
 */
public class ComponentException extends RuntimeException {

    public ComponentException(String message) {
        super(message);
    }

    /**
     * The base envelope, without a method name:
     * {@code "bad argument #1 (string expected, got number)"}.
     *
     * @param index zero-based, as used by {@link Arguments}; rendered one-based
     */
    public static ComponentException badArgument(int index, String expected, Object actual) {
        return badArgument(index, describe(expected, actual));
    }

    /**
     * The same envelope, with the reason written out in full. For the failures
     * where the type is <b>correct</b> and "number expected, got number" would
     * be nonsense: an integer accessor handed 1.5, a text accessor handed bytes
     * that are not valid UTF-8.
     *
     * @param index zero-based, as used by {@link Arguments}; rendered one-based
     */
    public static ComponentException badArgument(int index, String reason) {
        return new ComponentException(envelope(index, "", reason));
    }

    /**
     * Lua's own idiom, method name included:
     * {@code "bad argument #1 to 'set' (string expected, got number)"}.
     *
     * <p>{@link Arguments} holds the name and is what calls this. The name is
     * not information the player lacks - the traceback already gives the call
     * site - it is conformance to a message shape every Lua programmer knows.
     *
     * @param index zero-based, as used by {@link Arguments}; rendered one-based
     */
    public static ComponentException badArgument(
            String methodName,
            int index,
            String expected,
            Object actual)
    {
        return badArgument(methodName, index, describe(expected, actual));
    }

    /** @param index zero-based, as used by {@link Arguments}; rendered one-based */
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
     * The boundary's Java types, rendered in Lua's own vocabulary.
     *
     * <p>{@code core} may not see {@code org.luaj}, so {@code LuaValue.typename()}
     * is out of reach here - and it would be the wrong tool anyway, since what
     * arrives at this point is a converted Java value, not a {@code LuaValue}.
     *
     * <p>The default arm should be unreachable: the converter rejected anything
     * off the closed list before a component method ever saw it. It reports the
     * Java name deliberately - if it ever fires, the log must say which type got
     * through rather than hide it behind a plausible-looking Lua name.
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
