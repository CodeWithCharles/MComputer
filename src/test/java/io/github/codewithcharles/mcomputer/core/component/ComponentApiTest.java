package io.github.codewithcharles.mcomputer.core.component;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

public class ComponentApiTest {

    private static final ComponentMethod NOTHING = args -> new Object[0];

    @Test
    void theTypeNameSurvivesTheBuilder() {
        ComponentApi api = ComponentApi.builder("gpu").build();

        assertEquals("gpu", api.type());
    }

    @Test
    void methodNamesListsEveryDeclaredMethod() {
        ComponentApi api = ComponentApi.builder("gpu")
                .method("setForeground", NOTHING)
                .method("get", NOTHING)
                .build();

        assertEquals(Set.of("setForeground", "get"), api.methodNames());
    }

    @Test
    void aComponentWithNoMethodIsLegal() {
        ComponentApi api = ComponentApi.builder("stub").build();

        assertTrue(api.methodNames().isEmpty());
    }

    @Test
    void anUnknownMethodYieldsAnEmptyOptional() {
        ComponentApi api = ComponentApi.builder("gpu").method("get", NOTHING).build();

        assertTrue(api.method("set").isEmpty());
    }

    @Test
    void aMethodIsCalledThroughArgumentsAndReturnsSeveralValues() {
        ComponentApi api = ComponentApi.builder("gpu")
                .method("set", args -> new Object[] { args.checkText(0), args.checkDouble(1) })
                .build();

        Object[] returned = api.method("set")
                .orElseThrow()
                .invoke(new Arguments(new Object[] { "red".getBytes(UTF_8), 2.0 }, "set"));

        assertArrayEquals(new Object[] { "red", 2.0 }, returned);
    }

    @Test
    void aTypeErrorCarriesTheMethodName() {
        ComponentApi api = ComponentApi.builder("gpu")
                .method("set", args -> new Object[] { args.checkText(0) })
                .build();
        ComponentMethod set = api.method("set").orElseThrow();
        Arguments wrong = new Arguments(new Object[] { 2.0 }, "set");

        ComponentException thrown = assertThrows(ComponentException.class, () -> set.invoke(wrong));

        assertEquals("bad argument #1 to 'set' (string expected, got number)", thrown.getMessage());
    }

    @Test
    void aDuplicateMethodNameIsRejected() {
        ComponentApi.Builder builder = ComponentApi.builder("gpu").method("set", NOTHING);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> builder.method("set", NOTHING));

        assertEquals("duplicate method 'set' on component type 'gpu'", thrown.getMessage());
    }

    @Test
    void buildingTwiceYieldsIndependentInstances() {
        ComponentApi.Builder builder = ComponentApi.builder("gpu").method("get", NOTHING);

        ComponentApi first = builder.build();
        ComponentApi second = builder.method("set", NOTHING).build();

        assertEquals(Set.of("get"), first.methodNames());
        assertEquals(Set.of("get", "set"), second.methodNames());
    }

}
