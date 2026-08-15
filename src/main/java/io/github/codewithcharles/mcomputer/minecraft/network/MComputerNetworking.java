package io.github.codewithcharles.mcomputer.minecraft.network;

import io.github.codewithcharles.mcomputer.minecraft.block.entity.ComputerBlockEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * The client-to-server half of the terminal: one payload type and its receiver.
 *
 * <p>The handler hops to the server thread before touching anything.
 * {@code Machine.pushSignal} reads a field the class javadoc says needs no
 * synchronisation because only the server thread writes it, and a network
 * thread reading it would be the data race that rule exists to prevent.
 * {@code server.execute} is correct whether or not the API already delivers
 * there, which is why nothing here depends on knowing.
 */
public final class MComputerNetworking {

    private MComputerNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(
                TerminalInputPayload.TYPE, TerminalInputPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(TerminalInputPayload.TYPE,
                (payload, context) ->
                    context.server().execute(() -> deliver(context.player(), payload)));
    }

    /**
     * Opening the window and being allowed to type into it are two rights, and
     * this is the second. The range is the player's own
     * {@code blockInteractionRange()}, so a creative reach or a modifier is
     * honoured without a number of ours.
     *
     * <p>Out of range the window stays open and stops accepting keys, which is
     * what OpenComputers does.
     */
    private static void deliver(ServerPlayer player, TerminalInputPayload payload) {
        BlockPos pos = payload.pos();
        double reach = player.blockInteractionRange();
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
            > reach * reach) {
            return;
        }
        if (player.level().getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            computer.keyDown(player, payload.character(), payload.code());
        }
    }
}
