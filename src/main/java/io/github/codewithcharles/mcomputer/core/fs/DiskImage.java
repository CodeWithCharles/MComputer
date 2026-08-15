package io.github.codewithcharles.mcomputer.core.fs;

import io.github.codewithcharles.mcomputer.core.component.ComponentException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

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
     * A buffer and the length in use, so an append does not recopy the file at
     * every write. What is charged is the length and never the buffer, or
     * spaceUsed drifts from its own contract at the first growth.
     */
    private static final class FileNode implements Node {
        private byte[] bytes = new byte[0];
        private int length;
    }

    private record DirectoryNode(Map<String, Node> children) implements Node {
    }

    private static final byte DIRECTORY = 0;
    private static final byte FILE = 1;

    private final long capacity;
    private final long entryCost;

    /**
     * Entry costs plus content, over every entry but the root. Maintained at
     * the mutation sites rather than walked: every write asks the question, and
     * a walk answers it in O(n) each time.
     */
    private long used;

    private long revision;

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
        if (entryCost <= 0) {
            throw new IllegalArgumentException("entryCost must be > 0, got " + entryCost);
        }
        this.capacity = capacity;
        this.entryCost = entryCost;
    }

    public long spaceTotal() {
        return capacity;
    }

    public long spaceUsed() {
        return used;
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
        return create(path, () -> new DirectoryNode(new LinkedHashMap<>()));
    }

    /**
     * Creates one empty file. Its parent must already exist, like
     * {@link #makeDirectory}.
     *
     * @return {@code false} when the path already exists, or when its parent
     *         is missing or is a file
     * @throws ComponentException when the disk has no room left
     */
    public boolean createFile(String path) {
        return create(path, FileNode::new);
    }

    /**
     * @return the number of bytes the file holds
     * @throws ComponentException when the path names no file
     */
    public long size(String path) {
        return fileAt(path).length;
    }

    /**
     * Reads at most {@code count} bytes from {@code offset}.
     *
     * @return what is there, so shorter than {@code count} near the end of the
     *         file and empty past it. Never {@code null}: turning that into
     *         Lua's nil belongs to the layer that talks to a script.
     * @throws ComponentException when the path names no file, or when either
     *                            number is negative
     */
    public byte[] read(String path, int offset, int count) {
        FileNode file = fileAt(path);
        requireNotNegative(offset, "offset");
        requireNotNegative(count, "count");
        int from = Math.min(offset, file.length);
        // Not from + count: a count near Integer.MAX_VALUE overflows that sum
        // into a negative bound.
        int taken = Math.min(count, file.length - from);
        return Arrays.copyOfRange(file.bytes, from, from + taken);
    }

    /**
     * Writes at {@code offset}, growing the file when the write runs past the
     * end and filling any gap with zero bytes, which are charged like any
     * other. All or nothing: nothing is written when the result would not fit.
     *
     * @throws ComponentException when the path names no file, when the offset
     *                            is negative, or when the disk has no room left
     */
    public void write(String path, int offset, byte[] data) {
        FileNode file = fileAt(path);
        requireNotNegative(offset, "offset");
        // In long: offset comes from a script, and offset + data.length
        // overflows an int into a negative end that passes every check below.
        long end = (long) offset + data.length;
        long growth = Math.max(0L, end - file.length);
        if (used + growth > capacity) {
            throw new ComponentException("not enough space");
        }
        int grown = (int) Math.max(file.length, end);
        if (grown > file.bytes.length) {
            // Doubling, so an append is amortised.
            file.bytes = Arrays.copyOf(file.bytes, Math.max(grown, file.bytes.length * 2));
        }
        System.arraycopy(data, 0, file.bytes, offset, data.length);
        used += growth;
        file.length = grown;
        revision++;
    }

    /**
     * Empties a file and refunds its content. The buffer goes with it, so
     * nothing survives above the new length to be read back by a later write
     * past the end.
     *
     * @throws ComponentException when the path names no file
     */
    public void truncate(String path) {
        FileNode file = fileAt(path);
        used -= file.length;
        file.bytes = new byte[0];
        file.length = 0;
        revision++;
    }

    /**
     * The names in a directory, in the order they were created. Bare: marking
     * the directories among them belongs to the component API, like every other
     * rendering.
     *
     * @throws ComponentException when the path names no directory
     */
    public List<String> list(String path) {
        return List.copyOf(directoryAt(path).children().keySet());
    }

    /**
     * Removes one file or one empty directory, and refunds it. A directory with
     * children is refused: removing a branch belongs to the component API.
     *
     * @return {@code false} when the path names nothing, names the root, or
     *         names a directory that is not empty
     */
    public boolean remove(String path) {
        Slot slot = slotOf(path);
        if (slot == null) {
            return false;
        }
        Node node = slot.parent().children().get(slot.name());
        if (node == null) {
            return false;
        }
        if (node instanceof DirectoryNode directory && !directory.children().isEmpty()) {
            return false;
        }
        slot.parent().children().remove(slot.name());
        used -= entryCost;
        if (node instanceof FileNode file) {
            used -= file.length;
        }
        revision++;
        return true;
    }

    /**
     * Moves an entry, with everything under it. Costs nothing: an entry that
     * moves is charged the same either way.
     *
     * @return {@code false} when the source names nothing, when the target
     *         already exists, when the target's parent is missing, or when the
     *         target sits under the source
     */
    public boolean rename(String from, String to) {
        if (leadsInto(from, to)) {
            return false;
        }
        Slot source = slotOf(from);
        Slot target = slotOf(to);
        if (source == null || target == null) {
            return false;
        }
        Node node = source.parent().children().get(source.name());
        if (node == null || target.parent().children().containsKey(target.name())) {
            return false;
        }
        source.parent().children().remove(source.name());
        target.parent().children().put(target.name(), node);
        revision++;
        return true;
    }

    /**
     * The whole disk as bytes, so the adapter has one array to carry.
     *
     * <p>Depth first, a parent always before its children, one record each:
     * a kind byte, the path length and the path in UTF-8, and for a file its
     * content length and its content. Every number is a big-endian int.
     */
    public byte[] snapshot() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInto(out, root, "");
        return out.toByteArray();
    }

    /**
     * Replaces everything with what {@link #snapshot} produced.
     *
     * <p>The capacity is not enforced here: a disk saved when it was larger
     * keeps its files rather than losing them at a world load.
     *
     * @throws ComponentException when the bytes are not a snapshot
     */
    public void restore(byte[] snapshot) {
        root.children().clear();
        used = 0;
        ByteBuffer buffer = ByteBuffer.wrap(snapshot);
        while (buffer.hasRemaining()) {
            byte kind = buffer.get();
            // Before the path length is read, or a stray byte is taken for a
            // length and asks for an array of two billion.
            if (kind != DIRECTORY && kind != FILE) {
                throw new ComponentException("not a snapshot: unknown kind " + kind);
            }
            String path = new String(take(buffer, takeInt(buffer)), StandardCharsets.UTF_8);
            Slot slot = slotOf(path);
            if (slot == null) {
                throw new ComponentException("not a snapshot: '" + path + "' has no place");
            }
            Node node;
            if (kind == DIRECTORY) {
                node = new DirectoryNode(new LinkedHashMap<>());
            } else {
                FileNode file = new FileNode();
                file.bytes = take(buffer, takeInt(buffer));
                file.length = file.bytes.length;
                used += file.length;
                node = file;
            }
            slot.parent().children().put(slot.name(), node);
            used += entryCost;
        }
        revision++;
    }

    /**
     * Rises on every change to the tree or to a file's content, so a caller can
     * tell whether anything happened since it last looked. It never falls and
     * its value means nothing else.
     */
    public long revision() {
        return revision;
    }

    /** Where an entry sits, and under what name. */
    private record Slot(DirectoryNode parent, String name) {
    }

    /** {@code null} for the root, and when the parent is missing or is a file. */
    private Slot slotOf(String path) {
        List<String> segments = segmentsOf(path);
        if (segments.isEmpty()) {
            return null;
        }
        String name = segments.removeLast();
        if (!(nodeAt(segments) instanceof DirectoryNode parent)) {
            return null;
        }
        return new Slot(parent, name);
    }

    /**
     * Whether {@code to} sits under {@code from}. Renaming a directory into its
     * own descendant detaches the branch below it and loses everything, in
     * silence.
     */
    private static boolean leadsInto(String from, String to) {
        List<String> ancestor = segmentsOf(from);
        List<String> descendant = segmentsOf(to);
        return descendant.size() > ancestor.size()
                && descendant.subList(0, ancestor.size()).equals(ancestor);
    }

    /**
     * The one precondition size, read and write share. A duplicated throw is
     * where the tenth copy forgets a case.
     */
    private FileNode fileAt(String path) {
        if (nodeAt(path) instanceof FileNode file) {
            return file;
        }
        throw new ComponentException("no such file: '" + path + "'");
    }

    /** The counterpart of {@link #fileAt}, for the methods that need a directory. */
    private DirectoryNode directoryAt(String path) {
        if (nodeAt(path) instanceof DirectoryNode directory) {
            return directory;
        }
        throw new ComponentException("no such directory: '" + path + "'");
    }

    private static void requireNotNegative(int value, String name) {
        if (value < 0) {
            throw new ComponentException(name + " must not be negative, got " + value);
        }
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
     * What creating a directory and creating a file have in common: the order
     * of the checks, and the one that throws.
     *
     * @param newNode called only once the entry is known to be creatable, so a
     *                refused call allocates nothing
     */
    private boolean create(String path, Supplier<Node> newNode) {
        Slot slot = slotOf(path);
        if (slot == null) {
            return false;
        }
        // Before the room is counted, so a repeated call on a full disk
        // answers false where a first one would throw.
        if (slot.parent().children().containsKey(slot.name())) {
            return false;
        }
        if (used + entryCost > capacity) {
            throw new ComponentException("not enough space");
        }
        slot.parent().children().put(slot.name(), newNode.get());
        used += entryCost;
        revision++;
        return true;
    }

    /** {@code null} when nothing sits there, which is an answer and not a fault. */
    private Node nodeAt(String path) {
        return nodeAt(segmentsOf(path));
    }

    private Node nodeAt(List<String> segments) {
        Node node = root;
        for (String segment : segments) {
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

    /** Depth first, a parent written before the children it contains. */
    private void writeInto(ByteArrayOutputStream out, DirectoryNode directory, String prefix) {
        for (Map.Entry<String, Node> entry : directory.children().entrySet()) {
            String path = prefix + "/" + entry.getKey();
            byte[] encoded = path.getBytes(StandardCharsets.UTF_8);
            Node node = entry.getValue();
            out.write(node instanceof DirectoryNode ? DIRECTORY : FILE);
            writeInt(out, encoded.length);
            out.writeBytes(encoded);
            if (node instanceof FileNode file) {
                writeInt(out, file.length);
                out.write(file.bytes, 0, file.length);
            } else {
                writeInto(out, (DirectoryNode) node, path);
            }
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    /**
     * Both readers check what is left first, so a truncated or hostile snapshot
     * is a refusal rather than an allocation of whatever the bytes happened to
     * say.
     */
    private static int takeInt(ByteBuffer buffer) {
        if (buffer.remaining() < Integer.BYTES) {
            throw new ComponentException("not a snapshot: it ends mid-record");
        }
        return buffer.getInt();
    }

    private static byte[] take(ByteBuffer buffer, int count) {
        if (count < 0 || count > buffer.remaining()) {
            throw new ComponentException("not a snapshot: it ends mid-record");
        }
        byte[] taken = new byte[count];
        buffer.get(taken);
        return taken;
    }
}