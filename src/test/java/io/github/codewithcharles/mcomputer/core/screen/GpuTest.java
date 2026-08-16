package io.github.codewithcharles.mcomputer.core.screen;

import io.github.codewithcharles.mcomputer.core.component.Arguments;
import io.github.codewithcharles.mcomputer.core.component.ComponentApi;
import io.github.codewithcharles.mcomputer.core.component.ComponentException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class GpuTest {

    private final ScreenBuffer _screen = new ScreenBuffer(5, 2);
    private final ComponentApi _gpu = Gpu.api(_screen);

    /** A copy of ScreenBufferTest's, so that suite stays out of this diff. */
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** A copy of ScreenBufferTest's, for the same reason. */
    private static String rowText(ScreenBuffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int column = 0; column < buffer.width(); column++) {
            text.append((char) (buffer.byteAt(column, row) & 0xFF));
        }
        return text.toString();
    }

    private Object[] invoke(String method, Object... arguments) {
        return _gpu.method(method).orElseThrow().invoke(new Arguments(arguments, method));
    }

    /** What component.list() shows, and what a script matches on. */
    @Test
    void theTypeIsGpu() {
        assertEquals("gpu", _gpu.type());
    }

    /**
     * The fourth one-based shift in this project, and the fixture catches it on
     * both axes: a forgotten subtraction on x moves the text to "  ab ", one on
     * y leaves row 0 blank and fills row 1.
     */
    @Test
    void setWritesAtOneBasedCoordinates() {
        invoke("set", 2.0, 1.0, bytes("ab"));

        assertEquals(" ab  ", rowText(_screen, 0));
        assertEquals("     ", rowText(_screen, 1));
    }

    /**
     * A coordinate outside the screen is the script's mistake, not ours.
     * ScreenBuffer throws IndexOutOfBoundsException, which would leave luaj as
     * a HostFailure and kill the machine for a typo.
     *
     * <p>The two indices are asserted apart on purpose: they are what says the
     * error names the argument the script actually got wrong.
     */
    @Test
    void setRejectsACoordinateOutsideTheScreen() {
        ComponentException column = assertThrows(ComponentException.class,
                () -> invoke("set", 0.0, 1.0, bytes("a")));
        ComponentException row = assertThrows(ComponentException.class,
                () -> invoke("set", 1.0, 3.0, bytes("a")));

        assertTrue(column.getMessage().startsWith("bad argument #1 to 'set' ("),
                "message was: " + column.getMessage());
        assertTrue(row.getMessage().startsWith("bad argument #2 to 'set' ("),
                "message was: " + row.getMessage());
    }

    /**
     * set returns nothing while no caller reads a result, and adding a return
     * value later breaks no Lua script. Pinned so the choice gets made again
     * rather than drifted into.
     */
    @Test
    void setReturnsNothing() {
        assertEquals(0, invoke("set", 1.0, 1.0, bytes("a")).length);
    }

    @Test
    void getResolutionGivesTheScreensSize() {
        assertArrayEquals(new Object[] { 5.0, 2.0 }, invoke("getResolution"));
    }

    /** Synchronous, unlike print: a shell that asks where it is must not race. */
    @Test
    void writeLaysALineAndAdvances() {
        invoke("write", bytes("ab"));
        invoke("write", bytes("cd"));

        assertEquals("ab   ", rowText(_screen, 0));
        assertEquals("cd   ", rowText(_screen, 1));
    }

    /**
     * Zero on an untouched screen, which is a row {@code set} refuses. A caller
     * that wants a row of its own writes first and asks after.
     */
    @Test
    void theCursorIsTheRowWriteLastUsed() {
        assertEquals(0.0, invoke("getCursor")[0]);

        invoke("write", bytes("a"));

        assertEquals(1.0, invoke("getCursor")[0]);
    }

    /**
     * The suite's decision test. A cursor answering where the next line lands
     * would say 3 here, on a screen two rows tall, or be clamped to 2 and mean
     * two different states with one number. Saying which row was last used is
     * true before and after the scroll, and it is always a row set accepts.
     */
    @Test
    void aFullScreenKeepsTheCursorOnItsLastRow() {
        invoke("write", bytes("a"));
        invoke("write", bytes("b"));
        invoke("write", bytes("c"));

        assertEquals(2.0, invoke("getCursor")[0]);
        assertEquals("c    ", rowText(_screen, 1));
        assertDoesNotThrow(() -> invoke("set", 1.0, invoke("getCursor")[0], bytes("x")));
        assertEquals("x    ", rowText(_screen, 1));
    }
}