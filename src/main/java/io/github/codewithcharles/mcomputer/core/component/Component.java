package io.github.codewithcharles.mcomputer.core.component;

import java.util.Objects;
import java.util.UUID;

/**
 * An addressed component: an identity, plus what Lua can call on it.
 *
 * <p>The address is <b>given</b>, never generated here. Nothing in {@code core}
 * knows where addresses come from - the adapter layer reads it from the item
 * that backs this component, or assigns one the first time. That ignorance is
 * what lets a test hand over any UUID it likes with no game running.
 */
public record Component(UUID address, ComponentApi api) {

    public Component {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(api, "api");
    }

    public String type() {
        return api.type();
    }
}
