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
        // Boxed and unboxed on the same line: the queue's currency is Object[],
        // the shape a component method returns.
        return (Map<String, byte[]>) calls.submit(() -> new Object[] { components.list() })[0];
    }

    @Override
    public Object[] invoke(String address, String methodName, Object[] arguments)
            throws InterruptedException
    {
        return calls.submit(() -> components.invoke(address, methodName, arguments));
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
