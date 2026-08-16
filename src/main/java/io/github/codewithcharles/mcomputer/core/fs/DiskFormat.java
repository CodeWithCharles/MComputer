package io.github.codewithcharles.mcomputer.core.fs;

import io.github.codewithcharles.mcomputer.core.component.ComponentException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The byte layout of a disk, and nothing else.
 *
 * <p>Depth first, a parent always before its children, one record each: a kind
 * byte, the path length and the path in UTF-8, and for a file its content
 * length and its content. Every number is a big-endian int.
 *
 * <p><b>It reads into a flat list and not into a tree.</b> The tree stays
 * {@link DiskImage}'s, and a snapshot that does not parse is refused before
 * anything is replaced.
 *
 * <p>There is no version byte. A disk written today is read by this same code
 * at milestone 6, from a file instead of from a block entity's NBT. The day the
 * layout changes, a version is what the first byte has to become, and the kind
 * byte of the first record is what it would collide with.
 */
final class DiskFormat {

    private static final byte DIRECTORY = 0;
    private static final byte FILE = 1;

    private DiskFormat() {
    }

    /** One entry, with its whole path. The content is empty for a directory. */
    record Entry(boolean directory, String path, byte[] content) {
    }

    static byte[] write(DirectoryNode root) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInto(out, root, "");
        return out.toByteArray();
    }

    /** @throws ComponentException when the bytes are not a snapshot */
    static List<Entry> read(byte[] snapshot) {
        List<Entry> entries = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(snapshot);
        while (buffer.hasRemaining()) {
            byte kind = buffer.get();
            // Before the path length is read, or a stray byte is taken for a
            // length and asks for an array of two billion.
            if (kind != DIRECTORY && kind != FILE) {
                throw new ComponentException("not a snapshot: unknown kind " + kind);
            }
            String path = new String(take(buffer, takeInt(buffer)), StandardCharsets.UTF_8);
            byte[] content = kind == FILE ? take(buffer, takeInt(buffer)) : new byte[0];
            entries.add(new Entry(kind == DIRECTORY, path, content));
        }
        return entries;
    }

    /** Depth first, a parent written before the children it contains. */
    private static void writeInto(
            ByteArrayOutputStream out, DirectoryNode directory, String prefix)
    {
        for (Map.Entry<String, Node> entry : directory.children().entrySet()) {
            String path = prefix + "/" + entry.getKey();
            byte[] encoded = path.getBytes(StandardCharsets.UTF_8);
            Node node = entry.getValue();
            out.write(node instanceof DirectoryNode ? DIRECTORY : FILE);
            writeInt(out, encoded.length);
            out.writeBytes(encoded);
            if (node instanceof FileNode file) {
                writeInt(out, file.length());
                out.writeBytes(file.read(0, file.length()));
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
