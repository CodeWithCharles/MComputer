/**
 * Adapters. The only zone aware of both worlds.
 *
 * <p>This package produces components from the game and drives the core from
 * the tick loop. Traffic is one way: components never reach back into the
 * world, and no Java object crosses to the Lua side -- handles only.
 */
package io.github.codewithcharles.mcomputer.minecraft;