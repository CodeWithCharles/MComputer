package io.github.codewithcharles.mcomputer.core.screen;

import java.util.Arrays;

/**
 * A fixed-size grid of bytes, one byte per cell.
 *
 * <p>A cell holds a raw byte, never a decoded character. No decoding rule can
 * be both total and honest here: this accessor cannot refuse a byte the way
 * {@code checkText} refuses an argument, because a script printing a stray byte
 * must not die. Mapping a byte to a glyph belongs to the renderer.
 *
 * <p>Exactly one byte is read rather than stored: {@code '\n'} ends a line.
 * ASCII, Latin-1, CP437 and UTF-8 all agree on it, so nothing here chooses an
 * encoding, and this grid is a terminal's storage rather than a framebuffer -
 * it already owns wrapping, advancing and scrolling. <b>The list is closed at
 * that one byte.</b> {@code '\t'}, {@code '\r'} and the rest are stored and
 * drawn as glyphs.
 *
 * <p>Cells carry no colour. Nothing can set one until a graphics component
 * exists.
 *
 * <p>Not synchronised, and the adapter keeps that true. A script's output does
 * not reach this class on one thread: {@code print} calls the sink from the Lua
 * thread, a compile failure from the server thread inside
 * {@code Machine.start()}. The adapter parks lines in a thread-safe queue and
 * drains them here on the tick.
 *
 * <p>Invariant: every row at or below the write position is blank. It holds by
 * construction, by {@link #clear()} and by scrolling, and it is what lets
 * {@link #writeLine} leave the tail of a short line alone.
 */
public final class ScreenBuffer {

    /** A blank cell. Also what {@link #clear()} writes. */
    public static final byte BLANK = (byte) ' ';
    /** Ends a line. The only byte {@link #writeLine} reads instead of storing. */
    private static final byte NEWLINE = (byte) '\n';

    private final int width;
    private final int height;
    private final byte[] cells;
    private int nextRow;

    /**
     * @param width     columns, strictly positive
     * @param height    rows, strictly positive
     * @throws IllegalArgumentException if either is not strictly positive
     */
    public ScreenBuffer(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "screen dimensions must be positive, got " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.cells = new byte[width * height];
        Arrays.fill(cells, BLANK);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * The byte displayed at a cell.
     *
     * @throws IndexOutOfBoundsException if the cell is outside the grid. Asking
     *         for a cell that does not exist is a caller bug.
     */
    public byte byteAt(int column, int row) {
        if (column < 0 || column >= width || row < 0 || row >= height) {
            throw new IndexOutOfBoundsException(
                    "cell " + column + "," + row + " is outside " + width + "x" + height);
        }
        return cells[row * width + column];
    }

    /**
     * Writes one line, then advances. A segment longer than {@link #width()}
     * wraps onto the following rows rather than being truncated. When the last
     * row is full the grid scrolls up by one and the bottom row is blanked.
     *
     * <p>{@code '\n'} ends a line and is not stored. It separates rather than
     * terminates, so {@code "ab\n"} leaves a blank row below {@code ab}, which
     * is what real Lua's {@code print("a\n")} produces. No other byte is read.
     *
     * @param line the raw bytes of the line, with no terminator of its own
     */
    public void writeLine(byte[] line) {
        int start = 0;
        for (int i = 0; i < line.length; i++) {
            if (line[i] == NEWLINE) {
                writeSegment(line, start, i - start);
                start = i + 1;
            }
        }
        writeSegment(line, start, line.length - start);
    }

    /**
     * Lays one segment out from the write position, wrapping and scrolling as
     * needed. The overflow check is inside the loop, not in front of it: seven
     * bytes at the bottom of a full five-wide grid scroll twice in one call.
     */
    private void writeSegment(byte[] line, int offset, int length) {
        int written = 0;
        do {
            if (nextRow == height) {
                scroll();
            }
            int chunk = Math.min(width, length - written);
            System.arraycopy(line, offset + written, cells, nextRow * width, chunk);
            written += chunk;
            nextRow++;
        } while (written < length);
    }

    private void scroll() {
        System.arraycopy(cells, width, cells, 0, (height - 1) * width);
        Arrays.fill(cells, (height - 1) * width, cells.length, BLANK);
        nextRow = height - 1;
    }

    /** Blanks every cell and returns the write position to the first row. */
    public void clear() {
        Arrays.fill(cells, BLANK);
        nextRow = 0;
    }

    /** @return a copy of the array itself */
    public byte[] snapshot() {
        return cells.clone();
    }

    /**
     * The row the next line lands on. Ranges over 0 to {@link #height()}
     * inclusive: {@code height} is the ordinary state "the next write scrolls
     * first", which lazy scrolling makes normal.
     */
    public int writePosition() {
        return nextRow;
    }

    /**
     * Replaces every cell and the write position at once.
     *
     * <p>The position is not optional. Restoring a full grid while leaving the
     * position at zero yields a buffer whose rows below it are not blank - this
     * class's one invariant, broken by construction, on an object nothing would
     * flag as invalid.
     *
     * @param cells         exactly {@code width * height} bytes, row-major
     * @param writePosition where the next line lands, 0 to {@link #height()}
     * @throws IllegalArgumentException if either is out of shape
     */
    public void restore(byte[] cells, int writePosition) {
        if (cells.length != this.cells.length) {
            throw new IllegalArgumentException(
                    "expected " + this.cells.length + " cells, got " + cells.length);
        }
        if (writePosition < 0 || writePosition > height) {
            throw new IllegalArgumentException(
                    "write position " + writePosition + " is outside 0.." + height);
        }
        System.arraycopy(cells, 0, this.cells, 0, cells.length);
        this.nextRow = writePosition;
    }
}
