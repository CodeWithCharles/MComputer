package io.github.codewithcharles.mcomputer.core.fs;

import java.util.Map;

/** A directory, and what sits under it in the order it was created. */
record DirectoryNode(Map<String, Node> children) implements Node {
}
