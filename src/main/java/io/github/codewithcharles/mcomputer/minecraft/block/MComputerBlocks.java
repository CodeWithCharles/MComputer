package io.github.codewithcharles.mcomputer.minecraft.block;

import io.github.codewithcharles.mcomputer.MComputer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Every block this mod registers, and the only place that speaks to the block
 * registry.
 *
 * <p>The work happens in this class's static initialiser, which runs the first
 * time anything touches the class - hence {@link #register()}, an empty method
 * whose only job is to be called from the entrypoint at a moment we choose.
 * Without it, the fields would initialise whenever some unrelated code happened
 * to mention the class, possibly after the registries have frozen.
 */
public final class MComputerBlocks {

    private static final ResourceKey<Block> COMPUTER_KEY = ResourceKey.create(
            Registries.BLOCK,
            Identifier.fromNamespaceAndPath(MComputer.MOD_ID, "computer"));

    public static final Block COMPUTER = Registry.register(
            BuiltInRegistries.BLOCK,
            COMPUTER_KEY,
            new ComputerBlock(BlockBehaviour.Properties.of()
                    .setId(COMPUTER_KEY)
                    .strength(2.0F)));

    private MComputerBlocks() {

    }

    /** Forces this class to load, and with it every registration above. */
    public static void register() {
    }
}
