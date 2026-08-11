package io.github.codewithcharles.mcomputer.core.screen;

import java.util.Arrays;

/**
 * A fixed-size grid of bytes, one byte per cell.
 *
 * <p>A cell holds a raw byte and never a decoded character. A script's output
 * is a sequence of bytes, and no decoding rule can be both total and honest at
 * this layer: the accessor cannot refuse a byte the way {@code checkText} can
 * refuse an argument, because a script printing a stray byte must not die.
 * Mapping a byte to a glyph belongs to the renderer, at the far end.
 *
 * <p>Cells carry no colour. Nothing can set one until a graphics component
 * exists.
 *
 * <p>Not synchronised. It is written on the server thread, through the same
 * hand-off every other side effect of a script takes.
 *
 * <p>Invariant: every row at or below the write position is blank. It holds by
 * construction, by {@link #clear()} and by scrolling, and it is what lets
 * {@link #writeLine} leave the tail of a short line alone instead of blanking
 * it explicitly.
 */
public final class ScreenBuffer {

    /** A blank cell. Also what {@link #clear()} writes. */
    public static final byte BLANK = (byte) ' ';

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
     *         for a cell that does not exist is a caller bug, not a state to
     *         branch on.
     */
    public byte byteAt(int column, int row) {
        if (column < 0 || column >= width || row < 0 || row >= height) {
            throw new IndexOutOfBoundsException(
                    "cell " + column + "," + row + " is outside " + width + "x" + height);
        }
        return cells[row * width + column];
    }

    /**
     * Writes one line, then advances. A line longer than {@link #width()} wraps
     * onto the following rows rather than being truncated. When the last row is
     * full the whole grid scrolls up by one and the bottom row is blanked.
     *
     * @param line the raw bytes of the line, without a terminator
     */
    public void writeLine(byte[] line) {
        int written = 0;
        do {
            if (nextRow == height) {
                scroll();
            }
            int chunk = Math.min(width, line.length - written);
            System.arraycopy(line, written, cells, nextRow * width, chunk);
            written += chunk;
            nextRow++;
        } while (written < line.length);
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
}
