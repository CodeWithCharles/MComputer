package io.github.codewithcharles.mcomputer.core.fs;

import io.github.codewithcharles.mcomputer.core.component.Arguments;
import io.github.codewithcharles.mcomputer.core.component.ComponentApi;
import io.github.codewithcharles.mcomputer.core.component.ComponentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class FilesystemTest {

    private static final long ENTRY_COST = 512;
    private static final int MAX_HANDLES = 16;

    private final DiskImage _image = new DiskImage(4096, ENTRY_COST);
    private final ComponentApi _fs = Filesystem.api(_image, MAX_HANDLES);

    /** A copy of GpuTest's. */
    private Object[] invoke(String method, Object... arguments) {
        return _fs.method(method).orElseThrow().invoke(new Arguments(arguments, method));
    }

    /** A copy of ScreenBufferTest's, so that suite stays out of this diff. */
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(Object listed) {
        List<String> out = new ArrayList<>();
        for (byte[] name : (List<byte[]>) listed) {
            out.add(new String(name, StandardCharsets.US_ASCII));
        }
        return out;
    }

    @Test
    void theTypeIsFilesystem() {
        assertEquals("filesystem", _fs.type());
    }

    @Test
    void existsAnswersAboutBothKindsAndAboutNothing() {
        _image.makeDirectory("/a");
        _image.createFile("/f");

        assertEquals(true, invoke("exists", bytes("/a"))[0]);
        assertEquals(true, invoke("exists", bytes("/f"))[0]);
        assertEquals(false, invoke("exists", bytes("/nowhere"))[0]);
    }

    @Test
    void isDirectoryTellsTheKindsApart() {
        _image.makeDirectory("/a");
        _image.createFile("/f");

        assertEquals(true, invoke("isDirectory", bytes("/a"))[0]);
        assertEquals(false, invoke("isDirectory", bytes("/f"))[0]);
    }

    /** A number crosses as a Double, never as a long. */
    @Test
    void sizeCrossesAsADouble() {
        _image.createFile("/f");
        _image.write("/f", 0, bytes("abc"));

        assertEquals(3.0, invoke("size", bytes("/f"))[0]);
    }

    /**
     * The trailing slash is written here and nowhere below. The store answers
     * bare names, this is the layer that renders.
     */
    @Test
    void listMarksTheDirectoriesAmongTheNames() {
        _image.makeDirectory("/a");
        _image.createFile("/b.lua");

        assertEquals(List.of("a/", "b.lua"), names(invoke("list", bytes("/"))[0]));
    }

    @Test
    void theSpaceIsReportedInDoubles() {
        _image.createFile("/f");

        assertEquals(4096.0, invoke("spaceTotal")[0]);
        assertEquals((double) ENTRY_COST, invoke("spaceUsed")[0]);
    }

    /**
     * The store's refusals are the script's fault and travel untouched. Nothing
     * is caught here, or a ComponentException would become a bug of ours on the
     * way to luaj.
     */
    @Test
    void aRefusalFromTheStoreCrossesUntouched() {
        _image.makeDirectory("/a");

        assertThrows(ComponentException.class, () -> invoke("size", bytes("/a")));
    }

    /** The convenience the store refuses: one call, the whole branch. */
    @Test
    void makeDirectoryBuildsTheWholeBranch() {
        assertEquals(true, invoke("makeDirectory", bytes("/a/b/c"))[0]);

        assertTrue(_image.isDirectory("/a"));
        assertTrue(_image.isDirectory("/a/b"));
        assertTrue(_image.isDirectory("/a/b/c"));
    }

    /** Only the last segment has to be new. */
    @Test
    void makeDirectoryIsFalseOnlyWhenTheLeafIsAlreadyThere() {
        assertEquals(true, invoke("makeDirectory", bytes("/a/b"))[0]);
        assertEquals(false, invoke("makeDirectory", bytes("/a/b"))[0]);
        assertEquals(true, invoke("makeDirectory", bytes("/a/b/c"))[0]);
    }

    /** The second assertion says the branch was not half built on the way. */
    @Test
    void makeDirectoryStopsOnAFileInTheWay() {
        _image.createFile("/f");

        assertEquals(false, invoke("makeDirectory", bytes("/f/x/y"))[0]);
        assertFalse(_image.exists("/f/x"));
    }

    /** The other convenience: the store removes one entry, this removes a tree. */
    @Test
    void removeTakesAWholeBranchAndRefundsIt() {
        _image.makeDirectory("/a");
        _image.makeDirectory("/a/b");
        _image.createFile("/a/b/x.lua");
        _image.write("/a/b/x.lua", 0, bytes("0123456789"));

        assertEquals(true, invoke("remove", bytes("/a"))[0]);
        assertFalse(_image.exists("/a"));
        assertEquals(0, _image.spaceUsed());
    }

    /**
     * The store refuses the root and this layer has to say what emptying it
     * means. Born red: removeBranch used to hand the store's no straight back,
     * so rm / wiped the disk and reported that it had not.
     */
    @Test
    void removingTheRootEmptiesItAndSaysSo() {
        _image.makeDirectory("/a");
        _image.createFile("/a/x.lua");
        _image.createFile("/f");

        assertEquals(true, invoke("remove", bytes("/"))[0]);
        assertTrue(names(invoke("list", bytes("/"))[0]).isEmpty());
        assertEquals(0, _image.spaceUsed());
    }

    @Test
    void removeIsFalseOnWhatIsNotThere() {
        assertEquals(false, invoke("remove", bytes("/nowhere"))[0]);
    }

    @Test
    void renameIsHandedStraightToTheStore() {
        _image.createFile("/f");

        assertEquals(true, invoke("rename", bytes("/f"), bytes("/g"))[0]);
        assertFalse(_image.exists("/f"));
        assertTrue(_image.exists("/g"));
    }

    @Test
    void openingGivesAHandleAsANumber() {
        _image.createFile("/f");

        assertInstanceOf(Double.class, invoke("open", bytes("/f"), bytes("r"))[0]);
    }

    @Test
    void openingForReadingRefusesAMissingFile() {
        assertThrows(ComponentException.class, () -> invoke("open", bytes("/nope"), bytes("r")));
    }

    @Test
    void openingForWritingCreatesAndEmpties() {
        _image.createFile("/f");
        _image.write("/f", 0, bytes("abc"));

        invoke("open", bytes("/f"), bytes("w"));

        assertEquals(0, _image.size("/f"));
    }

    @Test
    void openingForAppendingCreatesWhatIsMissing() {
        invoke("open", bytes("/g"), bytes("a"));

        assertTrue(_image.exists("/g"));
    }

    /** A reused number is a stale handle silently addressing another file. */
    @Test
    void aHandleIsNeverReused() {
        _image.createFile("/f");
        Object first = invoke("open", bytes("/f"), bytes("r"))[0];
        invoke("close", first);

        assertNotEquals(first, invoke("open", bytes("/f"), bytes("r"))[0]);
    }

    /** Without this test, maxHandles is a number nothing reads. */
    @Test
    void tooManyOpenHandlesIsRefused() {
        ComponentApi small = Filesystem.api(_image, 1);
        _image.createFile("/f");
        small.method("open").orElseThrow()
                .invoke(new Arguments(new Object[] { bytes("/f"), bytes("r") }, "open"));

        assertThrows(ComponentException.class, () -> small.method("open").orElseThrow()
                .invoke(new Arguments(new Object[] { bytes("/f"), bytes("r") }, "open")));
    }

    @Test
    void closingAnUnknownHandleIsRefused() {
        assertThrows(ComponentException.class, () -> invoke("close", 999.0));
    }

    @Test
    void closingFreesARoom() {
        ComponentApi small = Filesystem.api(_image, 1);
        _image.createFile("/f");
        Object handle = small.method("open").orElseThrow()
                .invoke(new Arguments(new Object[] { bytes("/f"), bytes("r") }, "open"))[0];
        small.method("close").orElseThrow().invoke(new Arguments(new Object[] { handle }, "close"));

        assertDoesNotThrow(() -> small.method("open").orElseThrow()
                .invoke(new Arguments(new Object[] { bytes("/f"), bytes("r") }, "open")));
    }

    private double open(String path, String mode) {
        return (double) invoke("open", bytes(path), bytes(mode))[0];
    }

    @Test
    void readingAdvancesTheHandle() {
        _image.createFile("/f");
        _image.write("/f", 0, bytes("abcde"));
        double handle = open("/f", "r");

        assertArrayEquals(bytes("ab"), (byte[]) invoke("read", handle, 2.0)[0]);
        assertArrayEquals(bytes("cd"), (byte[]) invoke("read", handle, 2.0)[0]);
    }

    /** Nil rather than an empty string, so a shell can loop until it stops. */
    @Test
    void readingAtTheEndGivesNil() {
        _image.createFile("/f");
        double handle = open("/f", "r");

        assertNull(invoke("read", handle, 10.0)[0]);
    }

    @Test
    void writingAdvancesAndLands() {
        double handle = open("/f", "w");

        invoke("write", handle, bytes("abc"));
        invoke("write", handle, bytes("de"));

        assertArrayEquals(bytes("abcde"), _image.read("/f", 0, 5));
    }

    /** The one reader of OpenFile.writable. */
    @Test
    void writingThroughAReadHandleIsRefused() {
        _image.createFile("/f");
        double handle = open("/f", "r");

        assertThrows(ComponentException.class, () -> invoke("write", handle, bytes("a")));
    }

    @Test
    void seekTakesTheThreeWhences() {
        _image.createFile("/f");
        _image.write("/f", 0, bytes("abcde"));
        double handle = open("/f", "r");

        assertEquals(1.0, invoke("seek", handle, bytes("set"), 1.0)[0]);
        assertArrayEquals(bytes("b"), (byte[]) invoke("read", handle, 1.0)[0]);
        assertEquals(3.0, invoke("seek", handle, bytes("cur"), 1.0)[0]);
        assertEquals(5.0, invoke("seek", handle, bytes("end"), 0.0)[0]);
    }

    @Test
    void anUnknownWhenceIsRefused() {
        _image.createFile("/f");
        double handle = open("/f", "r");

        assertThrows(ComponentException.class, () -> invoke("seek", handle, bytes("middle"), 0.0));
    }

    @Test
    void seekingBeforeTheStartIsRefused() {
        _image.createFile("/f");
        double handle = open("/f", "r");

        assertThrows(ComponentException.class, () -> invoke("seek", handle, bytes("set"), -1.0));
    }

    @Test
    void theThreeRefuseAnUnknownHandle() {
        assertThrows(ComponentException.class, () -> invoke("read", 999.0, 1.0));
        assertThrows(ComponentException.class, () -> invoke("write", 999.0, bytes("a")));
        assertThrows(ComponentException.class, () -> invoke("seek", 999.0, bytes("set"), 0.0));
    }

    private static String text(Object value) {
        return new String((byte[]) value, StandardCharsets.US_ASCII);
    }

    /**
     * Eight spellings, four places. The last two are the ones a shell meets:
     * the root has to come back as "/" and not as the empty string, and going
     * up from the first level has to land on it.
     */
    @ParameterizedTest
    @CsvSource({
            "/a/b,       /a/b",
            "/a/b/,      /a/b",
            "//a///b,    /a/b",
            "/a/./b,     /a/b",
            "/a/b/..,    /a",
            "/a/b/../c,  /a/c",
            "/,          /",
            "/a/..,      /" })
    void canonicalGivesOneSpellingPerPlace(String path, String expected) {
        assertEquals(expected, text(invoke("canonical", bytes(path))[0]));
    }

    /** The store's two refusals, reached without touching the disk. */
    @Test
    void canonicalRefusesWhatTheStoreRefuses() {
        assertThrows(ComponentException.class, () -> invoke("canonical", bytes("a/b")));
        assertThrows(ComponentException.class, () -> invoke("canonical", bytes("/..")));
    }

    /**
     * It answers about a path and not about a disk, so nothing here is created
     * and the answer is the same either way.
     */
    @Test
    void canonicalDoesNotAskWhetherThePlaceExists() {
        assertEquals("/nowhere/at/all",
                text(invoke("canonical", bytes("/nowhere/./at/all"))[0]));
    }
}