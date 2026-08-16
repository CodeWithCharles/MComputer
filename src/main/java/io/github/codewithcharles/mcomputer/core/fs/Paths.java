package io.github.codewithcharles.mcomputer.core.fs;

import io.github.codewithcharles.mcomputer.core.component.ComponentException;

import java.util.ArrayList;
import java.util.List;

/**
 * What can be decided about a path without looking at a disk.
 *
 * <p>It shadows {@code java.nio.file.Paths} inside this package, which is
 * welcome rather than awkward: nothing in {@code core} has any business with a
 * real filesystem.
 *
 * <p>Nothing in Java builds a path, so a malformed one is a script error and
 * its type is {@link ComponentException}.
 */
final class Paths {

    private Paths() {
    }

    /**
     * Splits a path into its segments, dropping what names nothing and
     * resolving {@code ..} against what precedes it. The only place a path is
     * read, so these rules are written once.
     *
     * <p>Purely lexical: {@code /a/..} is the root whether {@code /a} exists or
     * not.
     *
     * @return a fresh list the caller may consume; the resolver removes its
     *         last segment to reach the parent
     * @throws ComponentException when the path is not absolute, or when a
     *                            {@code ..} would leave the root
     */
    static List<String> segmentsOf(String path) {
        if (path.isEmpty() || path.charAt(0) != '/') {
            throw new ComponentException("path is not absolute: '" + path + "'");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            } else if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    throw new ComponentException("path leaves the root: '" + path + "'");
                }
                segments.removeLast();
            } else {
                segments.add(segment);
            }
        }
        return segments;
    }

    /**
     * Whether {@code to} sits under {@code from}. Renaming a directory into its
     * own descendant unhooks the branch and loses everything, in silence.
     *
     * <p>Lexical, and it has to be: {@code DirectoryNode} is a record, so two
     * distinct empty directories are equal and a test written with
     * {@code equals} would answer about the wrong thing.
     */
    static boolean leadsInto(String from, String to) {
        List<String> ancestor = segmentsOf(from);
        List<String> descendant = segmentsOf(to);
        return descendant.size() > ancestor.size()
                && descendant.subList(0, ancestor.size()).equals(ancestor);
    }
}
