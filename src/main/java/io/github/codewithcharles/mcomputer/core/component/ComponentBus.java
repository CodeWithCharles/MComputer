package io.github.codewithcharles.mcomputer.core.component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * What a computer's Lua side calls to reach its components: address to
 * {@link Component}, then name to method. It speaks addresses the way Lua
 * speaks them - as strings.
 *
 * <p><b>This is the layer that owns the {@link ComponentException} / real-bug
 * split</b>, and it owns it by not manufacturing the wrong type rather than by
 * catching one: an address a script mistyped must not reach the machine as an
 * {@code IllegalArgumentException} out of {@code UUID.fromString} and stop it as
 * if our own code were broken.
 *
 * <p>Conversely, exceptions thrown by a component method are deliberately
 * <b>not</b> caught here. A {@code ComponentException} is a script error and a
 * stray {@code NullPointerException} is ours; the layer that converts either
 * into a Lua error is the one that owns the LuaJ boundary, not this one.
 *
 * <p>Addresses are parsed and rendered in this single class, both directions.
 * Split across two, the two forms drift and {@code list()} starts handing out
 * addresses {@code invoke()} refuses.
 */
public class ComponentBus {

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
     * Backs {@code component.list()}: address to type, in the shape the value
     * boundary accepts. Keys are decoded text, values are raw bytes - the closed
     * list for keys is not the closed list for values.
     */
    public Map<String, byte[]> list() {
        Map<String, byte[]> listed = new LinkedHashMap<>();
        registry.list().forEach(
                (address, type) -> listed.put(address.toString(), type.getBytes(UTF_8)));

        return listed;
    }

    /**
     * Parses and looks up in one place, so a malformed address and an unknown
     * one produce the same message: from the script's side both mean "this
     * resolves to nothing here". The address is echoed <b>verbatim</b>, never
     * re-rendered from a parsed UUID - that would come back lowercased and read
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
