package io.github.codewithcharles.mcomputer.luaj;

/**
 * The run was stopped from outside. Not a failure.
 *
 * <p>An {@link Error}, and this is the one place the reason is written out:
 * {@code pcall} and {@code xpcall} catch {@code LuaError} <b>and</b>
 * {@code java.lang.Exception}, measured on the embedded jar. Anything else a
 * script could swallow, keeping a thread alive after the block was broken.
 */
final class Stopped extends Error {

    Stopped() {
        // No stack trace: it would be the interpreter's frames.
        super("stopped", null, false, false);
    }

    /**
     * For the paths that catch an {@code InterruptedException}, which clears
     * the flag. The instruction hook reads it every thousandth instruction, so
     * putting it back is what keeps the stop request true after this frame.
     */
    static Stopped afterInterruption() {
        Thread.currentThread().interrupt();
        return new Stopped();
    }
}
