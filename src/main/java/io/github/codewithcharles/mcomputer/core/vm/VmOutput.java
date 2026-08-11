package io.github.codewithcharles.mcomputer.core.vm;

/**
 * Where a script's output goes.
 *
 * <p>Declared in {@code core} so that neither the VM nor the boundary knows
 * what a log is. The adapter layer hands over a logger; a test hands over a
 * list and asserts on it. This is the arbiter's second clause - a test seam
 * actually used - rather than an abstraction kept in reserve.
 *
 * <p>The parameter is {@code byte[]} and not {@code String} for the reason the
 * whole boundary exists: a Lua string is a byte array. Decoding here would
 * mutilate the first non-UTF-8 byte a script prints.
 */
@FunctionalInterface
public interface VmOutput {

    void write(byte[] line);
}
