package io.github.codewithcharles.mcomputer.core.component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * What a computer's Lua side calls to reach its components: address to
 * {@link Component}, then name to method. It speaks addresses the way Lua does,
 * as strings.
 *
 * <p><b>This layer owns the {@link ComponentException} / real-bug split</b>, and
 * it owns it by not manufacturing the wrong type: an address a script mistyped
 * must not arrive as an {@code IllegalArgumentException} out of
 * {@code UUID.fromString} and stop the machine as if our own code were broken.
 * Exceptions thrown by a component method are not caught here - the layer that
 * turns either kind into a Lua error is the one owning the LuaJ boundary.
 *
 * <p>Addresses are parsed and rendered in this single class. Split across two,
 * the forms drift and {@code list()} starts handing out addresses
 * {@code invoke()} refuses.
 */
public final class ComponentBus {

    private final ComponentRegistry registry;

    public ComponentBus(ComponentRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Backs {@code component.invoke(address, method, ...)}.
     *
     * @param address    as the script wrote it, unparsed
     * @param methodName goes into the {@link Arguments} this builds, which is
     *                   what puts it in {@code bad argument #1 to 'set' (...)}
     * @param arguments  boundary values, already validated by the converter
     * @return the method's return values, several by design
     */
    public Object[] invoke(String address, String methodName, Object[] arguments) {
        Component component = resolve(address);
        ComponentMethod method = component.api().method(methodName)
                .orElseThrow(() -> new ComponentException(
                        "unknown method '" + methodName + "' on component '" + component.type() + "'"));

        return method.invoke(new Arguments(arguments, methodName));
    }

    /**
     * Backs {@code component.list()}: address to type. Keys are decoded text,
     * values are raw bytes - the closed list for keys is not the one for
     * values.
     */
    public Map<String, byte[]> list() {
        Map<String, byte[]> listed = new LinkedHashMap<>();
        registry.list().forEach(
                (address, type) -> listed.put(address.toString(), type.getBytes(UTF_8)));

        return listed;
    }

    /**
     * Parses and looks up in one place, so a malformed address and an unknown
     * one give the same message: from the script's side both mean "this
     * resolves to nothing here". The address is echoed verbatim, never
     * re-rendered from a parsed UUID, which would come back lowercased and read
     * as a different component to whoever typed it in capitals.
     */
    private Component resolve(String address) {
        Objects.requireNonNull(address, "address");
        UUID parsed;
        try {
            parsed = UUID.fromString(address);
        } catch (IllegalArgumentException malformed) {
            throw noSuchComponent(address);
        }
        return registry.find(parsed).orElseThrow(() -> noSuchComponent(address));
    }

    private static ComponentException noSuchComponent(String address) {
        return new ComponentException("no such component '" + address + "'");
    }
}
