package io.github.codewithcharles.mcomputer.minecraft.client;

import io.github.codewithcharles.mcomputer.core.screen.ScreenBuffer;
import io.github.codewithcharles.mcomputer.minecraft.block.entity.ComputerBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The computer's terminal, seen from the client.
 *
 * <p><b>A pure reader.</b> It holds the CLIENT copy of the block entity and
 * draws the ScreenBuffer that the server keeps up to date through the block
 * entity's own update packet. Nothing here writes, and nothing here talks back.
 * The day a keyboard exists, that is the piece that gets added - and it is the
 * piece that will need the range check OpenComputers describes, where opening
 * the window and being allowed to type are two different rights.
 */
@Environment(EnvType.CLIENT)
public final class ComputerScreen extends Screen {

    /** TODO: guesses, sized on the vanilla font. The next step measures them. */
    private static final int CELL_WIDTH = 6;
    private static final int CELL_HEIGHT = 9;

    private static final int BACKGROUND = 0xFF000000;
    private static final int FOREGROUND = 0xFFFFFFFF;

    /**
     * What a byte with no glyph is drawn as. Substituting on <b>output</b> is
     * legitimate where substituting on an argument would not be: an argument
     * check may refuse a byte sequence, a screen may not - a script printing a
     * stray byte must not die. Same asymmetry that put a byte in every cell.
     */
    private static final char SUBSTITUTE = '?';

    /**
     * One single-character string per byte value, built once so that drawing a
     * cell allocates nothing - eighty by twenty-five, every frame.
     *
     * <p>This array <b>is</b> the byte-to-glyph table, and it is deliberately
     * the poorest one that is total: printable ASCII maps to itself, everything
     * else to {@link #SUBSTITUTE}. CP437 is what it becomes - it carries the
     * accents and the box-drawing glyphs - and it changes this method and
     * nothing else, which is the point of having kept core free of encodings.
     */
    private static final String[] GLYPHS = buildGlyphs();

    private final int cellWidth;
    private final int cellHeight;

    private final ComputerBlockEntity computer;

    private ComputerScreen(ComputerBlockEntity computer) {
        super(Component.literal("Computer"));
        this.computer = computer;

        // Measured, not guessed. The vanilla font is proportional, so a terminal
        // has to impose its own advance: the widest printable glyph, or letters
        // would collide. lineHeight is the font's own answer for the rows.
        this.cellHeight = font.lineHeight;
        int widest = 0;
        for (char glyph = ' '; glyph <= '~'; glyph++) {
            widest = Math.max(widest, font.width(String.valueOf(glyph)));
        }
        this.cellWidth = widest;
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
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick)
    {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        ScreenBuffer screen = computer.screen();
        int terminalWidth = screen.width() * cellWidth;
        int terminalHeight = screen.height() * cellHeight;

        // Never above 1: a terminal that fits is drawn at its own size, and only
        // a window too small for it shrinks. The client's GUI scale is a player
        // setting we do not control, so the fit is computed and not assumed.
        float scale = Math.min(1.0f, Math.min(
                (float) width / terminalWidth,
                (float) height / terminalHeight));

        graphics.pose().pushMatrix();
        graphics.pose().translate(
                (width - terminalWidth * scale) / 2.0f,
                (height - terminalHeight * scale) / 2.0f);
        graphics.pose().scale(scale, scale);

        graphics.fill(0, 0, terminalWidth, terminalHeight, BACKGROUND);

        for (int row = 0; row < screen.height(); row++) {
            for (int column = 0; column < screen.width(); column++) {
                byte cell = screen.byteAt(column, row);
                // A blank is most of a terminal. Skipping it turns two thousand
                // draw calls into a few dozen on a screen that has printed a
                // couple of lines.
                if (cell == ScreenBuffer.BLANK) {
                    continue;
                }
                graphics.text(font, GLYPHS[cell & 0xFF],
                        column * cellWidth, row * cellHeight, FOREGROUND, false);
            }
        }

        graphics.pose().popMatrix();
    }

    private static String[] buildGlyphs() {
        String[] glyphs = new String[256];
        for (int value = 0; value < glyphs.length; value++) {
            glyphs[value] = String.valueOf(
                    value >= 0x20 && value <= 0x7E ? (char) value : SUBSTITUTE);
        }
        return glyphs;
    }
}
