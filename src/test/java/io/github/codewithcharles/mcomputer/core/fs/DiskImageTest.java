package io.github.codewithcharles.mcomputer.core.fs;

import io.github.codewithcharles.mcomputer.core.component.ComponentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class DiskImageTest {

    private static final long ENTRY_COST = 512;

    private static final long CAPACITY = 4096;

    private final DiskImage _disk = new DiskImage(CAPACITY, ENTRY_COST);

    /** A copy of ScreenBufferTest's, so that suite stays out of this diff. */
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Born green, the constructor being the one implemented body. Its red is
     * earned by deleting the capacity guard for ten seconds: it falls alone
     * while aNegativeEntryCostIsRejected stays green.
     */
    @Test
    void aCapacityOfZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DiskImage(0, ENTRY_COST));
    }

    /** Born green, and the same manoeuvre on the other guard. */
    @Test
    void anEntryCostOfZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DiskImage(CAPACITY, 0));
    }

    @Test
    void spaceTotalIsTheConstructorsCapacity() {
        assertEquals(CAPACITY, _disk.spaceTotal());
    }

    /**
     * The root is not charged, so a fresh disk uses zero and not ENTRY_COST.
     * The suite's decision test on the accounting.
     */
    @Test
    void anEmptyDiskUsesNothing() {
        assertEquals(0, _disk.spaceUsed());
    }

    @Test
    void theRootExistsAndIsADirectory() {
        assertTrue(_disk.exists("/"));
        assertTrue(_disk.isDirectory("/"));
    }

    /**
     * A path naming nothing is an answer, not an error. Only a malformed one
     * throws, and the line between the two is this test read against
     * aPathLeavingTheRootIsRejected.
     */
    @Test
    void aMissingPathExistsNotAndIsNoDirectory() {
        assertFalse(_disk.exists("/nowhere"));
        assertFalse(_disk.isDirectory("/nowhere"));
    }

    /**
     * Normalisation is lexical: /a/.. is the root although /a does not exist.
     * Without it, // looks up a segment with an empty name and answers false.
     */
    @ParameterizedTest
    @ValueSource(strings = { "//", "///", "/.", "/./", "/a/.." })
    void aPathIsNormalisedBeforeItIsResolved(String path) {
        assertTrue(_disk.exists(path));
    }

    /**
     * The suite's decision test on the path rule. Clamping to the root would
     * turn every one of these into a plain true, and two distinct paths would
     * name one file with nothing saying so.
     *
     * <p>Note it is asserted on the exact type: ComponentException extends
     * RuntimeException, so an assertion on the supertype would go green
     * against the stub.
     */
    @ParameterizedTest
    @ValueSource(strings = { "/..", "/../", "/../nowhere", "/a/../.." })
    void aPathLeavingTheRootIsRejected(String path) {
        assertThrows(ComponentException.class, () -> _disk.exists(path));
    }

    /** Absolute only. Where a script stands is the shell's business, in Lua. */
    @ParameterizedTest
    @ValueSource(strings = { "a", "a/b", "./a", "" })
    void aRelativePathIsRejected(String path) {
        assertThrows(ComponentException.class, () -> _disk.exists(path));
    }

    @Test
    void makingADirectoryMakesItExist() {
        assertTrue(_disk.makeDirectory("/a"));

        assertTrue(_disk.exists("/a"));
        assertTrue(_disk.isDirectory("/a"));
    }

    /** The root is not a path one can create: it is already there. */
    @Test
    void makingTheRootIsRefused() {
        assertFalse(_disk.makeDirectory("/"));
    }

    /**
     * The second assertion is the one that discriminates: a refusal that had
     * nonetheless created something would pass the first.
     */
    @Test
    void makingADirectoryTwiceIsRefused() {
        _disk.makeDirectory("/a");

        assertFalse(_disk.makeDirectory("/a"));
        assertEquals(ENTRY_COST, _disk.spaceUsed());
    }

    /**
     * Pins where the branch is built. Creating the missing parent belongs to
     * the component API, so the second assertion is what fails the day this
     * class grows a convenience nobody asked it for.
     */
    @Test
    void aDirectoryWithNoParentIsRefused() {
        assertFalse(_disk.makeDirectory("/a/b"));
        assertFalse(_disk.exists("/a"));
    }

    @Test
    void aNestedDirectoryIsMadeUnderItsParent() {
        _disk.makeDirectory("/a");

        assertTrue(_disk.makeDirectory("/a/b"));
        assertTrue(_disk.isDirectory("/a/b"));
    }

    /** Every entry is charged, at whatever depth it sits. */
    @Test
    void everyDirectoryCostsItsEntryCost() {
        _disk.makeDirectory("/a");
        _disk.makeDirectory("/a/b");

        assertEquals(2 * ENTRY_COST, _disk.spaceUsed());
    }

    /**
     * The suite's decision test on the capacity. The first assertion is not
     * decoration: it is what says the last entry that fits exactly is
     * accepted, so a check written with the wrong comparison fails here rather
     * than shipping a disk one entry short.
     */
    @Test
    void aDirectoryThatDoesNotFitIsRefused() {
        DiskImage full = new DiskImage(ENTRY_COST, ENTRY_COST);

        assertTrue(full.makeDirectory("/a"));
        assertThrows(ComponentException.class, () -> full.makeDirectory("/b"));
    }

    /**
     * segmentsOf is meant to be the only door onto a path. A makeDirectory
     * that split the string itself passes every other test in this suite.
     */
    @Test
    void makeDirectoryReadsAPathByTheSameRule() {
        assertThrows(ComponentException.class, () -> _disk.makeDirectory("/.."));
    }

    /**
     * The first case in this suite where isDirectory answers false about
     * something that exists. Until a file could be made, it had only ever
     * answered false about nothing.
     */
    @Test
    void creatingAFileMakesItExistAndItIsNoDirectory() {
        assertTrue(_disk.createFile("/a.lua"));

        assertTrue(_disk.exists("/a.lua"));
        assertFalse(_disk.isDirectory("/a.lua"));
    }

    /**
     * The second assertion carries two things at once: one entry was charged,
     * and the refused call charged nothing.
     */
    @Test
    void creatingAFileTwiceIsRefused() {
        _disk.createFile("/a.lua");

        assertFalse(_disk.createFile("/a.lua"));
        assertEquals(ENTRY_COST, _disk.spaceUsed());
    }

    @Test
    void aFileWithNoParentIsRefused() {
        assertFalse(_disk.createFile("/a/b.lua"));
        assertFalse(_disk.exists("/a"));
    }

    /**
     * Both directions in one test, so an implementation comparing kinds rather
     * than names cannot go green on half of it.
     */
    @Test
    void aNameIsTakenWhateverItsKind() {
        _disk.makeDirectory("/d");
        _disk.createFile("/f");

        assertFalse(_disk.createFile("/d"));
        assertFalse(_disk.makeDirectory("/f"));
    }

    /**
     * Owed since wave 1b, which had no way to make a file. Each half guards a
     * different method's parent check: written as {@code parent == null} rather
     * than as an instanceof, both bodies pass every other test in this suite.
     */
    @Test
    void nothingIsCreatedUnderAFile() {
        _disk.createFile("/a.lua");

        assertFalse(_disk.makeDirectory("/a.lua/b"));
        assertFalse(_disk.createFile("/a.lua/b.lua"));
    }

    /**
     * The two kinds spend one purse. A createFile counting on its own would
     * accept this second entry, and the disk would hold twice what it says.
     */
    @Test
    void aFileThatDoesNotFitIsRefused() {
        DiskImage full = new DiskImage(ENTRY_COST, ENTRY_COST);
        assertTrue(full.makeDirectory("/d"));

        assertThrows(ComponentException.class, () -> full.createFile("/f"));
    }

    /** segmentsOf is meant to be the only door onto a path, for every method. */
    @Test
    void createFileReadsAPathByTheSameRule() {
        assertThrows(ComponentException.class, () -> _disk.createFile("/.."));
    }

    @Test
    void aFreshFileIsEmpty() {
        _disk.createFile("/a.lua");

        assertEquals(0, _disk.size("/a.lua"));
        assertEquals(0, _disk.read("/a.lua", 0, 10).length);
    }

    /**
     * One rule, two edges. A read written as a plain range copy blows up on the
     * first assertion and answers garbage on the second.
     */
    @Test
    void readingGivesWhatIsThereAndNoMore() {
        _disk.createFile("/a.lua");
        _disk.write("/a.lua", 0, bytes("abcde"));

        assertArrayEquals(bytes("abcde"), _disk.read("/a.lua", 0, 100));
        assertEquals(0, _disk.read("/a.lua", 5, 10).length);
    }

    /** The gap is real bytes and it is charged like any other. */
    @Test
    void writingPastTheEndFillsTheGapWithZeroes() {
        _disk.createFile("/a.lua");

        _disk.write("/a.lua", 3, bytes("ab"));

        assertEquals(5, _disk.size("/a.lua"));
        assertArrayEquals(new byte[] { 0, 0, 0, 'a', 'b' }, _disk.read("/a.lua", 0, 5));
    }

    /**
     * The third assertion is the one that discriminates: what is charged is the
     * growth, not the number of bytes handed over. A body spending
     * bytes.length passes the first two.
     */
    @Test
    void aWriteInsideTheFileGrowsNeitherItNorTheDisk() {
        _disk.createFile("/a.lua");
        _disk.write("/a.lua", 0, bytes("abcde"));
        long before = _disk.spaceUsed();

        _disk.write("/a.lua", 2, bytes("X"));

        assertEquals(5, _disk.size("/a.lua"));
        assertArrayEquals(bytes("abXde"), _disk.read("/a.lua", 0, 5));
        assertEquals(before, _disk.spaceUsed());
    }

    @Test
    void contentIsChargedOnTopOfTheEntry() {
        _disk.createFile("/a.lua");

        _disk.write("/a.lua", 0, bytes("0123456789"));

        assertEquals(ENTRY_COST + 10, _disk.spaceUsed());
    }

    /**
     * The suite's decision test for this wave. All or nothing: the two
     * assertions after the throw are what tell a refusal from a write that
     * stopped where the room ran out, and the second shape leaves a file in a
     * state the script neither asked for nor can deduce.
     */
    @Test
    void aWriteThatDoesNotFitChangesNothing() {
        DiskImage tight = new DiskImage(ENTRY_COST + 4, ENTRY_COST);
        tight.createFile("/f");
        tight.write("/f", 0, bytes("abcd"));

        assertThrows(ComponentException.class, () -> tight.write("/f", 4, bytes("e")));
        assertEquals(4, tight.size("/f"));
        assertArrayEquals(bytes("abcd"), tight.read("/f", 0, 4));
    }

    @Test
    void aNegativeOffsetOrCountIsRefused() {
        _disk.createFile("/a.lua");

        assertThrows(ComponentException.class, () -> _disk.read("/a.lua", -1, 1));
        assertThrows(ComponentException.class, () -> _disk.read("/a.lua", 0, -1));
        assertThrows(ComponentException.class, () -> _disk.write("/a.lua", -1, bytes("a")));
    }

    /**
     * One precondition, three doors. Written as three separate checks, the
     * tenth copy forgets a case, which is why the body will have a single
     * fileAt.
     */
    @Test
    void theContentMethodsRefuseWhatIsNotAFile() {
        _disk.makeDirectory("/d");

        assertThrows(ComponentException.class, () -> _disk.size("/d"));
        assertThrows(ComponentException.class, () -> _disk.read("/d", 0, 1));
        assertThrows(ComponentException.class, () -> _disk.write("/d", 0, bytes("a")));
        assertThrows(ComponentException.class, () -> _disk.size("/nowhere"));
        assertThrows(ComponentException.class, () -> _disk.truncate("/d"));
    }

    @Test
    void truncatingEmptiesTheFile() {
        _disk.createFile("/a.lua");
        _disk.write("/a.lua", 0, bytes("abcde"));

        _disk.truncate("/a.lua");

        assertEquals(0, _disk.size("/a.lua"));
        assertEquals(0, _disk.read("/a.lua", 0, 10).length);
    }

    /** The entry stays and keeps costing; only its content is given back. */
    @Test
    void truncatingRefundsTheContent() {
        _disk.createFile("/a.lua");
        _disk.write("/a.lua", 0, bytes("0123456789"));

        _disk.truncate("/a.lua");

        assertEquals(ENTRY_COST, _disk.spaceUsed());
    }

    /**
     * The suite's decision test for this wave. A truncate that only sets the
     * length to zero passes the two above and fails here: the gap of the next
     * write past the end would come back holding the old file.
     */
    @Test
    void writingAfterATruncateDoesNotResurrectTheOldContent() {
        _disk.createFile("/a.lua");
        _disk.write("/a.lua", 0, bytes("abcde"));
        _disk.truncate("/a.lua");

        _disk.write("/a.lua", 3, bytes("Z"));

        assertArrayEquals(new byte[] { 0, 0, 0, 'Z' }, _disk.read("/a.lua", 0, 4));
    }

    @Test
    void listingGivesTheNamesInTheOrderTheyWereCreated() {
        _disk.makeDirectory("/b");
        _disk.createFile("/a.lua");
        _disk.makeDirectory("/c");

        assertEquals(List.of("b", "a.lua", "c"), _disk.list("/"));
    }

    /** Names, not paths: the caller knows what it asked for. */
    @Test
    void listingGivesNamesAndNotPaths() {
        _disk.makeDirectory("/a");
        _disk.createFile("/a/x.lua");

        assertEquals(List.of("x.lua"), _disk.list("/a"));
    }

    @Test
    void anEmptyDirectoryListsNothing() {
        _disk.makeDirectory("/a");

        assertTrue(_disk.list("/a").isEmpty());
    }

    @Test
    void listRefusesWhatIsNotADirectory() {
        _disk.createFile("/f");

        assertThrows(ComponentException.class, () -> _disk.list("/f"));
        assertThrows(ComponentException.class, () -> _disk.list("/nowhere"));
    }

    /** A copy, not a window: a caller cannot reach the tree through it. */
    @Test
    void theListingIsItsOwn() {
        _disk.makeDirectory("/a");
        List<String> names = _disk.list("/");

        assertThrows(UnsupportedOperationException.class, () -> names.add("b"));
    }

    @Test
    void removingAFileMakesItVanishAndRefundsIt() {
        _disk.createFile("/f");
        _disk.write("/f", 0, bytes("0123456789"));

        assertTrue(_disk.remove("/f"));
        assertFalse(_disk.exists("/f"));
        assertEquals(0, _disk.spaceUsed());
    }

    @Test
    void removingAnEmptyDirectoryIsAllowed() {
        _disk.makeDirectory("/a");

        assertTrue(_disk.remove("/a"));
        assertEquals(0, _disk.spaceUsed());
    }

    /** The second assertion says nothing was half-removed on the way out. */
    @Test
    void aDirectoryWithChildrenIsNotRemoved() {
        _disk.makeDirectory("/a");
        _disk.createFile("/a/x.lua");

        assertFalse(_disk.remove("/a"));
        assertTrue(_disk.exists("/a/x.lua"));
    }

    @Test
    void removingWhatIsNotThereIsFalse() {
        assertFalse(_disk.remove("/nowhere"));
        assertFalse(_disk.remove("/"));
    }

    @Test
    void removeReadsAPathByTheSameRule() {
        assertThrows(ComponentException.class, () -> _disk.remove("/.."));
    }

    @Test
    void renamingMovesTheEntryAndItsContent() {
        _disk.createFile("/f");
        _disk.write("/f", 0, bytes("abc"));

        assertTrue(_disk.rename("/f", "/g"));
        assertFalse(_disk.exists("/f"));
        assertArrayEquals(bytes("abc"), _disk.read("/g", 0, 3));
    }

    @Test
    void renamingCostsNothing() {
        _disk.createFile("/f");
        _disk.write("/f", 0, bytes("abc"));
        long before = _disk.spaceUsed();

        _disk.rename("/f", "/g");

        assertEquals(before, _disk.spaceUsed());
    }

    @Test
    void renamingOntoAnExistingNameIsRefused() {
        _disk.createFile("/f");
        _disk.createFile("/g");

        assertFalse(_disk.rename("/f", "/g"));
        assertTrue(_disk.exists("/f"));
    }

    @Test
    void renamingWhatIsNotThereIsFalse() {
        assertFalse(_disk.rename("/nowhere", "/x"));
    }

    @Test
    void renamingIntoAMissingDirectoryIsRefused() {
        _disk.createFile("/f");

        assertFalse(_disk.rename("/f", "/nope/g"));
        assertTrue(_disk.exists("/f"));
    }

    /**
     * The wave's decision test. Without the check the branch is unhooked from
     * the root and reattached under itself, so nothing reaches it any more and
     * no exception is thrown.
     */
    @Test
    void aDirectoryCannotBeRenamedIntoItsOwnDescendant() {
        _disk.makeDirectory("/a");
        _disk.makeDirectory("/a/b");
        _disk.createFile("/a/b/x.lua");

        assertFalse(_disk.rename("/a", "/a/b/c"));
        assertTrue(_disk.exists("/a/b/x.lua"));
    }

    @Test
    void renameReadsAPathByTheSameRule() {
        _disk.createFile("/f");

        assertThrows(ComponentException.class, () -> _disk.rename("/..", "/x"));
        assertThrows(ComponentException.class, () -> _disk.rename("/f", "/.."));
    }

    private DiskImage restoredInto(DiskImage target) {
        target.restore(_disk.snapshot());
        return target;
    }

    @Test
    void aRoundTripKeepsTheTree() {
        _disk.makeDirectory("/a");
        _disk.makeDirectory("/a/b");
        _disk.createFile("/a/b/x.lua");
        _disk.write("/a/b/x.lua", 0, bytes("print()"));
        _disk.createFile("/f");

        DiskImage copy = restoredInto(new DiskImage(4096, ENTRY_COST));

        assertTrue(copy.isDirectory("/a/b"));
        assertArrayEquals(bytes("print()"), copy.read("/a/b/x.lua", 0, 7));
        assertTrue(copy.exists("/f"));
        assertEquals(0, copy.size("/f"));
    }

    @Test
    void aRoundTripKeepsTheAccounting() {
        _disk.makeDirectory("/a");
        _disk.createFile("/a/x.lua");
        _disk.write("/a/x.lua", 0, bytes("0123456789"));

        DiskImage copy = restoredInto(new DiskImage(4096, ENTRY_COST));

        assertEquals(_disk.spaceUsed(), copy.spaceUsed());
    }

    /** list promises the order names were created in, so the trip must keep it. */
    @Test
    void aRoundTripKeepsTheOrderOfNames() {
        _disk.makeDirectory("/b");
        _disk.createFile("/a.lua");
        _disk.makeDirectory("/c");

        DiskImage copy = restoredInto(new DiskImage(4096, ENTRY_COST));

        assertEquals(List.of("b", "a.lua", "c"), copy.list("/"));
    }

    @Test
    void restoreReplacesWhatWasThere() {
        _disk.createFile("/new");
        DiskImage target = new DiskImage(4096, ENTRY_COST);
        target.createFile("/old");

        restoredInto(target);

        assertFalse(target.exists("/old"));
        assertTrue(target.exists("/new"));
    }

    @Test
    void anEmptyDiskRoundTrips() {
        DiskImage copy = restoredInto(new DiskImage(4096, ENTRY_COST));

        assertTrue(copy.list("/").isEmpty());
        assertEquals(0, copy.spaceUsed());
    }

    /**
     * The suite's decision test on the trip. Enforcing the capacity here loses
     * a player's files the day a disk is restored into a smaller one, which is
     * silent and happens at a world load.
     */
    @Test
    void aDiskLargerThanTheCapacityIsStillRestored() {
        _disk.makeDirectory("/a");
        _disk.makeDirectory("/a/b");
        _disk.createFile("/a/b/x.lua");

        DiskImage small = restoredInto(new DiskImage(ENTRY_COST, ENTRY_COST));

        assertTrue(small.exists("/a/b/x.lua"));
        assertTrue(small.spaceUsed() > small.spaceTotal());
    }

    @Test
    void aMalformedSnapshotIsRefused() {
        assertThrows(ComponentException.class, () -> _disk.restore(bytes("garbage")));
    }

    private void assertMoves(Runnable mutation) {
        long before = _disk.revision();
        mutation.run();
        assertTrue(_disk.revision() > before, "the revision did not move");
    }

    /**
     * One assertion per mutator, because a forgotten increment is a write that
     * never reaches the disk and nothing says so.
     */
    @Test
    void everyMutationMovesTheRevision() {
        assertMoves(() -> _disk.makeDirectory("/a"));
        assertMoves(() -> _disk.createFile("/a/f"));
        assertMoves(() -> _disk.write("/a/f", 0, bytes("abc")));
        assertMoves(() -> _disk.write("/a/f", 0, bytes("x")));
        assertMoves(() -> _disk.truncate("/a/f"));
        assertMoves(() -> _disk.rename("/a/f", "/a/g"));
        assertMoves(() -> _disk.remove("/a/g"));
        assertMoves(() -> _disk.restore(new byte[0]));
    }

    @Test
    void aRefusedMutationLeavesTheRevisionAlone() {
        _disk.createFile("/f");
        long before = _disk.revision();

        _disk.createFile("/f");
        _disk.remove("/nowhere");
        _disk.rename("/nowhere", "/x");

        assertEquals(before, _disk.revision());
    }
}