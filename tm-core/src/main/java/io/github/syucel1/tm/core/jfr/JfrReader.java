package io.github.syucel1.tm.core.jfr;

import io.github.syucel1.tm.core.model.Frame;
import io.github.syucel1.tm.core.model.JvmRecording;
import io.github.syucel1.tm.core.model.StackTable;
import io.github.syucel1.tm.core.model.ThreadInfo;
import io.github.syucel1.tm.core.model.TmEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import jdk.jfr.Timespan;
import jdk.jfr.Timestamp;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedClassLoader;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordedThreadGroup;
import jdk.jfr.consumer.RecordingFile;

/**
 * Reads a {@code .jfr} file into a {@link JvmRecording}.
 *
 * <pre>{@code
 * JvmRecording rec = JfrReader.create().read(Path.of("demo.jfr"));
 * }</pre>
 *
 * <p>Stack traces are deduplicated into a {@link StackTable}; threads are collected into a thread
 * table; other event fields become simple attributes (strings, numbers, booleans). Nested
 * structures without a simple form are dropped. Time-span fields are converted to nanoseconds
 * and timestamp fields to nanoseconds since the Unix epoch (JFR stores both in internal ticks).
 */
public final class JfrReader {

    /** Fields handled by the model itself rather than copied into attributes. */
    private static final Set<String> MODEL_FIELDS =
            Set.of("startTime", "duration", "eventThread", "sampledThread", "stackTrace");

    private final Predicate<String> typeFilter;
    private final boolean includeAttributes;

    private JfrReader(Predicate<String> typeFilter, boolean includeAttributes) {
        this.typeFilter = typeFilter;
        this.includeAttributes = includeAttributes;
    }

    /** A reader that keeps every event type and its attributes. */
    public static JfrReader create() {
        return new JfrReader(t -> true, true);
    }

    /** Only keep events whose type name matches {@code filter}. */
    public JfrReader withTypeFilter(Predicate<String> filter) {
        return new JfrReader(Objects.requireNonNull(filter), includeAttributes);
    }

    /** Skip attribute extraction to save memory when only time, thread and stack are needed. */
    public JfrReader withoutAttributes() {
        return new JfrReader(typeFilter, false);
    }

    public JvmRecording read(Path file) throws IOException {
        StackTable stacks = new StackTable();
        Map<Long, ThreadInfo> threads = new HashMap<>();
        List<TmEvent> events = new ArrayList<>();
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent e = recording.readEvent();
                if (typeFilter.test(e.getEventType().getName())) {
                    events.add(convert(e, stacks, threads));
                }
            }
        }
        events.sort(Comparator.comparingLong(TmEvent::startNanos));
        return new JvmRecording(file, events, stacks, threads);
    }

    private TmEvent convert(RecordedEvent e, StackTable stacks, Map<Long, ThreadInfo> threads) {
        String type = e.getEventType().getName();
        long start = toEpochNanos(e.getStartTime());
        Duration d = e.getDuration();
        long duration = d == null ? 0 : d.toNanos();
        long threadKey = registerThread(threadOf(e), threads);
        int stackId = internStack(e.getStackTrace(), stacks);
        Map<String, Object> attrs = includeAttributes ? attributesOf(e) : Map.of();
        return new TmEvent(type, start, duration, threadKey, stackId, attrs);
    }

    /** The thread an event belongs to: {@code eventThread}, or {@code sampledThread} for samples. */
    private static RecordedThread threadOf(RecordedEvent e) {
        RecordedThread t = e.getThread();
        if (t == null && e.hasField("sampledThread")) {
            t = e.getThread("sampledThread");
        }
        return t;
    }

    private static long registerThread(RecordedThread t, Map<Long, ThreadInfo> threads) {
        if (t == null) {
            return ThreadInfo.NO_THREAD;
        }
        long key = ThreadInfo.keyOf(t.getJavaThreadId(), t.getOSThreadId());
        if (key != ThreadInfo.NO_THREAD && !threads.containsKey(key)) {
            String name = t.getJavaName() != null ? t.getJavaName() : t.getOSName();
            threads.put(key, new ThreadInfo(key, t.getJavaThreadId(), t.getOSThreadId(),
                    name == null ? "" : name, t.isVirtual()));
        }
        return key;
    }

    private static int internStack(RecordedStackTrace st, StackTable stacks) {
        if (st == null) {
            return StackTable.NO_STACK;
        }
        List<RecordedFrame> recorded = st.getFrames();
        List<Frame> frames = new ArrayList<>(recorded.size());
        for (RecordedFrame f : recorded) {
            RecordedMethod m = f.getMethod();
            if (m == null) {
                continue;
            }
            String cls = m.getType() != null ? m.getType().getName() : "<unknown>";
            frames.add(new Frame(cls, m.getName(), m.getDescriptor(), f.getLineNumber(), f.getType()));
        }
        return stacks.intern(frames, st.isTruncated());
    }

    private static Map<String, Object> attributesOf(RecordedEvent e) {
        Map<String, Object> attrs = new HashMap<>();
        for (ValueDescriptor field : e.getFields()) {
            String name = field.getName();
            if (MODEL_FIELDS.contains(name) || field.isArray()) {
                continue;
            }
            Object value;
            if (field.getAnnotation(Timespan.class) != null) {
                // Raw values are in JFR ticks; always expose time spans as nanoseconds.
                value = toNanos(e.getDuration(name));
            } else if (field.getAnnotation(Timestamp.class) != null) {
                Instant t = e.getInstant(name);
                value = t == null ? null : toEpochNanos(t);
            } else {
                value = simplify(e.getValue(name));
            }
            if (value != null) {
                attrs.put(name, value);
            }
        }
        return attrs;
    }

    private static Long toNanos(Duration d) {
        if (d == null) {
            return null;
        }
        try {
            return d.toNanos();
        } catch (ArithmeticException overflow) {
            return d.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    /** Converts a JFR value to a string, number or boolean, or {@code null} if it has no simple form. */
    static Object simplify(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Character c) {
            return String.valueOf(c);
        }
        if (v instanceof RecordedClass c) {
            return c.getName();
        }
        if (v instanceof RecordedThread t) {
            return t.getJavaName() != null ? t.getJavaName() : t.getOSName();
        }
        if (v instanceof RecordedMethod m) {
            return (m.getType() != null ? m.getType().getName() + "." : "") + m.getName();
        }
        if (v instanceof RecordedClassLoader cl) {
            if (cl.getName() != null) {
                return cl.getName();
            }
            return cl.getType() != null ? cl.getType().getName() : null;
        }
        if (v instanceof RecordedThreadGroup g) {
            return g.getName();
        }
        return null;
    }

    static long toEpochNanos(Instant t) {
        return t.getEpochSecond() * 1_000_000_000L + t.getNano();
    }
}
