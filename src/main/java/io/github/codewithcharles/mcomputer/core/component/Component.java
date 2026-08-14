package io.github.codewithcharles.mcomputer.core.component;

import java.util.Objects;
import java.util.UUID;

/**
 * An addressed component: an identity, plus what Lua can call on it.
 *
 * <p>The address is given, never generated here. {@code core} does not know
 * where addresses come from, which is what lets a test hand over any UUID with
 * no game running.
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
