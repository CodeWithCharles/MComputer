package io.github.codewithcharles.mcomputer.minecraft.block.entity;

import io.github.codewithcharles.mcomputer.MComputer;
import io.github.codewithcharles.mcomputer.minecraft.block.MComputerBlocks;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;

/**
 * Same shape as MComputerBlocks: registration in the static initialiser, and an
 * empty register() so the entrypoint decides when it happens.
 *
 * <p>No setId here - that requirement is the block registry's, not this one's.
 * The path "computer" is reused deliberately: these are two different
 * registries, so there is no clash, and one name for one thing beats two.
 */
public final class MComputerBlockEntities {

    public static final BlockEntityType<ComputerBlockEntity> COMPUTER = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(MComputer.MOD_ID, "computer"),
            new BlockEntityType<>(ComputerBlockEntity::new, Set.of(MComputerBlocks.COMPUTER)));

    private MComputerBlockEntities() {
    }

    public static void register() {
    }
}