package io.github.codewithcharles.mcomputer.core.component;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

final class ComponentExceptionTest {

    private static String messageOf(int index, String expected, Object actual) {
        return ComponentException.badArgument(index, expected, actual).getMessage();
    }

    @Test
    void theMessageIsWrittenInLuaIdiom() {
        assertEquals("bad argument #1 (string expected, got number)",
                messageOf(0, "string", 42.0));
    }

    @Test
    void theIndexIsRenderedOneBased() {
        assertEquals("bad argument #3 (number expected, got boolean)",
                messageOf(2, "number", Boolean.TRUE));
    }

    @Test
    void bytesAreCalledString() {
        assertEquals("bad argument #1 (number expected, got string)",
                messageOf(0, "number", new byte[] { 0x41 }));
    }

    @Test
    void nullIsCalledNil() {
        assertEquals("bad argument #1 (string expected, got nil)",
                messageOf(0, "string", null));
    }

    @Test
    void aMapIsCalledTable() {
        assertEquals("bad argument #1 (string expected, got table)",
                messageOf(0, "string", Map.of()));
    }

    @Test
    void aListIsCalledTable() {
        assertEquals("bad argument #1 (string expected, got table)",
                messageOf(0, "string", List.of()));
    }

    @Test
    void anOffBoundaryValueFallsBackToItsJavaName() {
        assertEquals("bad argument #1 (number expected, got Long)",
                messageOf(0, "number", 7L));
    }

    @Test
    void aReasonCanReplaceTheExpectedGotPair() {
        assertEquals("bad argument #2 (number has no integer representation)",
                ComponentException.badArgument(1, "number has no integer representation")
                        .getMessage());
    }
}