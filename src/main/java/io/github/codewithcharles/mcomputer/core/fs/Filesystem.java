package io.github.codewithcharles.mcomputer.core.fs;

import io.github.codewithcharles.mcomputer.core.component.Arguments;
import io.github.codewithcharles.mcomputer.core.component.ComponentApi;
import io.github.codewithcharles.mcomputer.core.component.ComponentException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The disk as a script sees it: paths in, boundary values out, over one
 * {@link DiskImage}.
 *
 * <p>It lives in {@code core} because its domain does, like the gpu over the
 * screen. Producing the component stays with the adapter: deciding which image
 * belongs to which computer, and minting its address.
 *
 * <p>This is the layer that renders and the layer that obliges. The store
 * answers with bare names and refuses to build a branch or to remove one
 * recursively; the trailing slash, those conveniences and the handles are
 * written here.
 */
public final class Filesystem {

    private Filesystem() {
    }

    /**
     * @param image the disk these methods work on.
     * @param maxHandles how many files one computer may hold open at a time.
     *                   Sixteen is what the reference implementation allows.
     */
    public static ComponentApi api(DiskImage image, int maxHandles) {
        OpenFiles files = new OpenFiles(maxHandles);
        return ComponentApi.builder("filesystem")
                .method("exists", arguments ->
                        new Object[] { image.exists(path(arguments)) })
                .method("isDirectory", arguments ->
                        new Object[] { image.isDirectory(path(arguments)) })
                .method("size", arguments ->
                        new Object[] { (double) image.size(path(arguments)) })
                .method("list", arguments ->
                        new Object[] { listed(image, path(arguments)) })
                .method("spaceTotal", arguments ->
                        new Object[] { (double) image.spaceTotal() })
                .method("spaceUsed", arguments ->
                        new Object[] { (double) image.spaceUsed() })
                .method("makeDirectory", arguments ->
                        new Object[] { makeBranch(image, path(arguments)) })
                .method("remove", arguments ->
                        new Object[] { removeBranch(image, path(arguments)) })
                .method("rename", arguments ->
                        new Object[] { image.rename(path(arguments), path(arguments, 1)) })
                .method("open", arguments -> new Object[] {
                        open(image, files, path(arguments), arguments.checkText(1)) })
                .method("close", arguments -> {
                    double number = arguments.checkDouble(0);
                    fileOf(files, number);
                    files.open.remove(number);
                    return new Object[0];
                })
                .method("read", arguments -> {
                    OpenFile file = fileOf(files, arguments.checkDouble(0));
                    byte[] read = image.read(file.path, file.position, arguments.checkInt(1));
                    file.position += read.length;
                    return new Object[] { read.length == 0 ? null : read };
                })
                .method("write", arguments -> {
                    OpenFile file = fileOf(files, arguments.checkDouble(0));
                    if (!file.writable) {
                        throw new ComponentException("file is open for reading");
                    }
                    byte[] data = arguments.checkBytes(1);
                    image.write(file.path, file.position, data);
                    file.position += data.length;
                    return new Object[] { true };
                })
                .method("seek", arguments -> {
                    OpenFile file = fileOf(files, arguments.checkDouble(0));
                    return new Object[] { (double) seek(image, file,
                            arguments.checkText(1), arguments.checkInt(2)) };
                })
                .build();
    }

    /** The one place a path is decoded, so the UTF-8 rule arrives once. */
    private static String path(Arguments arguments) {
        return arguments.checkText(0);
    }

    private static String path(Arguments arguments, int index) {
        return arguments.checkText(index);
    }

    /** The open files of one component, and the next number to hand out. */
    private static final class OpenFiles {
        private final Map<Double, OpenFile> open = new LinkedHashMap<>();
        private final int max;
        private double next = 1;

        OpenFiles(int max) {
            this.max = max;
        }
    }

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

    /**
     * Only the first letter of the mode is read, so the binary variants are
     * accepted and ignored: everything here is bytes already, which makes the
     * distinction meaningless rather than wrong.
     */
    private static double open(DiskImage image, OpenFiles files, String path, String mode) {
        if (files.open.size() >= files.max) {
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
        double number = files.next++;
        files.open.put(number, new OpenFile(path, kind != 'r', position));
        return number;
    }

    private static OpenFile fileOf(OpenFiles files, double number) {
        OpenFile file = files.open.get(number);
        if (file == null) {
            throw new ComponentException("bad file handle");
        }
        return file;
    }

    /**
     * The three whences Lua's own io library uses. A position before the start
     * is refused; one past the end is not, a write there filling the gap.
     */
    private static int seek(DiskImage image, OpenFile file, String whence, int offset) {
        int base = switch(whence) {
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

    /**
     * Bare below, marked here. A name cannot hold a slash, since a segment
     * neve3r does, so the mark is unambiguous
     */
    private static List<byte[]> listed(DiskImage image, String path) {
        List<byte[]> names = new ArrayList<>();
        for (String name : image.list(path)) {
            String marked = image.isDirectory(path + "/" + name) ? name + "/" : name;
            names.add(marked.getBytes(StandardCharsets.UTF_8));
        }
        return names;
    }

    /**
     * Builds the branch one level at a time, the store making a single
     * directory at a time.
     *
     * @return {@code false} when the leaf was already there, and when a segment
     *         of the path is a file
     */
    private static boolean makeBranch(DiskImage image, String path) {
        StringBuilder walked = new StringBuilder();
        boolean made = false;
        for (String segment : DiskImage.segmentsOf(path)) {
            walked.append('/').append(segment);
            String step = walked.toString();
            made = image.makeDirectory(step);
            if (!made && !image.isDirectory(step)) {
                return false;
            }
        }
        return made;
    }

    /**
     * Empties a directory before removing it, the store refusing a directory
     * that has children. Safe to iterate: {@code list} hands back a copy.
     */
    private static boolean removeBranch(DiskImage image, String path) {
        if (!image.exists(path)) {
            return false;
        }
        if (image.isDirectory(path)) {
            for (String name : image.list(path)) {
                removeBranch(image, path + "/" + name);
            }
        }
        return image.remove(path);
    }
}