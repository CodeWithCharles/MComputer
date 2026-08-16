package io.github.codewithcharles.mcomputer.core.screen;

import io.github.codewithcharles.mcomputer.core.component.Arguments;
import io.github.codewithcharles.mcomputer.core.component.ComponentApi;

/**
 * The graphics card, as a script sees it: {@code set}, {@code write},
 * {@code getResolution} and {@code getCursor} over one {@link ScreenBuffer}.
 *
 * <p>Every method here is synchronous, where {@code print} reaches the buffer a
 * tick later through the output queue. A shell that printed and then asked
 * where its cursor was would read a position from before the drain.
 *
 * <p>It lives in {@code core} because its domain does. A component's API
 * belongs to the layer of the thing it drives, and this one drives the screen.
 * What stays with the adapter is producing the component: deciding which buffer
 * belongs to which computer, and minting its address.
 *
 * <p>{@code getCursor} is the one-based row {@code write} last used, and zero
 * when nothing has been written. It deliberately does not answer where the next
 * line will land: on a full screen that row does not exist until the write
 * scrolls, so a caller painting it with {@code set} paints a row the scroll
 * then moves, leaving a copy behind and destroying the line that was there.
 *
 * <p>Coordinates are one-based, as in Lua, where {@link ScreenBuffer} counts
 * from zero. That shift lives in {@link #api} and is the fourth in this
 * project; the other three are in the converter, twice, and in
 * {@code Arguments} on the way to an error message.
 */
public final class Gpu {

    private Gpu() {
    }

    /**
     * @param screen the buffer this card draws on. Held by the returned
     *               methods, so one api() call serves one screen.
     */
    public static ComponentApi api(ScreenBuffer screen) {
        return ComponentApi.builder("gpu")
                .method("set", arguments -> {
                    int column = coordinate(arguments, 0, screen.width());
                    int row = coordinate(arguments, 1, screen.height());
                    screen.set(column, row, arguments.checkBytes(2));
                    return new Object[0];
                })
                .method("getResolution", arguments ->
                    new Object[] { (double) screen.width(), (double) screen.height() })
                .method("write", arguments -> {
                    screen.writeLine(arguments.checkBytes(0));
                    return new Object[0];
                })
                .method("getCursor", arguments ->
                        new Object[] { (double) screen.writePosition() })
                .build();
    }

    /**
     * One-based in, zero-based out, and the bound checked here rather than left
     * to {@link ScreenBuffer}: its IndexOutOfBoundsException would leave luaj
     * inside a HostFailure and kill the machine for a typo.
     */
    private static int coordinate(Arguments arguments, int index, int size) {
        int value = arguments.checkInt(index);
        if (value < 1 || value > size) {
            throw arguments.badArgument(index, "expected 1.." + size + ", got " + value);
        }
        return value - 1;
    }
}