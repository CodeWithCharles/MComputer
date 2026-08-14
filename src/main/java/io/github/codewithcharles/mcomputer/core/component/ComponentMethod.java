package io.github.codewithcharles.mcomputer.core.component;

/**
 * One Lua-callable method of a component.
 *
 * <p>The contract the rest of the architecture rests on:
 * <ul>
 *   <li>runs on the server thread, never on the Lua thread, which stays
 *       blocked for the duration;</li>
 *   <li>must fit inside a 50 ms tick;</li>
 *   <li>takes and returns boundary values only: {@code null}, {@code Boolean},
 *       {@code Double}, {@code byte[]}, or a flat {@code Map}/{@code List} of
 *       those. Anything object-like is returned as a handle.</li>
 * </ul>
 *
 * <p>The array return is not a convenience: Lua functions return several
 * values, and one result would leak into every method needing more.
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
