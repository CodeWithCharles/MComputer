package io.github.codewithcharles.mcomputer.luaj;

import io.github.codewithcharles.mcomputer.core.component.BoundaryLimits;
import io.github.codewithcharles.mcomputer.core.component.ComponentException;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static java.nio.charset.StandardCharsets.UTF_8;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class ValueConverterTest {

    private final ValueConverter _converter = new ValueConverter(
            BoundaryLimits.defaults());

    /** LuaValue extends Varargs, so a single value is a one-element call. */
    private Object inbound(LuaValue value) {
        return _converter.toJava(value)[0];
    }

    private LuaValue outbound(Object value) {
        return _converter.toLua(new Object[] { value }).arg1();
    }

    private LuaTable outboundTable(Object value) {
        LuaValue back = outbound(value);
        assertEquals(LuaValue.TTABLE, back.type());
        return back.checktable();
    }

    private static byte[] bytesOf(LuaString string) {
        byte[] out = new byte[string.length()];
        string.copyInto(0, out, 0, out.length);
        return out;
    }

    private static ValueConverter tightly(int depth, int entries) {
        return new ValueConverter(new BoundaryLimits(depth, entries));
    }

    private static LuaTable tableOf(String key, LuaValue value) {
        LuaTable table = new LuaTable();
        table.set(LuaValue.valueOf(key), value);
        return table;
    }

    private static LuaTable tableWithEntries(int count) {
        LuaTable table = new LuaTable();
        for (int i = 1; i <= count; i++) {
            table.set(i, LuaValue.TRUE);
        }
        return table;
    }

    static Stream<Named<Object>> outsideTheBoundary() {
        return Stream.of(
                Named.of("Long - the silent precision bug past 2^53", 42L),
                Named.of("Integer", 42),
                Named.of("String - text is not the boundary type", "text"),
                Named.of("Float", 4.2f),
                Named.of("a plain Object", new Object()));
    }

    private static LuaValue aFunction() {
        return new ZeroArgFunction() {
            @Override public LuaValue call() { return NIL; }
        };
    }

    @Test
    void nilRoundTrips() {
        assertNull(inbound(LuaValue.NIL));
        assertTrue(outbound(null).isnil());
    }

    @Test
    void booleanRoundTrips() {
        assertEquals(Boolean.TRUE, inbound(LuaValue.TRUE));
        LuaValue back = outbound(true);
        assertEquals(LuaValue.TBOOLEAN, back.type());
        assertTrue(back.toboolean());
    }

    @Test
    void numberRoundTrips() {
        assertEquals(3.5, (Double) inbound(LuaValue.valueOf(3.5)));
        LuaValue back = outbound(3.0);
        // NOT instanceof LuaDouble: LuaJ hands back a LuaInteger for a whole double.
        assertEquals(LuaValue.TNUMBER, back.type());
        assertEquals(3.0, back.checkdouble());
    }

    @Test
    void aStringKeepsItsExactBytes() {
        byte[] raw = { 0x00, (byte) 0xFF, 0x41 };

        assertArrayEquals(raw, (byte[]) inbound(LuaValue.valueOf(raw)));

        LuaString back = (LuaString) outbound(raw);
        byte[] out = new byte[back.length()];
        back.copyInto(0, out, 0, out.length);
        assertArrayEquals(raw, out);
    }

    @Test
    void aNumberIsNotAString() {
        assertInstanceOf(Double.class, inbound(LuaValue.valueOf(42)));
    }

    @Test
    void aNumericStringStaysAString() {
        assertInstanceOf(byte[].class, inbound(LuaValue.valueOf("42")));
    }

    @ParameterizedTest
    @MethodSource("outsideTheBoundary")
    void outboundRejects(Object value) {
        assertThrows(IllegalStateException.class, () -> outbound(value));
    }

    @Test
    void inboundRejectsAFunction()  {
        assertThrows(ComponentException.class,
                () -> _converter.toJava(aFunction()));
    }

    @Test
    void inboundRejectsUserdata() {
        assertThrows(ComponentException.class,
                () -> _converter.toJava(
                        LuaValue.userdataOf(new Object())));
    }

    @Test
    void inboundRejectsACoroutine() {
        assertThrows(ComponentException.class,
                () -> _converter.toJava(
                        new LuaThread(new Globals(), aFunction())));
    }

    @Test
    void varargsAreOneBasedAndTheArrayIsZeroBased() {
        Varargs in = LuaValue.varargsOf(new LuaValue[] {
                LuaValue.valueOf("a"),
                LuaValue.valueOf(2),
                LuaValue.NIL });

        Object[] out = _converter.toJava(in);

        assertEquals(3, out.length);
        assertInstanceOf(byte[].class, out[0]); // Lua arg 1 -> index 0
        assertEquals(2.0, (Double) out[1]);
        assertNull(out[2]);
    }

    @Test
    void noArgumentsGivesAnEmptyArray() {
        assertEquals(0, _converter.toJava(LuaValue.NONE).length);
    }

    @Test
    void noReturnValuesGivesNone() {
        assertEquals(0, _converter.toLua(new Object[0]).narg());
    }

    @Test
    void anEmptyTableBecomesAnEmptyMap() {
        assertEquals(Map.of(), inbound(new LuaTable()));
    }

    @Test
    void aFlatTableRoundTrips() {
        LuaTable table = new LuaTable();
        table.set(LuaValue.valueOf("name"), LuaValue.valueOf("abc"));
        table.set(LuaValue.valueOf("size"), LuaValue.valueOf(3));

        Map<?, ?> map = (Map<?, ?>) inbound(table);

        assertEquals(Set.of("name", "size"), map.keySet());
        assertArrayEquals("abc".getBytes(UTF_8), (byte[]) map.get("name"));
        assertEquals(3.0, (Double) map.get("size"));
    }

    @Test
    void aNumericKeyBecomesADouble() {
        LuaTable table = new LuaTable();
        table.set(1, LuaValue.valueOf("a"));

        Map<?, ?> map = (Map<?, ?>) inbound(table);

        assertEquals(Set.of(1.0), map.keySet());
    }

    @Test
    void aBooleanKeyIsRejected() {
        LuaTable table = new LuaTable();
        table.set(LuaValue.TRUE, LuaValue.valueOf("a"));

        assertThrows(ComponentException.class, () -> inbound(table));
    }

    @Test
    void aKeyWhoseBytesAreNotValidUtf8IsRejected() {
        LuaTable table = new LuaTable();
        table.set(LuaValue.valueOf(new byte[] { (byte) 0xFF }), LuaValue.valueOf("a"));

        assertThrows(ComponentException.class, () -> inbound(table));
    }

    @Test
    void aNestedTableBecomesANestedMap() {
        LuaTable inner = new LuaTable();
        inner.set(LuaValue.valueOf("deep"), LuaValue.TRUE);

        LuaTable outer = new LuaTable();
        outer.set(LuaValue.valueOf("inner"), inner);

        Map<?, ?> map = (Map<?, ?>) inbound(outer);

        assertEquals(Map.of("deep", true), map.get("inner"));
    }

    @Test
    void anEmptyMapBecomesAnEmptyTable() {
        LuaTable table = outboundTable(Map.of());

        assertTrue(table.next(LuaValue.NIL).isnil(1));
    }

    @Test
    void aMapBecomesATableWithTheSameEntries() {
        LuaTable table = outboundTable(Map.of("name", "abc".getBytes(UTF_8)));

        LuaValue value = table.get(LuaValue.valueOf("name"));
        assertEquals(LuaValue.TSTRING, value.type());
        assertArrayEquals("abc".getBytes(UTF_8), bytesOf(value.checkstring()));
    }

    @Test
    void aListBecomesKeysOneToN() {
        LuaTable table = outboundTable(List.of(true, false));

        assertEquals(2, table.length());
        assertTrue(table.get(0).isnil());   // Lua indexes from 1, not 0
        assertTrue(table.get(1).toboolean());
        assertFalse(table.get(2).toboolean());
    }

    @Test
    void aStringIsAValidKeyButNotAValidValue() {
        LuaTable table = outboundTable(Map.of("name", true));

        assertEquals("string", table.next(LuaValue.NIL).arg(1).typename());
        assertTrue(table.get(LuaValue.valueOf("name")).toboolean());

        assertThrows(IllegalStateException.class, () -> outbound(Map.of("name", "abc")));
    }

    @Test
    void aNullValueMakesTheKeyDisappear() {
        Map<String, Object> source = new HashMap<>();
        source.put("gone", null);
        source.put("kept", true);

        LuaTable table = outboundTable(source);

        assertTrue(table.get(LuaValue.valueOf("gone")).isnil());
        assertTrue(table.get(LuaValue.valueOf("kept")).toboolean());
    }

    @Test
    void nestingUpToTheDepthLimitIsPermitted() {
        LuaTable twoLevels = tableOf("a", tableOf("b", LuaValue.TRUE));

        assertDoesNotThrow(() -> tightly(2, 10).toJava(twoLevels));
    }

    @Test
    void nestingPastTheDepthLimitIsRejected() {
        LuaTable threeLevels = tableOf("a", tableOf("b", tableOf("c", LuaValue.TRUE)));

        assertThrows(ComponentException.class, () -> tightly(2, 10).toJava(threeLevels));
    }

    @Test
    void aTableAtTheEntryBudgetIsPermitted() {
        assertDoesNotThrow(() -> tightly(8, 10).toJava(tableWithEntries(10)));
    }

    @Test
    void aTablePastTheEntryBudgetIsRejected() {
        assertThrows(ComponentException.class,
                () -> tightly(8, 10).toJava(tableWithEntries(11)));
    }

    @Test
    void theEntryBudgetIsTotalNotPerTable() {
        LuaTable outer = new LuaTable();
        outer.set(LuaValue.valueOf("first"), tableWithEntries(6));
        outer.set(LuaValue.valueOf("second"), tableWithEntries(6));

        assertThrows(ComponentException.class, () -> tightly(8, 10).toJava(outer));
    }

    @Test
    void aSelfReferencingTableFailsOnDepthNotOnStackOverflow() {
        LuaTable table = new LuaTable();
        table.set(LuaValue.valueOf("self"), table);

        assertThrows(ComponentException.class, () -> tightly(2, 10).toJava(table));
    }

    @Test
    void anOutboundCycleFailsOnDepthNotOnStackOverflow() {
        Map<String, Object> map = new HashMap<>();
        map.put("self", map);

        assertThrows(IllegalStateException.class,
                () -> tightly(2, 10).toLua(new Object[] { map }));
    }
}
