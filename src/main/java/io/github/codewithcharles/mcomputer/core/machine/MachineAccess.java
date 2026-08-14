package io.github.codewithcharles.mcomputer.core.machine;

import java.util.Map;

/**
 * What a running script may do to its own computer.
 *
 * <p>Handed to a VM at construction by the run it belongs to, so the VM never
 * learns where a call actually runs.
 *
 * <p><b>Every method may block the calling thread.</b> That is the execution
 * model: a script asking the game a question waits for the answer, and the
 * caller is the Lua thread, whose purpose is to be blockable.
 *
 * <p>{@link InterruptedException} propagates untouched. Turning it into a
 * stopped run belongs to the layer owning the Lua boundary, because the result
 * has to be something Lua's {@code pcall} cannot swallow.
 *
 * <p>Values crossing here are boundary values.
 */
public interface MachineAccess {

    /** Backs {@code component.list()}: address to type. */
    Map<String, byte[]> listComponents() throws InterruptedException;

    /**
     * Backs {@code component.invoke(address, method, ...)}. A
     * {@code ComponentException} passes through untouched.
     *
     * @return the method's return values, several by design
     */
    Object[] invoke(String address, String methodName, Object[] arguments)
            throws InterruptedException;

    /** Backs {@code computer.pullSignal()}: waits as long as it takes. */
    Signal pullSignal() throws InterruptedException;

    /**
     * Backs {@code computer.pullSignal(seconds)}, the conversion being the
     * caller's - this package speaks milliseconds. Two methods rather than a
     * sentinel: OpenComputers' default timeout is {@code math.huge}, and no
     * millisecond value honestly means forever.
     *
     * @return {@code null} on timeout
     */
    Signal pullSignal(long timeoutMillis) throws InterruptedException;
}
