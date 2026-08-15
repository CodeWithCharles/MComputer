package io.github.codewithcharles.mcomputer;

import io.github.codewithcharles.mcomputer.minecraft.block.MComputerBlocks;
import io.github.codewithcharles.mcomputer.minecraft.block.entity.MComputerBlockEntities;
import io.github.codewithcharles.mcomputer.minecraft.item.MComputerItemGroups;
import io.github.codewithcharles.mcomputer.minecraft.item.MComputerItems;
import io.github.codewithcharles.mcomputer.minecraft.network.MComputerNetworking;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MComputer implements ModInitializer {
    public static final String MOD_ID = "mcomputer";

    /** Named after the mod id so log lines are attributable at a glance. */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Swapped in by Stonecutter from `mod.version`. */
    public static final String VERSION = /*$ mod_version*/ "0.1.0";

    /** Swapped in by Stonecutter - the Minecraft version this build targets. */
    public static final String MINECRAFT = /*$ minecraft*/ "26.2";

    @Override
    public void onInitialize() {
        LOGGER.info("Hello World from MComputer {} on Minecraft {}", VERSION, MINECRAFT);
        MComputerBlocks.register();
        MComputerBlockEntities.register();
        MComputerItems.register();
        MComputerItemGroups.register();
        MComputerNetworking.register();
    }
}