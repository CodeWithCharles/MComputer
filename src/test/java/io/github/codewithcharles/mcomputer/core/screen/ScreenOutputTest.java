package io.github.codewithcharles.mcomputer.core.screen;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ScreenOutputTest {

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
    void theBufferIsTheOneItWasGiven() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);

        ScreenOutput output = new ScreenOutput(buffer, 4);

        assertSame(buffer, output.buffer());
    }

    @Test
    void aMaxOfZeroIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenOutput(new ScreenBuffer(5, 3), 0));
    }

    @Test
    void aNullBufferIsRejected() {
        assertThrows(NullPointerException.class, () -> new ScreenOutput(null, 4));
    }

    @Test
    void aLineWaitsForTheDrain() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);
        ScreenOutput output = new ScreenOutput(buffer, 4);

        output.write(bytes("ab"));

        assertEquals("     ", rowText(buffer, 0));

        output.drain();

        assertEquals("ab   ", rowText(buffer, 0));
    }

    @Test
    void linesAreDrainedInTheOrderTheyWereWritten() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);
        ScreenOutput output = new ScreenOutput(buffer, 4);

        output.write(bytes("aa"));
        output.write(bytes("bb"));
        output.drain();

        assertEquals("aa   ", rowText(buffer, 0));
        assertEquals("bb   ", rowText(buffer, 1));
    }

    @Test
    void drainReturnsTheNumberOfLinesWritten() {
        ScreenOutput output = new ScreenOutput(new ScreenBuffer(5, 3), 4);

        output.write(bytes("aa"));
        output.write(bytes("bb"));

        assertEquals(2, output.drain());
    }

    @Test
    void drainingTwiceWritesNothingTheSecondTime() {
        ScreenBuffer buffer = new ScreenBuffer(5, 3);
        ScreenOutput output = new ScreenOutput(buffer, 4);

        output.write(bytes("aa"));
        output.drain();

        assertEquals(0, output.drain());
        assertEquals("aa   ", rowText(buffer, 0));
        assertEquals("     ", rowText(buffer, 1));
    }

    @Test
    void drainingAnEmptyQueueReturnsZero() {
        ScreenOutput output = new ScreenOutput(new ScreenBuffer(5, 3), 4);

        assertEquals(0, output.drain());
    }

    @Test
    void aFullQueueDropsItsOldestLine() {
        ScreenBuffer buffer = new ScreenBuffer(5, 5);
        ScreenOutput output = new ScreenOutput(buffer, 3);

        output.write(bytes("1"));
        output.write(bytes("2"));
        output.write(bytes("3"));
        output.write(bytes("4"));
        output.write(bytes("5"));
        output.drain();

        assertEquals("3    ", rowText(buffer, 0));
        assertEquals("4    ", rowText(buffer, 1));
        assertEquals("5    ", rowText(buffer, 2));
    }
}