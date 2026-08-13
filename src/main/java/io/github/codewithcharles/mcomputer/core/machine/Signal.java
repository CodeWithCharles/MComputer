package io.github.codewithcharles.mcomputer.core.machine;

import java.util.Objects;

/**
 * One event a script can pull: a name plus its payload in boundary types.
 *
 * <p>By convention the first value is the address of the emitting component,
 * as in OpenComputers ({@code key_down} carries the keyboard's address first).
 * The convention lives at the emitting sites, not here.
 *
 * <p>The array is held by reference, not copied - same ownership rule as
 * {@code Arguments}. Record equality on {@code values} is therefore reference
 * equality; nothing compares two signals today.
 */
public record Signal(String name, Object[] values) {

    public Signal {
        Objects.requireNonNull(name, "name");
    }
}
