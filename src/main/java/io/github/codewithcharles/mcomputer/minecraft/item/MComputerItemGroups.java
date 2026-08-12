package io.github.codewithcharles.mcomputer.minecraft.item;

import io.github.codewithcharles.mcomputer.MComputer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * The mod's own creative tab, and it is vanilla all the way down - no Fabric API
 * module is needed to build one.
 *
 * <p>Its own tab rather than an entry appended to a vanilla one, because that is
 * the destination anyway: cards, disks and components all belong here, and
 * scattering them across Redstone and Functional Blocks would be a decision to
 * undo later.
 */
public final class MComputerItemGroups {

    private static final ResourceKey<CreativeModeTab> MAIN_KEY = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath(MComputer.MOD_ID, "main"));

    public static final CreativeModeTab MAIN = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            MAIN_KEY,
            CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.mcomputer.main"))
                    .icon(() -> new ItemStack(MComputerItems.COMPUTER))
                    .displayItems((parameters, output) ->
                            output.accept(new ItemStack(MComputerItems.COMPUTER)))
                    .build());

    private MComputerItemGroups() {
    }

    /** Forces this class to load, and with it every registration above. */
    public static void register() {
    }
}
