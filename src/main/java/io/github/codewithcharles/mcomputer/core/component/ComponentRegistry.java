package io.github.codewithcharles.mcomputer.core.component;

import java.util.*;

/**
 * The components installed in one computer.
 *
 * <p><b>One registry per machine, never a global one.</b> A shared registry
 * would turn knowing an address into an authorisation, letting a script drive
 * the neighbour's hardware.
 *
 * <p>Not synchronised. The adapter adds and removes on the server thread when
 * the computer's inventory changes, and every Lua-side read arrives through the
 * call queue, which runs there too. The day either stops holding, this
 * paragraph is the first thing to change.
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

    /** Empty if nothing is installed at that address in this machine. */
    public Optional<Component> find(UUID address) {
        return Optional.ofNullable(components.get(address));
    }

    /**
     * Address to type, in Java terms. A fresh map each call: what reaches a
     * script is rebuilt by the converter anyway.
     */
    public Map<UUID, String> list() {
        Map<UUID, String> byAddress = new LinkedHashMap<>();
        components.forEach((address, component) -> byAddress.put(address, component.type()));
        return byAddress;
    }
}
