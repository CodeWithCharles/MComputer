package io.github.codewithcharles.mcomputer.minecraft.mixin;

import io.github.codewithcharles.mcomputer.MComputer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Inject(method = "loadLevel", at = @At("HEAD"))
    private void mcomputer$onLoadLevel(CallbackInfo ci) {
        MComputer.LOGGER.info("Mixin toolchain OK - server is loading the level");
    }
}