package io.github.codewithcharles.mcomputer.minecraft.client;

import io.github.codewithcharles.mcomputer.minecraft.block.entity.ComputerBlockEntity;
import io.github.codewithcharles.mcomputer.minecraft.network.TerminalInputPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * The computer's terminal, seen from the client.
 *
 * <p><b>A pure reader.</b> It holds the CLIENT copy of the block entity and
 * draws the ScreenBuffer that the server keeps up to date through the block
 * entity's own update packet.
 */
@Environment(EnvType.CLIENT)
public final class ComputerScreen extends Screen {
    private final ComputerBlockEntity computer;

    private ComputerScreen(ComputerBlockEntity computer) {
        super(Component.literal("Computer"));
        this.computer = computer;
    }

    public static void open(ComputerBlockEntity computer) {
        Minecraft.getInstance().setScreenAndShow(new ComputerScreen(computer));
    }

    /**
     * <b>The game must keep running while this is open.</b> In single player a
     * pausing screen stops the server tick, hence serverTick(), hence the drain
     * and the sync - the terminal would freeze the machine it exists to show.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)
    {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        TerminalRenderer.draw(graphics, font, computer.screen(), width, height);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (super.keyPressed(event)) {
            return true;
        }
        send(0, event.key());
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        send(event.codepoint(), 0);
        return true;
    }

    private void send(int character, int code) {
        ClientPlayNetworking.send(
                new TerminalInputPayload(computer.getBlockPos(), character, code));
    }
}