package io.github.codewithcharles.mcomputer.luaj;

import org.luaj.vm2.LuaString;

/**
 * The one place the raw bytes of a LuaString are taken.
 *
 * <p>LuaJ pools short strings, so the backing array is shared and its offset is
 * not necessarily zero: reading {@code m_bytes} directly, or assuming it starts
 * at 0, hands back another string's content. A second copy of these four lines
 * is a second place for someone to shorten it.
 */
final class LuaStrings {

    private LuaStrings() {
    }

    static byte[] bytesOf(LuaString string) {
        byte[] out = new byte[string.length()];
        string.copyInto(0, out, 0, out.length);
        return out;
    }
}
