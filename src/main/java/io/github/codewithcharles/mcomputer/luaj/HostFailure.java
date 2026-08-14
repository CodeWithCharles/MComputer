package io.github.codewithcharles.mcomputer.luaj;

/**
 * A fault of ours, on its way out through an interpreter that would otherwise
 * disguise it.
 *
 * <p>LuaJ wraps any {@code Exception} a Java function throws into a
 * {@code LuaError} reading {@code vm error: <class>: <message>}, which
 * {@code pcall} then catches. Our own faults would reach the player as his
 * script's, and a shell would swallow them forever. An {@link Error} is the
 * only thing that crosses both, for the reason {@link Stopped} gives.
 *
 * <p>Keeps the cause's stack trace, which is the trace of the broken code.
 */
final class HostFailure extends Error {

    HostFailure(RuntimeException cause) {
        super(cause);
    }
}
