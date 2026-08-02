package io.github.codewithcharles.mcomputer.core.component;

/**
 * How much structure one value may carry across the Java/Lua boundary.
 *
 * <p>These are a domain rule, not a detail of the Lua implementation: they say
 * what the boundary accepts. Hence, their home in {@code core}, even though the
 * only code reading them lives in the adapter that does the converting.
 *
 * <p>Injected rather than hardcoded for one concrete reason: a test of the depth
 * limit should build three levels against a limit of two, not two hundred levels
 * against a constant.
 *
 * @param maxDepth   how deeply tables may nest. Doubles as the cycle detector:
 *                   a self-referencing table simply runs out of depth.
 * @param maxEntries total number of entries across the <b>whole</b> structure,
 *                   not per table - a per-table cap is trivially defeated by a
 *                   thousand tables of a thousand entries.
 */
public record BoudaryLimits(int maxDepth, int maxEntries) {
}
