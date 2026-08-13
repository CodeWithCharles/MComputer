package io.github.codewithcharles.mcomputer.minecraft.client;

import io.github.codewithcharles.mcomputer.MComputer;
import io.github.codewithcharles.mcomputer.core.screen.ScreenBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * How a ScreenBuffer is drawn: the font, the cell, the frame, the scale rule
 * and the cell loop. Knows nothing about whose buffer it is or why it is on
 * screen - that is the caller's business.
 *
 * <p>It exists apart from {@link ComputerScreen} because the screen block draws
 * the same buffer from a different window, and in-world rendering will draw it
 * from no window at all.
 *
 * <p><b>CHANGING THE FONT.</b> Everything the terminal needs from its font is
 * the three constants below plus two numbers in
 * {@code assets/mcomputer/font/terminal.json}. To swap in another bitmap font:
 * <ol>
 *   <li>the atlas is a <b>16 by 16 grid of cells</b>, white on transparent, at
 *       {@code assets/mcomputer/textures/font/terminal.png}. Cell {@code (r, c)}
 *       holds the glyph for byte {@code r * 16 + c}. Nothing else about the
 *       image matters - its total size is sixteen cells each way, whatever the
 *       cell size is;</li>
 *   <li>set {@link #CELL_WIDTH} and {@link #CELL_HEIGHT} to the cell size in
 *       pixels;</li>
 *   <li>in {@code terminal.json}, set {@code height} to CELL_HEIGHT and
 *       {@code ascent} to <b>7</b>, whatever the cell height is. Minecraft puts
 *       a glyph cell's top at {@code y + 7 - ascent}: the 7 is its own fixed
 *       baseline offset, not a property of the font. Vanilla's own ascii.png is
 *       8 tall and declares 7 for exactly this reason. Measured on four
 *       screenshots after two wrong guesses;</li>
 *   <li>leave the {@code chars} array alone. It maps cell {@code (r, c)} to
 *       {@code U+E000 + r * 16 + c}, a private-use code point, which is also
 *       what {@link #buildGlyphs()} asks for. Both ends are ours, so no Unicode
 *       table is involved and none can go stale.</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
final class TerminalRenderer {

    /** Where the terminal font lives. Both ends of the byte-to-glyph mapping. */
    private static final Style TERMINAL = Style.EMPTY.withFont(
            new FontDescription.Resource(
                    Identifier.fromNamespaceAndPath(MComputer.MOD_ID, "terminal")));

    /**
     * How many physical pixels a font pixel may occupy. It is the terminal's
     * apparent size, and the only knob worth turning here: 2 gives a framed
     * window on any screen, 3 fills a large one edge to edge, 1 is crisp and
     * small. Never a fraction - that is what the whole scale calculation exists
     * to avoid.
     */
    private static final int MAX_PIXEL_SIZE = 1;

    /** The atlas cell, in pixels. See the class javadoc before changing these. */
    private static final int CELL_WIDTH = 8;
    private static final int CELL_HEIGHT = 16;

    /** The bezel, in font pixels, so it scales with the terminal. */
    private static final int FRAME_WIDTH = 4;
    private static final int FRAME = 0xFF8B8B8B;
    private static final int FRAME_LIGHT = 0xFFB4B4B4;
    private static final int FRAME_DARK = 0xFF4A4A4A;
    private static final int FRAME_EDGE = 0xFF1A1A1A;
    private static final int BACKGROUND = 0xFF000000;
    private static final int FOREGROUND = 0xFFFFFFFF;

    /**
     * One Component per byte value, built once so that drawing a cell allocates
     * nothing - eighty by twenty-five, every frame.
     *
     * <p>The style is what selects the terminal font; {@code text(Font, String,
     * ...)} would use the vanilla one, because a bare string carries no style.
     *
     * <p><b>Every byte has a glyph.</b> There is no substitute character any
     * more: byte {@code b} is code point {@code U+E000 + b}, and the atlas has a
     * cell for all 256. A sink that cannot refuse a byte should not have to
     * pretend one is unprintable.
     */
    private static final Component[] GLYPHS = buildGlyphs();

    private TerminalRenderer() {
    }

    private static Component[] buildGlyphs() {
        Component[] glyphs = new Component[256];
        for (int value = 0; value < glyphs.length; value++) {
            glyphs[value] = Component.literal(String.valueOf((char) (0xE000 + value)))
                    .withStyle(TERMINAL);
        }
        return glyphs;
    }

    /**
     * Draws the buffer centred in an area of {@code areaWidth} by
     * {@code areaHeight} GUI units, framed, at the largest whole number of
     * physical pixels per font pixel that fits and that MAX_PIXEL_SIZE allows.
     *
     * <p>The GUI scale is read from the window rather than taken as a
     * parameter: it is a property of the window, which no caller owns.
     */
    public static void draw(
            GuiGraphicsExtractor graphics, Font font,
            ScreenBuffer screen, int areaWidth, int areaHeight)
    {
        int f = FRAME_WIDTH;

        int terminalWidth = screen.width() * CELL_WIDTH;
        int terminalHeight = screen.height() * CELL_HEIGHT;

        int guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        int framed = FRAME_WIDTH + 1;
        int outerWidth = terminalWidth + 2 * framed;
        int outerHeight = terminalHeight + 2 * framed;
        int steps = Math.max(1, Math.min(MAX_PIXEL_SIZE, Math.min(
                areaWidth * guiScale / outerWidth,
                areaHeight * guiScale / outerHeight)));
        float scale = (float) steps / guiScale;

        graphics.pose().pushMatrix();
        graphics.pose().translate(
                (areaWidth - terminalWidth * scale) / 2.0f,
                (areaHeight - terminalHeight * scale) / 2.0f);
        graphics.pose().scale(scale, scale);

        graphics.fill(-f - 1, -f - 1,
                terminalWidth + f + 1, terminalHeight + f + 1, FRAME_EDGE);
        graphics.fill(-f, -f, terminalWidth + f, terminalHeight + f, FRAME);
        graphics.fill(-f, -f, terminalWidth + f, -f + 1, FRAME_LIGHT);
        graphics.fill(-f, -f, -f + 1, terminalHeight + f, FRAME_LIGHT);
        graphics.fill(-f, terminalHeight + f - 1,
                terminalWidth + f, terminalHeight + f, FRAME_DARK);
        graphics.fill(terminalWidth + f - 1, -f,
                terminalWidth + f, terminalHeight + f, FRAME_DARK);
        graphics.fill(-1, -1, terminalWidth + 1, terminalHeight + 1, FRAME_EDGE);
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
                        column * CELL_WIDTH, row * CELL_HEIGHT, FOREGROUND, false);
            }
        }

        graphics.pose().popMatrix();
    }
}