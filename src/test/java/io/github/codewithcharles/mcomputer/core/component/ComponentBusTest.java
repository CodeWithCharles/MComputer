package io.github.codewithcharles.mcomputer.core.component;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

final class ComponentBusTest {

    private static final UUID GPU = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String ABSENT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String ABSENT_SHOUTING = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE";
    private static final UUID NEIGHBOUR = UUID.fromString("99999999-8888-7777-6666-555555555555");

    private static ComponentBus busWithGpu() {
        ComponentRegistry registry = new ComponentRegistry();
        registry.add(new Component(GPU, ComponentApi.builder("gpu")
                .method("set", args -> new Object[] { args.checkText(0), args.checkDouble(1) })
                .build()));
        return new ComponentBus(registry);
    }

    @Test
    void anUnknownAddressIsRejected() {
        ComponentBus bus = busWithGpu();

        ComponentException thrown = assertThrows(ComponentException.class,
                () -> bus.invoke(ABSENT, "set", new Object[0]));

        assertEquals("no such component '" + ABSENT + "'", thrown.getMessage());
    }

    /**
     * The one that pins the split: without a parse of its own, this would come
     * out of UUID.fromString as an IllegalArgumentException and stop the
     * machine for a typo.
     */
    @Test
    void aMalformedAddressGivesTheSameMessageAsAnUnknownOne() {
        ComponentBus bus = busWithGpu();

        ComponentException thrown = assertThrows(ComponentException.class,
                () -> bus.invoke("bonjour", "set", new Object[0]));

        assertEquals("no such component 'bonjour'", thrown.getMessage());
    }

    /**
     * Echoed as written, never re-rendered from a parsed UUID - that would come
     * back lowercased and read as a different component to whoever typed it in
     * capitals.
     */
    @Test
    void theAddressIsEchoedVerbatim() {
        ComponentBus bus = busWithGpu();

        ComponentException thrown = assertThrows(ComponentException.class,
                () -> bus.invoke(ABSENT_SHOUTING, "set", new Object[0]));

        assertEquals("no such component '" + ABSENT_SHOUTING + "'", thrown.getMessage());
    }

    @Test
    void anUnknownMethodIsRejected() {
        ComponentBus bus = busWithGpu();

        ComponentException thrown = assertThrows(ComponentException.class,
                () -> bus.invoke(GPU.toString(), "sett", new Object[0]));

        assertEquals("unknown method 'sett' on component 'gpu'", thrown.getMessage());
    }

    @Test
    void aMethodIsInvokedAndItsValuesReturned() {
        ComponentBus bus = busWithGpu();

        Object[] returned = bus.invoke(GPU.toString(), "set",
                new Object[] { "red".getBytes(UTF_8), 2.0 });

        assertArrayEquals(new Object[] { "red", 2.0 }, returned);
    }

    /**
     * The payoff of Arguments carrying the name: the bus builds the Arguments,
     * so the name is in the message without anything being prefixed afterwards.
     */
    @Test
    void aTypeErrorCarriesTheMethodNameTheBusWasGiven() {
        ComponentBus bus = busWithGpu();

        ComponentException thrown = assertThrows(ComponentException.class,
                () -> bus.invoke(GPU.toString(), "set", new Object[] { 2.0 }));

        assertEquals("bad argument #1 to 'set' (string expected, got number)", thrown.getMessage());
    }

    /**
     * One registry per machine is a security boundary, not a scoping detail:
     * both halves are asserted in one test so that "simplifying" the registry
     * into a shared one cannot go green.
     */
    @Test
    void aComponentIsReachableOnlyFromItsOwnMachine() {
        ComponentBus mine = busWithGpu();
        ComponentRegistry neighbourRegistry = new ComponentRegistry();
        neighbourRegistry.add(new Component(NEIGHBOUR, ComponentApi.builder("gpu")
                .method("set", args -> new Object[] { "ok".getBytes(UTF_8) })
                .build()));
        ComponentBus neighbour = new ComponentBus(neighbourRegistry);

        assertEquals(1, neighbour.invoke(NEIGHBOUR.toString(), "set", new Object[0]).length);
        assertThrows(ComponentException.class,
                () -> mine.invoke(NEIGHBOUR.toString(), "set", new Object[0]));
    }

    @Test
    void anEmptyMachineListsNothing() {
        assertTrue(new ComponentBus(new ComponentRegistry()).list().isEmpty());
    }

    @Test
    void everyComponentIsListedAsAddressToType() {
        ComponentBus bus = busWithGpu();

        Map<String, byte[]> listed = bus.list();

        assertEquals(1, listed.size());
        assertArrayEquals("gpu".getBytes(UTF_8), listed.get(GPU.toString()));
    }

    /**
     * The round trip. If list() ever rendered addresses in a form invoke()
     * refused, no script could use the result of the one to drive the other -
     * and every other test in this suite would still pass.
     */
    @Test
    void anAddressTakenFromListIsAcceptedByInvoke() {
        ComponentBus bus = busWithGpu();

        String address = bus.list().keySet().iterator().next();
        Object[] returned = bus.invoke(address, "set", new Object[] { "red".getBytes(UTF_8), 2.0 });

        assertArrayEquals(new Object[] { "red", 2.0 }, returned);
    }

}
