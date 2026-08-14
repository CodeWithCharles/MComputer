package io.github.codewithcharles.mcomputer.core.vm;

/**
 * Where a script's output goes. The adapter hands over a screen; a test hands
 * over a list.
 *
 * <p>{@code byte[]} and not {@code String}, for the reason the whole boundary
 * exists: a Lua string is a byte array, and decoding here would mutilate the
 * first non-UTF-8 byte a script prints.
 */
@FunctionalInterface
public interface VmOutput {

    void write(byte[] line);
}
