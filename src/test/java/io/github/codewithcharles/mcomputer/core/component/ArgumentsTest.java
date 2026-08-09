package io.github.codewithcharles.mcomputer.core.component;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static java.nio.charset.StandardCharsets.UTF_8;


public class ArgumentsTest {

    private static Arguments of(Object... values) {
        return new Arguments(values);
    }

    @Test
    void countIsTheNumberOfValuesPassed() {
        assertEquals(2, of(1.0, Boolean.TRUE).count());
        assertEquals(0, of().count());
    }

    @Test
    void aPositionPastTheEndIsNull() {
        assertTrue(of(1.0).isNull(1));
        assertTrue(of().isNull(0));
    }

    @Test
    void anExplicitNilIsNull() {
        assertTrue(of(1.0, null).isNull(1));
    }

    @Test
    void aPresentValueIsNotNull() {
        assertFalse(of(1.0).isNull(0));
    }

    @Test
    void aNegativeIndexIsAProgrammingError() {
        assertThrows(IndexOutOfBoundsException.class, () -> of(1.0).isNull(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> of(1.0).raw(-1));
    }

    @Test
    void rawReturnsTheValueUntouched() {
        byte[] bytes = { 0x41 };
        assertSame(bytes, of(bytes).raw(0));
    }

    @Test
    void rawIsNullPastTheEnd() {
        assertNull(of(1.0).raw(1));
    }

    @Test
    void aNullArrayIsAProgrammingError() {
        assertThrows(NullPointerException.class, () -> new Arguments(null));
    }

    // --- checkBoolean -----------------------------------------------------

    @Test
    void checkBooleanReturnsThePresentBoolean() {
        assertTrue(of(Boolean.TRUE).checkBoolean(0));
        assertFalse(of(Boolean.FALSE).checkBoolean(0));
    }

    @Test
    void checkBooleanRejectsAnotherType() {
        ComponentException thrown = assertThrows(ComponentException.class,
                () -> of(1.0).checkBoolean(0));
        assertEquals("bad argument #1 (boolean expected, got number)", thrown.getMessage());
    }

    @Test
    void checkBooleanRejectsAMissingArgument() {
        ComponentException thrown = assertThrows(ComponentException.class,
                () -> of().checkBoolean(0));
        assertEquals("bad argument #1 (boolean expected, got nil)", thrown.getMessage());
    }

    // --- checkDouble ------------------------------------------------------

    @Test
    void checkDoubleReturnsThePresentNumber() {
        assertEquals(1.5, of(1.5).checkDouble(0));
    }

    @Test
    void checkDoubleRejectsANumericString() {
        ComponentException thrown = assertThrows(ComponentException.class,
                () -> of(new byte[] { 0x34 }).checkDouble(0));
        assertEquals("bad argument #1 (number expected, got string)", thrown.getMessage());
    }

    // --- checkInt ---------------------------------------------------------

    @Test
    void checkIntAcceptsAWholeDouble() {
        assertEquals(3, of(3.0).checkInt(0));
        assertEquals(-7, of(-7.0).checkInt(0));
        assertEquals(0, of(-0.0).checkInt(0));
    }

    @Test
    void checkIntAcceptsTheIntBounds() {
        assertEquals(Integer.MAX_VALUE, of((double) Integer.MAX_VALUE).checkInt(0));
        assertEquals(Integer.MIN_VALUE, of((double) Integer.MIN_VALUE).checkInt(0));
    }

    @Test
    void checkIntRejectsAFraction() {
        ComponentException thrown = assertThrows(ComponentException.class,
                () -> of(1.5).checkInt(0));
        assertEquals("bad argument #1 (number has no integer representation)",
                thrown.getMessage());
    }

    @Test
    void checkIntRejectsOutOfRange() {
        assertThrows(ComponentException.class,
                () -> of(Integer.MAX_VALUE + 1.0).checkInt(0));
        assertThrows(ComponentException.class,
                () -> of(Integer.MIN_VALUE - 1.0).checkInt(0));
    }

    @Test
    void checkIntRejectsNaNAndInfinity() {
        assertThrows(ComponentException.class, () -> of(Double.NaN).checkInt(0));
        assertThrows(ComponentException.class,
                () -> of(Double.POSITIVE_INFINITY).checkInt(0));
    }

    @Test
    void checkIntStillReportsATypeErrorAsATypeError() {
        ComponentException thrown = assertThrows(ComponentException.class,
                () -> of(Boolean.TRUE).checkInt(0));
        assertEquals("bad argument #1 (number expected, got boolean)", thrown.getMessage());
    }

    // --- checkBytes -------------------------------------------------------

    @Test
    void checkBytesReturnsTheBytesUntouched() {
        byte[] bytes = { 0x41, (byte) 0xFF };
        assertSame(bytes, of(bytes).checkBytes(0));
    }

    @Test
    void checkBytesRejectsANumber() {
        ComponentException thrown = assertThrows(ComponentException.class,
                () -> of(1.0).checkBytes(0));
        assertEquals("bad argument #1 (string expected, got number)", thrown.getMessage());
    }

    // --- checkText --------------------------------------------------------

    @Test
    void checkTextDecodesUtf8() {
        assertEquals("caf\u00e9", of("caf\u00e9".getBytes(UTF_8)).checkText(0));
    }

    @Test
    void checkTextRejectsInvalidUtf8() {
        ComponentException thrown = assertThrows(ComponentException.class,
                () -> of(new byte[] { (byte) 0xFF }).checkText(0));
        assertEquals("bad argument #1 (invalid UTF-8 string)", thrown.getMessage());
    }

    @Test
    void checkTextRejectsANumber() {
        ComponentException thrown = assertThrows(ComponentException.class,
                () -> of(1.0).checkText(0));
        assertEquals("bad argument #1 (string expected, got number)", thrown.getMessage());
    }

    // --- optional ---------------------------------------------------------

    @Test
    void anAbsentArgumentYieldsTheFallback() {
        assertTrue(of().optBoolean(0, true));
        assertEquals(2.5, of().optDouble(0, 2.5));
        assertEquals(7, of().optInt(0, 7));
        assertNull(of().optBytes(0, null));
        assertEquals("none", of().optText(0, "none"));
    }

    @Test
    void anExplicitNilYieldsTheFallback() {
        assertEquals(7, of((Object) null).optInt(0, 7));
        assertEquals("none", of((Object) null).optText(0, "none"));
    }

    @Test
    void aPresentValueWinsOverTheFallback() {
        assertEquals(1.5, of(1.5).optDouble(0, 2.5));
        assertEquals(3, of(3.0).optInt(0, 7));
        assertEquals("here", of("here".getBytes(UTF_8)).optText(0, "none"));
    }

    @Test
    void aPresentFalseIsNotAnAbsentArgument() {
        assertFalse(of(Boolean.FALSE).optBoolean(0 , true));
    }

    @Test
    void optBytesReturnsThePresentBytesUntouched() {
        byte[] bytes = { 0x41 };
        assertSame(bytes, of(bytes).optBytes(0, new byte[0]));
    }

    @Test
    void aPresentValueOfTheWrongTypeStillFails() {
        ComponentException thrown = assertThrows(ComponentException.class,
                () -> of(1.0).optText(0, "none"));
        assertEquals("bad argument #1 (string expected, got number)", thrown.getMessage());
    }

    @Test
    void optIntStillRejectsAFraction() {
        ComponentException thrown = assertThrows(ComponentException.class,
                () -> of(1.5).optInt(0, 7));
        assertEquals("bad argument #1 (number has no integer representation)",
                thrown.getMessage());
    }

    @Test
    void optTextStillRejectsInvalidUtf8() {
        assertThrows(ComponentException.class,
                () -> of(new byte[] { (byte) 0xFF }).optText(0, "none"));
    }
}