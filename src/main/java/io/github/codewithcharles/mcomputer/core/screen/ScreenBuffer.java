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
 * <p>Exactly one byte is <b>read</b> rather than stored: {@code '\n'} ends a
 * line. That is not a decoding rule sneaking back in - ASCII, Latin-1, CP437 and
 * UTF-8 agree on it, so nothing here chooses an encoding - and this grid is a
 * terminal's storage rather than a framebuffer: it already owns wrapping,
 * advancing and scrolling, and a line break is of that family. <b>The list is
 * closed at that one byte.</b> {@code '\t'}, {@code '\r'} and every other
 * control byte are stored and will be drawn as glyphs. Widening it is a
 * decision, not a fix.
 *
 * <p>Cells carry no colour. Nothing can set one until a graphics component
 * exists.
 *
 * <p>Not synchronised, and it is the adapter's job to keep that true. A script's
 * output does <b>not</b> reach this class on the server thread: {@code print}
 * calls the sink from the Lua thread, and a compile failure calls it from the
 * server thread inside {@code Machine.start()}. The adapter therefore parks
 * lines in a thread-safe queue and drains them here on the tick. Every method
 * below assumes a single thread and nothing enforces it.
 *
 * <p>Invariant: every row at or below the write position is blank. It holds by
 * construction, by {@link #clear()} and by scrolling, and it is what lets
 * {@link #writeLine} leave the tail of a short line alone instead of blanking
 * it explicitly.
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
     * Writes one line, then advances. A segment longer than {@link #width()}
     * wraps onto the following rows rather than being truncated. When the last
     * row is full the whole grid scrolls up by one and the bottom row is
     * blanked.
     *
     * <p>{@code '\n'} ends a line and is not stored. It <b>separates</b> rather
     * than terminates, so {@code "ab\n"} leaves a blank row below {@code ab}:
     * our own {@code print} already supplies a line end, and real Lua's
     * {@code print("a\n")} does output a blank line. No other byte is read - the
     * class javadoc carries the closed list.
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
     * needed. The overflow check is <b>inside</b> the loop and not in front of
     * it: seven bytes at the bottom of a full five-wide grid scroll twice within
     * one call, which is what aLineThatWrapsPastTheBottomScrollsMidWrite forces.
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
}
