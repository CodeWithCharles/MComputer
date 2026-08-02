package io.github.codewithcharles.mcomputer.core.component;

/**
 * One Lua-callable method of a component.
 *
 * <p>The contract the rest of the architecture rests on:
 * <ul>
 *   <li>Runs on the <b>server thread</b>, never on the Lua thread. The Lua
 *       thread is blocked for the duration of the call.</li>
 *   <li>Must fit comfortably inside a 50 ms tick.</li>
 *   <li>Takes and returns <b>boundary values only</b>: {@code null},
 *       {@code Boolean}, {@code Double}, {@code byte[]}, or a flat
 *       {@code Map}/{@code List} of those. No Java object ever crosses to the
 *       Lua side; anything object-like is returned as a handle.</li>
 * </ul>
 *
 * <p>The array return type is not a convenience. Lua functions return several
 * values, and collapsing that to a single result would leak into every method
 * that needs more than one.
 *
 * <p>Failures are signalled with an unchecked exception, which the caller turns
 * into a Lua error at the boundary. A dedicated exception type will earn its
 * place when there is something to distinguish.
 */
@FunctionalInterface
public interface ComponentMethod {

    /**
     * @param arguments the call's arguments, already validated as boundary
     *                  values, never {@code null}
     * @return boundary values, possibly empty, never {@code null}
     * @throws ComponentException if the arguments do not satisfy this method's
     *                            contract, or the operation cannot be performed
     */
    Object[] invoke(Arguments arguments);
}
