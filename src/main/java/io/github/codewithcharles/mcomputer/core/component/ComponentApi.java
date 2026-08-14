package io.github.codewithcharles.mcomputer.core.component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Lua-facing surface of one component: a type name, and a fixed table of
 * named methods. No annotations, no reflection.
 *
 * <pre>{@code
 * ComponentApi.builder("gpu")
 *     .method("setForeground", args -> { ... })
 *     .method("get",           args -> { ... })
 *     .build();
 * }</pre>
 *
 * <p>Built per component instance, not per type: a gpu's methods close over
 * that gpu's state. It carries no address, so it can be built and tested with
 * no notion of where addresses come from.
 *
 * <p>Immutable once built.
 */
public final class ComponentApi {

    private final String type;
    private final Map<String, ComponentMethod> methods;

    private ComponentApi(String type, Map<String, ComponentMethod> methods) {
        this.type = type;
        this.methods = methods;
    }

    public static Builder builder(String type) {
        return new Builder(type);
    }

    /** The Lua-visible type name, as reported by {@code component.list()}. */
    public String type() {
        return type;
    }

    /** Backs {@code component.methods(address)}. */
    public Set<String> methodNames() {
        return methods.keySet();
    }

    /** Empty when no such method exists; the caller decides what that means. */
    public Optional<ComponentMethod> method(String name) {
        return Optional.ofNullable(methods.get(name));
    }

    public static final class Builder {

        private final String type;
        private final Map<String, ComponentMethod> methods = new LinkedHashMap<>();

        private Builder(String type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        /**
         * @throws IllegalArgumentException if {@code name} was already
         *         declared. A silently overwritten method surfaces as a missing
         *         feature hours later.
         */
        public Builder method(String name, ComponentMethod method) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(method, "method");
            if (methods.putIfAbsent(name, method) != null) {
                throw new IllegalArgumentException(
                        "duplicate method '" + name + "' on component type '" + type + "'");
            }
            return this;
        }

        /**
         * Callable more than once; each call yields an independent instance.
         * The copy immutability needs is the copy that makes reuse safe.
         */
        public ComponentApi build() {
            return new ComponentApi(type, Collections.unmodifiableMap(new LinkedHashMap<>(methods)));
        }
    }
}
