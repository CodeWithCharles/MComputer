package io.github.codewithcharles.mcomputer.luaj;

import io.github.codewithcharles.mcomputer.core.component.BoundaryLimits;
import io.github.codewithcharles.mcomputer.core.component.ComponentException;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Translates between LuaJ values and the closed list of boundary values.
 *
 * <p>This class is the reason the {@code luaj} package exists. It imports
 * {@code org.luaj}, so it cannot live in {@code core} - the ArchUnit rule
 * forbidding it is what forces this separation to be real rather than intended.
 *
 * <p>The mapping, and it is the whole contract:
 *
 * <pre>
 *   nil      <-> null
 *   boolean  <-> Boolean
 *   number   <-> Double        (Lua has no integers; never send a long)
 *   string   <-> byte[]        (Lua strings are bytes, not text)
 *   table    <-> Map           (incoming, always)
 *   table    <-  Map or List   (outgoing; a List becomes keys 1..n)
 * </pre>
 *
 * <p>Two asymmetries, both deliberate:
 * <ul>
 *   <li>A table always becomes a {@code Map} on the way in - no "looks like an
 *       array" heuristic. Incoming data is adversarial and must be
 *       unambiguous. On the way out the data is ours, so {@code List} is
 *       allowed as a convenience. Round-tripping therefore holds in <b>Lua</b>
 *       terms, not in Java ones.</li>
 *   <li><b>Table keys that are strings become {@code String}, not
 *       {@code byte[]}.</b> A key needs value equality to work at all, and
 *       {@code byte[]} has none - {@code map.get(bytes)} would silently never
 *       match. Values keep their bytes.</li>
 * </ul>
 *
 * <p>Both directions are checked. Inbound because a player's script is
 * untrusted; outbound because the switch has to exist anyway, so rejecting a
 * stray {@code Long} or {@code String} costs nothing and catches our own bugs
 * at the only place they can still be understood.
 *
 * <p>Not thread-safe in any interesting way, but immutable, therefore safe
 * to share.
 */
public final class ValueConverter {

    private final BoundaryLimits limits;

    public ValueConverter(BoundaryLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Lua call arguments to boundary values, ready to be wrapped in
     * {@code Arguments}.
     * @apiNote {@code Varargs} is 1-based, the returned array is 0-based. This
     *          is the only other place that shift exists; {@code Arguments}
     *          owns the second one, on the way to the error message.
     *
     * @throws ComponentException if a value is outside the closed list, or the
     *         structure exceeds the limits. This is a script error and becomes
     *         a Lua error.
     */
    public Object[] toJava(Varargs arguments) {
        Budget budget = new Budget(limits.maxEntries());
        int count = arguments.narg();
        Object[] out = new Object[count];
        for (int i = 1; i <= count; i++) {
            out[i - 1] = toJava(arguments.arg(i), limits.maxDepth(), budget);
        }
        return out;
    }

    /** Boundary values returned by a component method, back to Lua.
     *
     * @throws IllegalStateException if a value is outside the closed list. Not
     *         a {@code ComponentException}: nothing the player did can cause
     *         this, so it must surface as the bug it is instead of being
     *         converted into a Lua error and lost.
     */
    public Varargs toLua(Object[] values) {
        Budget budget = new Budget(limits.maxEntries());
        LuaValue[] out = new LuaValue[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = toLua(values[i], limits.maxDepth(), budget);
        }
        return LuaValue.varargsOf(out);
    }

    // --- the recursion ----------------------------------------------------
    // Depth is counted down, the entry budget is shared across the whole walk.
    // Both are threaded through the recursion rather than held as fields, so
    // the converter stays stateless and one call cannot poison the next.


    private static byte[] bytesOf(LuaString string) {
        byte[] out = new byte[string.length()];
        string.copyInto(0, out, 0, out.length);
        return out;
    }

    private Map<Object, Object> tableToJava(LuaTable table, int depth, Budget budget) {
        if (depth <= 0) {
            throw new ComponentException("table too deep (possible cycle)");
        }
        Map<Object, Object> out = new LinkedHashMap<>();
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs entry = table.next(key);
            if (entry.isnil(1)) {
                return out;
            }
            if (!budget.trySpend()) {
                throw new ComponentException("table has too many entries");
            }
            key = entry.arg(1);
            out.put(keyToJava(key), toJava(entry.arg(2), depth - 1, budget));
        }
    }

    private static Object keyToJava(LuaValue key) {
        return switch (key.type()) {
            case LuaValue.TSTRING -> textKey(key.checkstring());
            case LuaValue.TNUMBER -> key.todouble();
            default -> throw new ComponentException(
                    "unsupported table key (" + key.typename() + ")");
        };
    }

    private static String textKey(LuaString key) {
        if (!key.isValidUtf8()) {
            throw new ComponentException("table key is not valid UTF-8");
        }
        return key.tojstring();
    }

    private LuaTable mapToLua(Map<?, ?> map, int depth, Budget budget) {
        if (depth <= 0) {
            throw new IllegalStateException("table too deep (possible cycle)");
        }
        LuaTable out = new LuaTable();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!budget.trySpend()) {
                throw new IllegalStateException("table has too many entries");
            }
            out.set(keyToLua(entry.getKey()), toLua(entry.getValue(), depth - 1, budget));
        }
        return out;
    }

    private LuaTable listToLua(List<?> list, int depth, Budget budget) {
        if (depth <= 0) {
            throw new IllegalStateException("table too deep (possible cycle)");
        }
        LuaTable out = new LuaTable();
        for (int i = 0; i < list.size(); i++) {
            if (!budget.trySpend()) {
                throw new IllegalStateException("table has too many entries");
            }
            out.set(i + 1, toLua(list.get(i), depth - 1, budget));  // Lua indexes from 1
        }
        return out;
    }

    /**
     * Accept a String while toLua refuses it because keys becomes String and values keeps their bytes.
     * @param key key to convert
     * @return The key to lua value
     */
    private static LuaValue keyToLua(Object key) {
        return switch (key) {
            case String text -> LuaValue.valueOf(text);
            case Double number -> LuaValue.valueOf(number);
            case null, default -> throw new IllegalStateException(
                    "unsupported table key: "
                            + (key == null ? "null" : key.getClass().getName()));
        };
    }

    private Object toJava(LuaValue value, int depth, Budget budget) {
        return switch (value.type()) {
            case LuaValue.TNIL      -> null;
            case LuaValue.TBOOLEAN  -> value.toboolean();
            case LuaValue.TNUMBER   -> value.todouble();
            case LuaValue.TSTRING   -> bytesOf(value.checkstring());
            case LuaValue.TTABLE    -> tableToJava(value.checktable(), depth, budget);
            default -> throw new ComponentException(
                    "unsupported value (" + value.typename() + ")");
        };
    }

    private LuaValue toLua(Object value, int depth, Budget budget) {
        return switch (value) {
            case null   -> LuaValue.NIL;
            case Boolean b  -> LuaValue.valueOf(b);
            case Double d  -> LuaValue.valueOf(d);
            case byte[] bytes  -> LuaValue.valueOf(bytes);
            case Map<?, ?> map -> mapToLua(map, depth, budget);
            case List<?> list -> listToLua(list, depth, budget);
            default -> throw new IllegalStateException(
                    "value outside the boundary: " + value.getClass().getName());
        };
    }

    /** A mutable counter for one conversion. Deliberately not a field. */
    private static final class Budget {
        private int remaining;

        Budget(int remaining) {
            this.remaining = remaining;
        }

        /**
         * @return {@code false} when the budget is exhausted. Deliberately not an
         *         exception: the two directions of conversion report the same
         *         overflow with different exception types, and this counter has no
         *         business knowing which one it is serving.
         */
        boolean trySpend() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }
}
