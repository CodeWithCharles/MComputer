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
     * What a byte with no glyph is drawn as - the control range and DEL, and
     * nothing else once CP437 covers the rest.
     *
     * <p>Substituting on <b>output</b> is legitimate where substituting on an
     * argument would not be: an argument check may refuse a byte sequence, a
     * screen may not.
     *
     * <p><b>It could disappear entirely.</b> CP437 as a <i>font</i> gave the
     * 0x00-0x1F range real glyphs - smileys, arrows, card suits - where CP437 as
     * an <i>encoding</i>, which is what unicode.org publishes and what is used
     * here, leaves them as control characters. Adopting the font reading would
     * make every one of the 256 bytes printable, which is the honest end state
     * for a sink that cannot refuse. It waits on a source worth trusting.
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

    /**
     * CP437's upper half - bytes 0x80 to 0xFF - as the Unicode characters those
     * bytes stand for. Generated from unicode.org's own mapping table
     * (MAPPINGS/VENDORS/MICSFT/PC/CP437.TXT), not transcribed from memory.
     *
     * <p>Escaped rather than written literally: the source of this project stays
     * pure ASCII.
     */
    private static final String CP437_UPPER_HALF =
            "\u00c7\u00fc\u00e9\u00e2\u00e4\u00e0\u00e5\u00e7"
                    + "\u00ea\u00eb\u00e8\u00ef\u00ee\u00ec\u00c4\u00c5"
                    + "\u00c9\u00e6\u00c6\u00f4\u00f6\u00f2\u00fb\u00f9"
                    + "\u00ff\u00d6\u00dc\u00a2\u00a3\u00a5\u20a7\u0192"
                    + "\u00e1\u00ed\u00f3\u00fa\u00f1\u00d1\u00aa\u00ba"
                    + "\u00bf\u2310\u00ac\u00bd\u00bc\u00a1\u00ab\u00bb"
                    + "\u2591\u2592\u2593\u2502\u2524\u2561\u2562\u2556"
                    + "\u2555\u2563\u2551\u2557\u255d\u255c\u255b\u2510"
                    + "\u2514\u2534\u252c\u251c\u2500\u253c\u255e\u255f"
                    + "\u255a\u2554\u2569\u2566\u2560\u2550\u256c\u2567"
                    + "\u2568\u2564\u2565\u2559\u2558\u2552\u2553\u256b"
                    + "\u256a\u2518\u250c\u2588\u2584\u258c\u2590\u2580"
                    + "\u03b1\u00df\u0393\u03c0\u03a3\u03c3\u00b5\u03c4"
                    + "\u03a6\u0398\u03a9\u03b4\u221e\u03c6\u03b5\u2229"
                    + "\u2261\u00b1\u2265\u2264\u2320\u2321\u00f7\u2248"
                    + "\u00b0\u2219\u00b7\u221a\u207f\u00b2\u25a0\u00a0";

    private final int[] glyphOffset = new int[256];
    private final int cellWidth;
    private final int cellHeight;

    private final ComputerBlockEntity computer;

    private ComputerScreen(ComputerBlockEntity computer) {
        super(Component.literal("Computer"));
        this.computer = computer;

        this.cellHeight = font.lineHeight;
        // Measured over EVERY glyph we can draw, not just ASCII: a box-drawing
        // character wider than the widest letter would collide with its
        // neighbour, and the centring offset below would go negative. The rule
        // is unchanged - a cell is as wide as the widest glyph - only applied to
        // the real glyph set.
        int widest = 0;
        for (String glyph : GLYPHS) {
            widest = Math.max(widest, font.width(glyph));
        }
        this.cellWidth = widest;

        // Precomputed, so drawing a cell measures nothing. Centring is what
        // stops a narrow letter from floating against the left edge of its cell,
        // which is what the first screenshot showed on every 'i' and 'l'.
        for (int value = 0; value < GLYPHS.length; value++) {
            this.glyphOffset[value] = (cellWidth - font.width(GLYPHS[value])) / 2;
        }
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
                        column * cellWidth + glyphOffset[cell & 0xFF],
                        row * cellHeight, FOREGROUND, false);
            }
        }

        graphics.pose().popMatrix();
    }

    private static String[] buildGlyphs() {
        String[] glyphs = new String[256];
        for (int value = 0; value < glyphs.length; value++) {
            char glyph;
            if (value >= 0x20 && value < 0x7F) {
                glyph = (char) value; // CP437 agrees with ASCII here
            } else if (value >= 0x80) {
                glyph = CP437_UPPER_HALF.charAt(value - 0x80);
            } else {
                glyph = SUBSTITUTE; // controls, and DEL
            }
            glyphs[value] = String.valueOf(glyph);
        }
        return glyphs;
    }
}
