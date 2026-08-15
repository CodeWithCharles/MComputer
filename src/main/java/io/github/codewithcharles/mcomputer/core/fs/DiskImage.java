package io.github.codewithcharles.mcomputer.core.fs;

import io.github.codewithcharles.mcomputer.core.component.ComponentException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The contents of one disk: directories and files under a single root,
 * addressed by absolute path.
 *
 * <p>Handles live in the component API above. This object is what follows a
 * disk from one computer to another; an open handle is not.
 *
 * <p><b>A path that would leave the root is refused, never clamped.</b>
 * Clamping lets two distinct paths name one file with nothing saying so.
 *
 * <p>Nothing in Java builds a path, so a malformed one is a script error and
 * its type is {@link ComponentException}, thrown here rather than translated
 * above.
 *
 * <p>Not synchronised. Every call arrives on the server thread through the
 * CallQueue.
 */
public final class DiskImage {

    /**
     * One structure rather than a map of files beside a set of directory
     * paths, so an empty directory is representable and nothing can disagree
     * with anything.
     */
    private sealed interface Node permits FileNode, DirectoryNode {
    }

    /**
     * Content lands here when writing exists. Today the type alone carries the
     * fact that a path component cannot be descended into.
     */
    private static final class FileNode implements Node {
    }

    private record DirectoryNode(Map<String, Node> children) implements Node {
    }

    private final long capacity;
    private final long entryCost;

    private final DirectoryNode root = new DirectoryNode(new LinkedHashMap<>());

    /**
     * @param capacity  how many bytes this disk holds, entry costs included
     * @param entryCost what one file or directory costs before its content.
     *                  Without it a million empty files are free.
     */
    public DiskImage(long capacity, long entryCost) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        if (entryCost < 0) {
            throw new IllegalArgumentException("entryCost must be >= 0, got " + entryCost);
        }
        this.capacity = capacity;
        this.entryCost = entryCost;
    }

    public long spaceTotal() {
        return capacity;
    }

    /** Entry costs plus content, over every entry but the root. */
    public long spaceUsed() {
        return usedUnder(root);
    }

    public boolean exists(String path) {
        return nodeAt(path) != null;
    }

    /** {@code false} when the path names a file, and when it names nothing. */
    public boolean isDirectory(String path) {
        return nodeAt(path) instanceof DirectoryNode;
    }

    /**
     * Creates one directory. Its parent must already exist: creating a whole
     * branch belongs to the component API above.
     *
     * @return {@code false} when the path already exists, or when its parent
     *         is missing or is a file
     * @throws ComponentException when the disk has no room left
     */
    public boolean makeDirectory(String path) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Splits a path into its segments, dropping what names nothing and
     * resolving {@code ..} against what precedes it. The only place a path is
     * read, so these rules are written once.
     *
     * <p>Purely lexical: {@code /a/..} is the root whether {@code /a} exists
     * or not.
     *
     * @throws ComponentException when the path is not absolute, or when a
     *                            {@code ..} would leave the root
     */
    private static List<String> segementsOf(String path) {
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

    /** {@code null} when nothing sits there, which is an answer and not a fault. */
    private Node nodeAt(String path) {
        Node node = root;
        for (String segment : segementsOf(path)) {
            if (!(node instanceof DirectoryNode directory)) {
                return null;
            }
            node = directory.children().get(segment);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /** Walks the children, so the root falls out uncharged with no subtraction. */
    private long usedUnder(DirectoryNode directory) {
        long used = 0;
        for (Node child : directory.children().values()) {
            used += entryCost;
            if (child instanceof DirectoryNode subDirectory) {
                used += usedUnder(subDirectory);
            }
        }
        return used;
    }
}