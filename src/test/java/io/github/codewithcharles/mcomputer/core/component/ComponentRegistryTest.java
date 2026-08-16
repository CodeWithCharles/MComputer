package io.github.codewithcharles.mcomputer.core.component;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ComponentRegistryTest {

    private static Component component(UUID address, String type) {
        return new Component(address, ComponentApi.builder(type).build());
    }

    @Test
    void anUnknownAddressFindsNothing() {
        ComponentRegistry registry = new ComponentRegistry();

        assertTrue(registry.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void anAddedComponentIsFoundAtItsAddress() {
        UUID address = UUID.randomUUID();
        Component gpu = component(address, "gpu");
        ComponentRegistry registry = new ComponentRegistry();

        registry.add(gpu);

        assertSame(gpu, registry.find(address).orElseThrow());
    }

    @Test
    void aRemovedComponentIsNoLongerFound() {
        UUID address = UUID.randomUUID();
        ComponentRegistry registry = new ComponentRegistry();
        registry.add(component(address, "gpu"));

        registry.remove(address);

        assertTrue(registry.find(address).isEmpty());
    }

    @Test
    void removingSomethingAbsentIsSilent() {
        ComponentRegistry registry = new ComponentRegistry();

        assertDoesNotThrow(() -> registry.remove(UUID.randomUUID()));
    }

    /**
     * Two different types at one address, deliberately: the day this is
     * optimized by comparing the components before refusing, this test
     * says it is the <b>address</b> that is unique, not the content.
     */
    @Test
    void addingTwiceAtTheSameAddressIsRejected() {
        UUID address = UUID.randomUUID();
        ComponentRegistry registry = new ComponentRegistry();
        registry.add(component(address, "gpu"));

        assertThrows(IllegalArgumentException.class,
                () -> registry.add(component(address, "screen")));
    }

    @Test
    void anEmptyRegistryListsNothing() {
        assertTrue(new ComponentRegistry().list().isEmpty());
    }

    @Test
    void everyInstalledComponentIsListedWithItsType() {
        UUID gpu = UUID.randomUUID();
        UUID screen = UUID.randomUUID();
        ComponentRegistry registry = new ComponentRegistry();
        registry.add(component(gpu, "gpu"));
        registry.add(component(screen, "screen"));

        Map<UUID, String> listed = registry.list();

        assertEquals(Map.of(gpu, "gpu", screen, "screen"), listed);
    }

    @Test
    void aListedMapIsASnapshotNotAView() {
        ComponentRegistry registry = new ComponentRegistry();
        registry.add(component(UUID.randomUUID(), "gpu"));

        Map<UUID, String> listed = registry.list();
        registry.add(component(UUID.randomUUID(), "screen"));

        assertEquals(1, listed.size());
    }
}
