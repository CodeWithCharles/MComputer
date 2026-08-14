package io.github.codewithcharles.mcomputer.core.machine;

import io.github.codewithcharles.mcomputer.core.component.ComponentBus;

import java.util.Map;

/**
 * The machine as one run sees it.
 *
 * <p>Built per run, like the queues it holds, and it is where the rule
 * "component calls happen on the server thread" is written down. The
 * {@code ComponentRegistry} is not synchronised, so that rule is load-bearing,
 * and one class holding it beats every call site of the Lua face remembering
 * it.
 *
 * <p>A signal pull is the exception: a {@link SignalQueue} is safe to read from
 * any thread, and routing a wait through the {@link CallQueue} would hold the
 * tick's drain for as long as no key is pressed.
 *
 * <p>No {@code requireNonNull}: the only caller builds all three itself.
 */
final class RunAccess implements MachineAccess {

    private final CallQueue calls;
    private final SignalQueue signals;
    private final ComponentBus components;

    RunAccess(CallQueue calls, SignalQueue signals, ComponentBus components) {
        this.calls = calls;
        this.signals = signals;
        this.components = components;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, byte[]> listComponents() throws InterruptedException {
        try {
            // Boxed and unboxed on the same line: the queue's currency is
            // Object[], the shape a component method returns.
            return (Map<String, byte[]>) calls.submit(() -> new Object[] { components.list() })[0];
        } catch (CallQueue.ShutDown teardown) {
            throw runIsOver();
        }
    }

    @Override
    public Object[] invoke(String address, String methodName, Object[] arguments)
            throws InterruptedException
    {
        try {
            return calls.submit(() -> components.invoke(address, methodName, arguments));
        } catch (CallQueue.ShutDown teardown) {
            throw runIsOver();
        }
    }

    /**
     * A shut-down queue means this run is over, which is what an interruption
     * already means one layer up. The translation belongs here because luaj
     * cannot tell this apart from an IllegalStateException a component threw,
     * and this class can.
     *
     * <p>The cause is dropped rather than kept: it carries the stack of the
     * thread that called stop(), which describes a right-click and not the
     * script.
     */
    private static InterruptedException runIsOver() {
        return new InterruptedException("call queue shut down");
    }

    @Override
    public Signal pullSignal() throws InterruptedException {
        return signals.pull();
    }

    @Override
    public Signal pullSignal(long timeoutMillis) throws InterruptedException {
        return signals.pull(timeoutMillis);
    }
}
