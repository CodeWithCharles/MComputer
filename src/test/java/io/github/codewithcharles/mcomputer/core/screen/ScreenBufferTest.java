package io.github.codewithcharles.mcomputer.core.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

final class ScreenBufferTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static String rowText(ScreenBuffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int column = 0; column < buffer.width(); column++) {
            text.append((char) (buffer.byteAt(column, row) & 0xFF));
        }
        return text.toString();
    }

    @Test
    void aBufferKeepsTheDimensionsItWasGiven() {
        ScreenBuffer buffer = new ScreenBuffer(80, 25);

        assertEquals(80, buffer.width());
        assertEquals(25, buffer.height());
    }

    @Test
    void aWidthOfZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ScreenBuffer(0, 25));
    }

    @Test
    void aHeightOfZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ScreenBuffer(80, 0));
    }

    @Test
    void aFreshBufferIsBlankEverywhere() {
        ScreenBuffer buffer = new ScreenBuffer(3, 2);

        for (int row = 0; row < buffer.height(); row++) {
            for (int column = 0; column < buffer.width(); column++) {
                assertEquals(ScreenBuffer.BLANK, buffer.byteAt(column, row),
                        "cell " + column + "," + row);
            }
        }
    }

    @Test
    void aCellOutsideTheGridIsRejected() {
        ScreenBuffer buffer = new ScreenBuffer(3, 2);

        assertThrows(IndexOutOfBoundsException.class, () -> buffer.byteAt(3, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.byteAt(0, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.byteAt(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.byteAt(0, -1));
    }

    @Test
    void aLineLandsOnTheFirstRow() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);

        buffer.writeLine(bytes("ab"));

        assertEquals("ab   ", rowText(buffer, 0));
        assertEquals("     ", rowText(buffer, 1));
        assertEquals("     ", rowText(buffer, 2));
    }

    @Test
    void theNextLineLandsOnTheRowBelow() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);

        buffer.writeLine(bytes("ab"));
        buffer.writeLine(bytes("cd"));

        assertEquals("ab   ", rowText(buffer, 0));
        assertEquals("cd   ", rowText(buffer, 1));
        assertEquals("     ", rowText(buffer, 2));
    }

    @Test
    void aByteThatIsNotTextSurvivesIntact() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);

        buffer.writeLine(new byte[] {(byte) 0xFF});

        assertEquals((byte) 0xFF, buffer.byteAt(0, 0));
    }

    @Test
    void aLineLongerThanTheGridWrapsOntoTheNextRow() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);

        buffer.writeLine(bytes("abcdefg"));

        assertEquals("abcde", rowText(buffer, 0));
        assertEquals("fg   ", rowText(buffer, 1));
        assertEquals("     ", rowText(buffer, 2));
    }

    @Test
    void aWrappedLineLeavesTheWritePositionBelowItsLastRow() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);

        buffer.writeLine(bytes("abcdefg"));
        buffer.writeLine(bytes("z"));

        assertEquals("abcde", rowText(buffer, 0));
        assertEquals("fg   ", rowText(buffer, 1));
        assertEquals("z    ", rowText(buffer, 2));
    }

    @Test
    void anEmptyLineStillAdvances() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);

        buffer.writeLine(new byte[0]);
        buffer.writeLine(bytes("z"));

        assertEquals("     ", rowText(buffer, 0));
        assertEquals("z    ", rowText(buffer, 1));
    }

    @Test
    void writingPastTheLastRowScrollsEverythingUp() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);

        buffer.writeLine(bytes("aaa"));
        buffer.writeLine(bytes("bbb"));
        buffer.writeLine(bytes("ccccc"));
        buffer.writeLine(bytes("d"));

        assertEquals("bbb  ", rowText(buffer, 0));
        assertEquals("ccccc", rowText(buffer, 1));
    }

    @Test
    void scrollingBlanksTheRowItFrees() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);

        buffer.writeLine(bytes("aaa"));
        buffer.writeLine(bytes("bbb"));
        buffer.writeLine(bytes("ccccc"));
        buffer.writeLine(bytes("d"));

        assertEquals("d    ", rowText(buffer, 2));
    }

    @Test
    void aLineThatWrapsPastTheBottomScrollsMidWrite() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);

        buffer.writeLine(bytes("aaa"));
        buffer.writeLine(bytes("bbb"));
        buffer.writeLine(bytes("ccc"));
        buffer.writeLine(bytes("1234567"));

        assertEquals("ccc  ", rowText(buffer, 0));
        assertEquals("12345", rowText(buffer, 1));
        assertEquals("67   ", rowText(buffer, 2));
    }

    @Test
    void clearingBlanksEveryCell() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);

        buffer.writeLine(bytes("aaaaa"));
        buffer.writeLine(bytes("bbbbb"));
        buffer.clear();

        assertEquals("     ", rowText(buffer, 0));
        assertEquals("     ", rowText(buffer, 1));
        assertEquals("     ", rowText(buffer, 2));
    }

    @Test
    void clearingReturnsTheWritePositionToTheTop() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);

        buffer.writeLine(bytes("aaaaa"));
        buffer.writeLine(bytes("bbbbb"));
        buffer.clear();
        buffer.writeLine(bytes("z"));

        assertEquals("z    ", rowText(buffer, 0));
    }
}