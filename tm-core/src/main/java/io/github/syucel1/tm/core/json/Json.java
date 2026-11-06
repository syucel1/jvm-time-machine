package io.github.syucel1.tm.core.json;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Minimal JSON writer with no dependencies, enough for the query API.
 *
 * <p>Supports {@code null}, strings, numbers, booleans, enums, {@link Instant} and {@link Path}
 * (as strings), maps (string keys), collections and arrays of objects, and records (as objects
 * whose keys are the record component names). Non-finite numbers become {@code null}.
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(value, sb);
        return sb.toString();
    }

    public static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            string(s, sb);
        } else if (v instanceof Double d) {
            number(d, sb);
        } else if (v instanceof Float f) {
            number(f.doubleValue(), sb);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Character || v instanceof Enum<?> || v instanceof Instant || v instanceof Path) {
            string(v.toString(), sb);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else if (v instanceof Object[] a) {
            write(java.util.Arrays.asList(a), sb);
        } else if (v.getClass().isRecord()) {
            record(v, sb);
        } else {
            string(v.toString(), sb);
        }
    }

    private static void record(Object r, StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (RecordComponent c : r.getClass().getRecordComponents()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            string(c.getName(), sb);
            sb.append(':');
            try {
                var accessor = c.getAccessor();
                accessor.setAccessible(true);
                write(accessor.invoke(r), sb);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("cannot read record component " + c.getName(), e);
            }
        }
        sb.append('}');
    }

    private static void number(double d, StringBuilder sb) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            sb.append("null");
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
