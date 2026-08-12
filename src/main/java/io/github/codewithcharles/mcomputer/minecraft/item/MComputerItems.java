package io.github.codewithcharles.mcomputer.minecraft.item;

import io.github.codewithcharles.mcomputer.MComputer;
import io.github.codewithcharles.mcomputer.minecraft.block.MComputerBlocks;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

/**
 * Every item this mod registers, and the only place that speaks to the item
 * registry.
 *
 * <p>Same shape as {@code MComputerBlocks}, including the empty
 * {@link #register()} whose only job is to let the entrypoint choose when the
 * static initialiser runs - and for the same reason: a registration that happens
 * whenever some unrelated code first mentions the class can land after the
 * registries have frozen.
 */
public final class MComputerItems {

    private static final ResourceKey<Item> COMPUTER_KEY = ResourceKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath(MComputer.MOD_ID, "computer"));

    public static final Item COMPUTER = Registry.register(
            BuiltInRegistries.ITEM,
            COMPUTER_KEY,
            new BlockItem(MComputerBlocks.COMPUTER, new Item.Properties()
                    .setId(COMPUTER_KEY)
                    .useBlockDescriptionPrefix()));

    private MComputerItems() {
    }

    /** Forces this class to load, and with it every registration above. */
    public static void register() {
    }
}
