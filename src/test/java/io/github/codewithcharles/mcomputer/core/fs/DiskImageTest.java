package io.github.codewithcharles.mcomputer.core.fs;

import io.github.codewithcharles.mcomputer.core.component.ComponentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DiskImageTest {

    private static final long ENTRY_COST = 512;

    private static final long CAPACITY = 4096;

    private final DiskImage _disk = new DiskImage(CAPACITY, ENTRY_COST);

    /**
     * Born green, the constructor being the one implemented body. Its red is
     * earned by deleting the capacity guard for ten seconds: it falls alone
     * while aNegativeEntryCostIsRejected stays green.
     */
    @Test
    void aCapacityOfZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DiskImage(0, ENTRY_COST));
    }

    /** Born green too, and the same manoeuvre on the other guard. */
    @Test
    void aNegativeEntryCostIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DiskImage(CAPACITY, -1));
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
}