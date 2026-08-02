/**
 * Pure domain. Knows nothing about Minecraft and nothing about LuaJ.
 *
 * <p>Nothing in this package or its subpackages may reference
 * {@code net.minecraft}, {@code net.fabricmc}, {@code com.mojang} or
 * {@code org.luaj}. The ban on LuaJ is not an oversight: it is what forces the
 * {@code Vm} port to exist instead of remaining a good intention.
 *
 * <p>Acceptance criterion: if the core needs Minecraft in order to be tested,
 * the rule is violated. Enforced by {@code ArchitectureTest}.
 */
package io.github.codewithcharles.mcomputer.core;