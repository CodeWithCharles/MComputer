package io.github.codewithcharles.mcomputer.core.component;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComponentTest {

    private static final UUID ADDRESS = UUID.fromString("00000000-0000-0000-0000-00000000c0de");

    private static ComponentApi gpu() {
        return ComponentApi.builder("gpu").build();
    }

    @Test
    void theTypeIsReadFromTheApi() {
        assertEquals("gpu", new Component(ADDRESS, gpu()).type());
    }

    @Test
    void theAddressSurvivesTheConstructor() {
        assertEquals(ADDRESS, new Component(ADDRESS, gpu()).address());
    }

    @Test
    void anAddressIsRequired() {
        assertThrows(NullPointerException.class, () -> new Component(null, gpu()));
    }

    @Test
    void anApiIsRequired() {
        assertThrows(NullPointerException.class, () -> new Component(ADDRESS, null));
    }
}