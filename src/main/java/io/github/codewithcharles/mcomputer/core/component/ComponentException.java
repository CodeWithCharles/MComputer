package io.github.codewithcharles.mcomputer.core.component;

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
     * Builds the message in Lua's own idiom, minus the method name, which the
     * dispatcher prefixes because it is the layer that knows it:
     * {@code "bad argument #1 (string expected, got number)"}.
     *
     * @param index zero-based, as used by {@link Arguments}; rendered one-based
     */
    public static ComponentException badArgument(int index, String expected, Object actual) {
        throw new UnsupportedOperationException("not implemented");
    }
}
