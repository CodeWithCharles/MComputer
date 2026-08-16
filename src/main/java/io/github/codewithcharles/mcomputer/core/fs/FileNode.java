package io.github.codewithcharles.mcomputer.core.fs;

import java.util.Arrays;

/**
 * The content of one file: a buffer and the length in use, so an append does
 * not recopy at every write.
 *
 * <p><b>Bytes above the length are zero by construction, not by a fill somebody
 * maintains.</b> {@link #write} only ever raises the length through
 * {@code Arrays.copyOf}, which zeroes what it adds, and {@link #truncate} hands
 * the buffer back rather than erasing it, so nothing survives above the length
 * to be read back by a later write past the end.
 *
 * <p>It charges nothing and refuses nothing. The room is the disk's business:
 * {@link #growthFor} says what a write would cost, so a caller can refuse
 * before anything here moves.
 */
final class FileNode implements Node {

    private byte[] bytes = new byte[0];
    private int length;

    /** What a disk charges for this file. Never the buffer, which doubles. */
    int length() {
        return length;
    }

    /**
     * What {@link #write} would add to the length.
     *
     * <p>In long: the offset comes from a script, and offset plus the data
     * length overflows an int into a negative end that passes every check a
     * caller could write.
     */
    long growthFor(int offset, int dataLength) {
        return Math.max(0L, (long) offset + dataLength - length);
    }

    /**
     * Writes at {@code offset}, growing the file when the write runs past the
     * end and leaving zeroes in any gap it opens.
     */
    void write(int offset, byte[] data) {
        int grown = (int) Math.max(length, (long) offset + data.length);
        if (grown > bytes.length) {
            // Doubling, so an append is amortised.
            bytes = Arrays.copyOf(bytes, Math.max(grown, bytes.length * 2));
        }
        System.arraycopy(data, 0, bytes, offset, data.length);
        length = grown;
    }

    /**
     * @return what is there, so shorter than {@code count} near the end of the
     *         file and empty past it
     */
    byte[] read(int offset, int count) {
        int from = Math.min(offset, length);
        // Not from + count: a count near Integer.MAX_VALUE overflows that sum
        // into a negative bound.
        int taken = Math.min(count, length - from);
        return Arrays.copyOfRange(bytes, from, from + taken);
    }

    /** @return how many bytes were freed, which is what a disk refunds */
    int truncate() {
        int freed = length;
        bytes = new byte[0];
        length = 0;
        return freed;
    }
}
