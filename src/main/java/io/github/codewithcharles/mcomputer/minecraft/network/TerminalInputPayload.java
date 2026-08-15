package io.github.codewithcharles.mcomputer.minecraft.network;

import io.github.codewithcharles.mcomputer.MComputer;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One key event on its way from a terminal window to the computer behind it.
 *
 * <p>{@code character} and {@code code} arrive on two different callbacks -
 * GLFW separates them and 26.2 reflects that - so exactly one of the two is set
 * and the other is zero, which is also what a script tests.
 *
 * <p>The position is what the server resolves and range-checks. Nothing else
 * identifies the target: knowing a position must not be an authorisation.
 */
public record TerminalInputPayload(BlockPos pos, int character, int code)
        implements CustomPacketPayload {

    /**
     * Built by hand rather than with {@code CustomPacketPayload.createType},
     * which calls {@code Identifier.withDefaultNamespace} and therefore forces
     * the {@code minecraft} namespace.
     */
    public static final Type<TerminalInputPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MComputer.MOD_ID, "terminal_input"));

    public static final StreamCodec<ByteBuf, TerminalInputPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, TerminalInputPayload::pos,
                    ByteBufCodecs.VAR_INT, TerminalInputPayload::character,
                    ByteBufCodecs.VAR_INT, TerminalInputPayload::code,
                    TerminalInputPayload::new);

    @Override
    public Type<TerminalInputPayload> type() {
        return TYPE;
    }
}