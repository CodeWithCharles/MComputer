package io.github.codewithcharles.mcomputer.core.fs;

import io.github.codewithcharles.mcomputer.core.component.Arguments;
import io.github.codewithcharles.mcomputer.core.component.ComponentApi;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
 * recursively; the trailing slash, those conveniences and the nil at the end of
 * a file are written here. The handles are {@link OpenFiles}'.
 *
 * <p>It is also the only layer here that sees an {@link Arguments}. What
 * crosses below is a path, a handle or bytes.
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
        OpenFiles files = new OpenFiles(image, maxHandles);
        return ComponentApi.builder("filesystem")
                .method("exists", arguments ->
                        new Object[] { image.exists(path(arguments, 0)) })
                .method("isDirectory", arguments ->
                        new Object[] { image.isDirectory(path(arguments, 0)) })
                .method("size", arguments ->
                        new Object[] { (double) image.size(path(arguments, 0)) })
                .method("list", arguments ->
                        new Object[] { listed(image, path(arguments, 0)) })
                .method("canonical", arguments ->
                        new Object[] { Paths.canonical(path(arguments, 0))
                                .getBytes(StandardCharsets.UTF_8) })
                .method("spaceTotal", arguments ->
                        new Object[] { (double) image.spaceTotal() })
                .method("spaceUsed", arguments ->
                        new Object[] { (double) image.spaceUsed() })
                .method("makeDirectory", arguments ->
                        new Object[] { makeBranch(image, path(arguments, 0)) })
                .method("remove", arguments ->
                        new Object[] { removeBranch(image, path(arguments, 0)) })
                .method("rename", arguments ->
                        new Object[] { image.rename(path(arguments, 0), path(arguments, 1)) })
                .method("open", arguments -> new Object[] {
                        files.open(path(arguments, 0), arguments.checkText(1)) })
                .method("close", arguments -> {
                    files.close(arguments.checkDouble(0));
                    return new Object[0];
                })
                .method("read", arguments -> {
                    byte[] read = files.read(arguments.checkDouble(0), arguments.checkInt(1));
                    // Nil at the end of the file, so a shell can loop until it
                    // stops. A rendering, hence here.
                    return new Object[] { read.length == 0 ? null : read };
                })
                .method("write", arguments -> {
                    files.write(arguments.checkDouble(0), arguments.checkBytes(1));
                    return new Object[] { true };
                })
                .method("seek", arguments -> new Object[] { (double) files.seek(
                        arguments.checkDouble(0),
                        arguments.checkText(1),
                        arguments.checkInt(2)) })
                .build();
    }

    /** The one place a path is decoded, so the UTF-8 rule arrives once. */
    private static String path(Arguments arguments, int index) {
        return arguments.checkText(index);
    }

    /**
     * Bare below, marked here. A name cannot hold a slash, since a segment
     * never does, so the mark is unambiguous.
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
        for (String segment : Paths.segmentsOf(path)) {
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
