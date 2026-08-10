package io.github.codewithcharles.mcomputer.core.component;

import java.util.*;

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

    private final Map<UUID, Component> components = new LinkedHashMap<>();

    public void add(Component component) {
        Objects.requireNonNull(component, "component");
        Component existing = components.putIfAbsent(component.address(), component);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "address " + component.address() + " already holds a " + existing.type());
        }
    }

    /** Does nothing if no component is installed at that address. */
    public void remove(UUID address) {
        components.remove(address);
    }

    /** Empty if nothing is installed at that address in <b>this</b> machine. */
    public Optional<Component> find(UUID address) {
        return Optional.ofNullable(components.get(address));
    }

    /**
     * Address to type, in Java terms. A fresh map on each call: what reaches a
     * script is built by the converter anyway, so there is no live view to be
     * had and nothing to gain by returning one.
     */
    public Map<UUID, String> list() {
        Map<UUID, String> byAddress = new LinkedHashMap<>();
        components.forEach((address, component) -> byAddress.put(address, component.type()));
        return byAddress;
    }
}
