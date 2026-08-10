package io.github.codewithcharles.mcomputer.core.component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The components installed in <b>one</b> computer.
 *
 * <p><b>One registry per machine, never a global one.</b> A shared registry
 * would turn the knowledge of an address into an authorisation: any script that
 * guessed or obtained a UUID could drive the neighbour's hardware. Scoping the
 * lookup to the machine is the same invariant as "the Lua thread cannot touch
 * the world", seen from addressing.
 *
 * <p><b>Not synchronised, and it does not need to be.</b> The adapter layer adds
 * and removes on the server thread when the computer's inventory changes, and
 * every Lua-side read arrives through the call queue, which runs on that same
 * thread. The day either stops being true, this paragraph is the first thing
 * that has to change.
 */
public final class ComponentRegistry {

    public void add(Component component) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Does nothing if no component is installed at that address. */
    public void remove(UUID address) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Empty if nothing is installed at that address in <b>this</b> machine. */
    public Optional<Component> find(UUID address) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Address to type, in Java terms. A fresh map on each call: what reaches a
     * script is built by the converter anyway, so there is no live view to be
     * had and nothing to gain by returning one.
     */
    public Map<UUID, String> list() {
        throw new UnsupportedOperationException("not implemented");
    }
}
