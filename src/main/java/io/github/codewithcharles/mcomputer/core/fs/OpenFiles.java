package io.github.codewithcharles.mcomputer.core.fs;

import io.github.codewithcharles.mcomputer.core.component.ComponentException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The files one computer holds open on one disk.
 *
 * <p>A handle is a number, which is what the value boundary requires of
 * anything object-like: this class is what the number points at, and the number
 * is what a script reads. It is never reused, or a stale handle would silently
 * address another file.
 *
 * <p><b>An open file holds a path, not a node.</b> A file removed under a
 * handle then fails the next read with "no such file" instead of writing into a
 * branch nothing reaches any more.
 *
 * <p>It speaks handles and paths, never {@code Arguments}: what a script sent
 * has already been checked when a call arrives here.
 */
final class OpenFiles {

    /** Where a script stands in a file. The position moves, so not a record. */
    private static final class OpenFile {
        private final String path;
        private final boolean writable;
        private int position;

        OpenFile(String path, boolean writable, int position) {
            this.path = path;
            this.writable = writable;
            this.position = position;
        }
    }

    private final Map<Double, OpenFile> byHandle = new LinkedHashMap<>();
    private final DiskImage image;
    private final int max;
    private double next = 1;

    /**
     * @param image the disk these handles point into
     * @param max   how many may be open at a time
     */
    OpenFiles(DiskImage image, int max) {
        this.image = image;
        this.max = max;
    }

    /**
     * Only the first letter of the mode is read, so the binary variants are
     * accepted and ignored: everything here is bytes already, which makes the
     * distinction meaningless rather than wrong.
     */
    double open(String path, String mode) {
        if (byHandle.size() >= max) {
            throw new ComponentException("too many open handles");
        }
        char kind = mode.isEmpty() ? '?' : mode.charAt(0);
        int position = 0;
        switch (kind) {
            case 'r' -> {
                if (!image.exists(path) || image.isDirectory(path)) {
                    throw new ComponentException("no such file: '" + path + "'");
                }
            }
            case 'w' -> {
                if (!image.createFile(path)) {
                    image.truncate(path);
                }
            }
            case 'a' -> {
                image.createFile(path);
                position = (int) image.size(path);
            }
            default -> throw new ComponentException("unknown mode '" + mode + "'");
        }
        double handle = next++;
        byHandle.put(handle, new OpenFile(path, kind != 'r', position));
        return handle;
    }

    /** Closing a handle nobody holds is an error, so the removal is the check. */
    void close(double handle) {
        if (byHandle.remove(handle) == null) {
            throw new ComponentException("bad file handle");
        }
    }

    /** @return what is there, empty at the end of the file */
    byte[] read(double handle, int count) {
        OpenFile file = fileOf(handle);
        byte[] read = image.read(file.path, file.position, count);
        file.position += read.length;
        return read;
    }

    void write(double handle, byte[] data) {
        OpenFile file = fileOf(handle);
        if (!file.writable) {
            throw new ComponentException("file is open for reading");
        }
        image.write(file.path, file.position, data);
        file.position += data.length;
    }

    /**
     * The three whences Lua's own io library uses. A position before the start
     * is refused; one past the end is not, a write there filling the gap.
     */
    int seek(double handle, String whence, int offset) {
        OpenFile file = fileOf(handle);
        int base = switch (whence) {
            case "set" -> 0;
            case "cur" -> file.position;
            case "end" -> (int) image.size(file.path);
            default -> throw new ComponentException("unknown whence '" + whence + "'");
        };
        int position = base + offset;
        if (position < 0) {
            throw new ComponentException("cannot seek before the start, got " + position);
        }
        file.position = position;
        return position;
    }

    private OpenFile fileOf(double handle) {
        OpenFile file = byHandle.get(handle);
        if (file == null) {
            throw new ComponentException("bad file handle");
        }
        return file;
    }
}
