package io.github.codewithcharles.mcomputer.core.component;

/**
 * How much structure one value may carry across the Java/Lua boundary.
 *
 * <p>A domain rule rather than a detail of the Lua implementation, which is why
 * it lives in {@code core} although only the converter reads it. Injected so a
 * depth test can build three levels against a limit of two.
 *
 * @param maxDepth   how deeply tables may nest. Doubles as the cycle detector:
 *                   a self-referencing table runs out of depth.
 * @param maxEntries total entries across the whole structure, not per table -
 *                   a per-table cap is defeated by a thousand small tables.
 */
public record BoundaryLimits(int maxDepth, int maxEntries) {

    public BoundaryLimits {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException(
                    "maxDepth must be > 0, got " + maxDepth);
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException(
                    "maxEntries must be > 0, got " + maxEntries);
        }
    }

    /** Sensible until something proves otherwise: depth 8, 4096 entries. */
    public static BoundaryLimits defaults() {
        return new BoundaryLimits(8, 4096);
    }
}
