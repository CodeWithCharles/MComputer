package io.github.codewithcharles.mcomputer.core.fs;

/**
 * One entry of a disk.
 *
 * <p>A sealed tree rather than a map of files beside a set of directory paths:
 * an empty directory is representable and no two structures can disagree about
 * anything. The two kinds carry different content, so the switch target is the
 * sealed type and exhaustiveness materialises.
 */
sealed interface Node permits FileNode, DirectoryNode {
}
