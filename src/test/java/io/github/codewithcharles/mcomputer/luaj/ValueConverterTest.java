package io.github.codewithcharles.mcomputer.luaj;

import io.github.codewithcharles.mcomputer.core.component.BoundaryLimits;
import io.github.codewithcharles.mcomputer.core.component.ComponentException;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.stream.Stream;

public class ValueConverterTest {

    private final ValueConverter _converter = new ValueConverter(
            BoundaryLimits.defaults());

    /** LuaValue extends Varargs, so a single value is a one-element call. */
    private Object inbound(LuaValue value) {
        return _converter.toJava(value)[0];
    }

    private LuaValue outbound(Object value) {
        return _converter.toLua(new Object[] { value }).arg1();
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
}
