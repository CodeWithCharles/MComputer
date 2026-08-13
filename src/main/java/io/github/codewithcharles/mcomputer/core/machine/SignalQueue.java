package io.github.codewithcharles.mcomputer.core.machine;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Signals waiting to be pulled by the machine's script.
 *
 * <p>Written by the server thread, read by the Lua thread blocked in
 * {@code pullSignal}. Bounded: a full queue refuses the INCOMING signal -
 * the opposite of {@code ScreenOutput}, because events are consumed in
 * order and losing one mid-sequence is worse than refusing the newest.
 */
public final class SignalQueue {

    private final BlockingQueue<Signal> pending;

    public SignalQueue(int capacity) {
        this.pending = new LinkedBlockingDeque<>(capacity);
    }

    public boolean push(Signal signal) {
        return pending.offer(signal);
    }

    public Signal pull(long timeoutMillis) throws InterruptedException {
        return pending.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public Signal pull() throws InterruptedException {
        return pending.take();
    }
}
