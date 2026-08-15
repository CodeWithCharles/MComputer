package io.github.codewithcharles.mcomputer.core.screen;

import io.github.codewithcharles.mcomputer.core.component.Arguments;
import io.github.codewithcharles.mcomputer.core.component.ComponentApi;

/**
 * The graphics card, as a script sees it: {@code set} and {@code getResolution}
 * over one {@link ScreenBuffer}.
 *
 * <p>It lives in {@code core} because its domain does. A component's API
 * belongs to the layer of the thing it drives, and this one drives the screen.
 * What stays with the adapter is producing the component: deciding which buffer
 * belongs to which computer, and minting its address.
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