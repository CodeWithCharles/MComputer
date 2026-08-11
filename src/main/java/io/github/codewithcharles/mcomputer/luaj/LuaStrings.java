package io.github.codewithcharles.mcomputer.luaj;

import org.luaj.vm2.LuaString;

/**
 * The one place the raw bytes of a LuaString are taken.
 *
 * <p>It exists to stop a trap from being written twice. LuaJ pools short
 * strings, so the backing array is shared and its offset is not necessarily
 * zero: reading {@code m_bytes} directly, or assuming it starts at 0, hands back
 * another string's content. {@code copyInto} is the only honest route, and a
 * second copy of these four lines is a second place for someone to shorten it.
 *
 * <p>Same argument as {@code Arguments.check(...)} being factored while
 * {@code optX} was not: what is duplicated here has a failure mode.
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