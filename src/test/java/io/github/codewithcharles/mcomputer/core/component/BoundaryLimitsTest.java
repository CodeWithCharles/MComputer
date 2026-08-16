package io.github.codewithcharles.mcomputer.core.component;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BoundaryLimitsTest {

    @Test
    void    defaultsAreDepth8And4096Entries() {
        BoundaryLimits limits = BoundaryLimits.defaults();
        assertEquals(8, limits.maxDepth());
        assertEquals(4096, limits.maxEntries());
    }

    @Test
    void    aDepthOfZeroIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BoundaryLimits(0, 10));
    }

    @Test
    void    aNegativeEntryCountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BoundaryLimits(2, -1));
    }
}
