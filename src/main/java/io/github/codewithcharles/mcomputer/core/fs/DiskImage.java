package io.github.codewithcharles.mcomputer.core.fs;

import io.github.codewithcharles.mcomputer.core.component.ComponentException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * The contents of one disk: directories and files under a single root,
 * addressed by absolute path.
 *
 * <p>Handles live in the component API above. This object is what follows a
 * disk from one computer to another; an open handle is not.
 *
 * <p>What it owns is the tree and the accounting. The lexical rules about a
 * path are {@link Paths}', the bytes of a file are {@link FileNode}'s, and the
 * byte layout of a snapshot is {@link DiskFormat}'s.
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

    private final long capacity;
    private final long entryCost;

    /**
     * Entry costs plus content, over every entry but the root. Carried at the
     * mutation sites rather than walked: every write would otherwise ask the
     * question in O(n). {@link #recomputeUsed} is what keeps those sites
     * honest.
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
        return fileAt(path).length();
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
        return file.read(offset, count);
    }

    /**
     * Writes at {@code offset}, growing the file when the write runs past the
     * end and filling any gap with zero bytes, which are charged like any
     * other. All or nothing: the room is found before the file is touched.
     *
     * @throws ComponentException when the path names no file, when the offset
     *                            is negative, or when the disk has no room left
     */
    public void write(String path, int offset, byte[] data) {
        FileNode file = fileAt(path);
        requireNotNegative(offset, "offset");
        long growth = file.growthFor(offset, data.length);
        if (used + growth > capacity) {
            throw new ComponentException("not enough space");
        }
        file.write(offset, data);
        used += growth;
        revision++;
    }

    /**
     * Empties a file and refunds its content.
     *
     * @throws ComponentException when the path names no file
     */
    public void truncate(String path) {
        used -= fileAt(path).truncate();
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
            used -= file.length();
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
        if (Paths.leadsInto(from, to)) {
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

    /** The whole disk as bytes, so the adapter has one array to carry. */
    public byte[] snapshot() {
        return DiskFormat.write(root);
    }

    /**
     * Replaces everything with what {@link #snapshot} produced.
     *
     * <p>All or nothing: the tree is rebuilt beside the live one and swapped in
     * at the end, so a snapshot that does not read leaves the disk as it was.
     * Same rule as a write that does not fit.
     *
     * <p>The capacity is not enforced: a disk saved when it was larger keeps its
     * files rather than losing them at a world load.
     *
     * @throws ComponentException when the bytes are not a snapshot
     */
    public void restore(byte[] snapshot) {
        DirectoryNode staged = new DirectoryNode(new LinkedHashMap<>());
        long stagedUsed = 0;
        for (DiskFormat.Entry entry : DiskFormat.read(snapshot)) {
            Slot slot = slotOf(staged, entry.path());
            if (slot == null) {
                throw new ComponentException(
                        "not a snapshot: '" + entry.path() + "' has no place");
            }
            Node node;
            if (entry.directory()) {
                node = new DirectoryNode(new LinkedHashMap<>());
            } else {
                FileNode file = new FileNode();
                file.write(0, entry.content());
                stagedUsed += entry.content().length;
                node = file;
            }
            slot.parent().children().put(slot.name(), node);
            stagedUsed += entryCost;
        }
        root.children().clear();
        root.children().putAll(staged.children());
        used = stagedUsed;
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

    /**
     * What {@link #spaceUsed} would be if it were counted instead of carried.
     *
     * <p>Exists for one test, and the two are independent on purpose: the
     * counter is derived from the history of the mutations, this from the tree
     * as it stands. A refund forgotten at one of the four sites makes them
     * disagree, and nothing else in the class would notice.
     */
    long recomputeUsed() {
        return usedUnder(root);
    }

    private long usedUnder(DirectoryNode directory) {
        long total = 0;
        for (Node node : directory.children().values()) {
            total += entryCost;
            if (node instanceof FileNode file) {
                total += file.length();
            } else {
                total += usedUnder((DirectoryNode) node);
            }
        }
        return total;
    }

    /** Where an entry sits, and under what name. */
    private record Slot(DirectoryNode parent, String name) {
    }

    private Slot slotOf(String path) {
        return slotOf(root, path);
    }

    /** {@code null} for the root, and when the parent is missing or is a file. */
    private static Slot slotOf(DirectoryNode from, String path) {
        List<String> segments = Paths.segmentsOf(path);
        if (segments.isEmpty()) {
            return null;
        }
        String name = segments.removeLast();
        if (!(nodeAt(from, segments) instanceof DirectoryNode parent)) {
            return null;
        }
        return new Slot(parent, name);
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
        return nodeAt(root, Paths.segmentsOf(path));
    }

    private static Node nodeAt(DirectoryNode from, List<String> segments) {
        Node node = from;
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
}
