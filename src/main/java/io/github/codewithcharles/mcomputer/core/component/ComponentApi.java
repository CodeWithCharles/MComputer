package io.github.codewithcharles.mcomputer.core.component;

import java.util.Optional;
import java.util.Set;

/**
 * The Lua-facing surface of one component: a type name, and a fixed table of
 * named methods.
 *
 * <p>Built explicitly, in ordinary Java, with no annotations and no reflection:
 *
 * <pre>{@code
 * ComponentApi.builder("gpu")
 *     .method("setForeground", args -> { ... })
 *     .method("get",           args -> { ... })
 *     .build();
 * }</pre>
 *
 * <p>An instance is built <b>per component instance</b>, not per type: a gpu's
 * methods close over that particular gpu's state. Carrying no address is what
 * lets it be built and tested with no notion of where addresses come from.
 *
 * <p>Immutable once built.
 */
public final class ComponentApi {

    public static Builder builder(String type) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** The Lua-visible type name, as reported by {@code component.list()}. */
    public String type() {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Backs {@code component.methods(address)}. */
    public Set<String> methodNames() {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Empty when no such method exists; the caller decides what that means. */
    public Optional<ComponentMethod> method(String name) {
        throw new UnsupportedOperationException("not implemented");
    }

    public static final class Builder {

        private Builder(String type) {
            throw new UnsupportedOperationException("not implemented");
        }

        /**
         * @throws IllegalArgumentException if {@code name} was already declared.
         *         A silently overwritten method is a bug that surfaces as a
         *         missing feature hours later.
         */
        public Builder method(String name, ComponentMethod method) {
            throw new UnsupportedOperationException("not implemented");
        }

        public ComponentApi build() {
            throw new UnsupportedOperationException("not implemented");
        }
    }
}