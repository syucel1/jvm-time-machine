package io.github.syucel1.tm.core.analysis;

import io.github.syucel1.tm.core.model.Frame;
import io.github.syucel1.tm.core.model.JvmRecording;
import io.github.syucel1.tm.core.model.StackTraceData;
import io.github.syucel1.tm.core.model.ThreadInfo;
import io.github.syucel1.tm.core.model.TmEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Answers "what was the JVM doing between A and B?": hot methods, allocations, lock contention,
 * GC and the busiest threads for one {@link Window}.
 *
 * <pre>{@code
 * WindowQuery.Result r = WindowQuery.run(recording, Window.ofOffsets(recording, 2.0, 5.0), 10);
 * }</pre>
 */
public final class WindowQuery {

    static final String GARBAGE_COLLECTION = "jdk.GarbageCollection";

    /**
     * Allocation pressure for one class or one code site.
     *
     * @param name    class name, or {@code className.methodName} of the allocating site
     * @param bytes   estimated bytes allocated (sum of sample weights)
     * @param samples allocation samples
     * @param percent share of all allocated bytes in the window, 0–100
     */
    public record Allocation(String name, long bytes, long samples, double percent) {
    }

    /**
     * Lock contention at one site.
     *
     * @param method       top frame of the blocked thread, {@code className.methodName}
     * @param monitorClass class of the contended lock object
     * @param count        number of blocking events
     * @param totalNanos   total time threads spent blocked
     * @param maxNanos     longest single block
     */
    public record Contention(String method, String monitorClass, long count, long totalNanos, long maxNanos) {
    }

    /**
     * One garbage collection.
     *
     * @param offsetSeconds start, seconds since the recording start
     * @param name          collector, e.g. {@code G1New}
     * @param cause         why it ran, e.g. {@code G1 Evacuation Pause}
     * @param durationNanos wall-clock duration of the collection
     * @param pauseNanos    total stop-the-world pause time
     * @param longestPauseNanos longest single pause
     */
    public record Gc(double offsetSeconds, String name, String cause, long durationNanos, long pauseNanos,
                     long longestPauseNanos) {
    }

    /**
     * GC totals for the window, with the longest collections listed.
     *
     * @param count       collections
     * @param pauseNanos  total pause time
     * @param maxPauseNanos longest single pause
     * @param longest     collections with the longest pauses, longest first
     */
    public record GcSummary(long count, long pauseNanos, long maxPauseNanos, List<Gc> longest) {
    }

    /**
     * Activity of one thread in the window.
     *
     * @param name           thread name
     * @param cpuSamples     CPU samples
     * @param allocatedBytes estimated bytes allocated
     * @param lockWaitNanos  time blocked on monitors
     */
    public record ThreadActivity(String name, long cpuSamples, long allocatedBytes, long lockWaitNanos) {
    }

    /**
     * Everything known about one window.
     *
     * @param fromSeconds          window start, seconds since recording start
     * @param toSeconds            window end, seconds since recording start
     * @param eventCount           events starting in the window
     * @param cpuSamples           CPU samples in the window
     * @param hotMethods           top methods by CPU samples
     * @param allocationsByClass   top allocated classes
     * @param allocationsBySite    top allocating application methods (first non-JDK frame)
     * @param contention           top lock-contention sites
     * @param gc                   GC totals and longest collections
     * @param threads              busiest threads
     */
    public record Result(
            double fromSeconds,
            double toSeconds,
            long eventCount,
            long cpuSamples,
            List<HotMethods.MethodCount> hotMethods,
            List<Allocation> allocationsByClass,
            List<Allocation> allocationsBySite,
            List<Contention> contention,
            GcSummary gc,
            List<ThreadActivity> threads) {
    }

    private WindowQuery() {
    }

    public static Result run(JvmRecording recording, Window window, int limit) {
        List<TmEvent> events = window.events(recording);
        long cpuSamples = events.stream().filter(e -> e.type().equals(HotMethods.EXECUTION_SAMPLE)).count();
        return new Result(
                window.fromOffsetSeconds(recording),
                window.toOffsetSeconds(recording),
                events.size(),
                cpuSamples,
                HotMethods.top(recording, events, HotMethods.EXECUTION_SAMPLE, limit),
                allocations(events, e -> e.attribute("objectClass") instanceof String s ? s : "<unknown>", limit),
                allocations(events, e -> siteOf(recording, e), limit),
                contention(recording, events, limit),
                gc(recording, events, limit),
                threads(recording, events, limit));
    }

    private static String siteOf(JvmRecording recording, TmEvent e) {
        Frame f = Frames.firstApplicationFrame(recording.stackOf(e));
        return f == null ? "<no stack>" : f.qualifiedName();
    }

    private static List<Allocation> allocations(List<TmEvent> events, Function<TmEvent, String> key, int limit) {
        Map<String, long[]> byKey = new HashMap<>();
        long total = 0;
        for (TmEvent e : events) {
            if (!e.type().equals(Timeline.ALLOCATION_SAMPLE)) {
                continue;
            }
            long weight = e.attribute("weight") instanceof Number n ? n.longValue() : 0;
            long[] acc = byKey.computeIfAbsent(key.apply(e), k -> new long[2]);
            acc[0] += weight;
            acc[1]++;
            total += weight;
        }
        long finalTotal = Math.max(total, 1);
        return byKey.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> en) -> en.getValue()[0]).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(en -> new Allocation(en.getKey(), en.getValue()[0], en.getValue()[1],
                        100.0 * en.getValue()[0] / finalTotal))
                .toList();
    }

    private static List<Contention> contention(JvmRecording recording, List<TmEvent> events, int limit) {
        record Key(String method, String monitorClass) {
        }
        Map<Key, long[]> byKey = new HashMap<>();
        for (TmEvent e : events) {
            if (!e.type().equals(Timeline.MONITOR_ENTER)) {
                continue;
            }
            StackTraceData stack = recording.stackOf(e);
            String method = stack == null || stack.top() == null ? "<no stack>" : stack.top().qualifiedName();
            String monitor = e.attribute("monitorClass") instanceof String s ? s : "<unknown>";
            long[] acc = byKey.computeIfAbsent(new Key(method, monitor), k -> new long[3]);
            acc[0]++;
            acc[1] += e.durationNanos();
            acc[2] = Math.max(acc[2], e.durationNanos());
        }
        return byKey.entrySet().stream()
                .map(en -> new Contention(en.getKey().method(), en.getKey().monitorClass(),
                        en.getValue()[0], en.getValue()[1], en.getValue()[2]))
                .sorted(Comparator.comparingLong(Contention::totalNanos).reversed()
                        .thenComparing(Contention::method))
                .limit(limit)
                .toList();
    }

    private static GcSummary gc(JvmRecording recording, List<TmEvent> events, int limit) {
        List<Gc> all = new ArrayList<>();
        long pauseTotal = 0;
        long pauseMax = 0;
        for (TmEvent e : events) {
            if (!e.type().equals(GARBAGE_COLLECTION)) {
                continue;
            }
            long pause = e.attribute("sumOfPauses") instanceof Number n ? n.longValue() : 0;
            long longest = e.attribute("longestPause") instanceof Number n ? n.longValue() : 0;
            pauseTotal += pause;
            pauseMax = Math.max(pauseMax, longest);
            all.add(new Gc(
                    (e.startNanos() - recording.startNanos()) / 1e9,
                    e.attribute("name") instanceof String s ? s : "",
                    e.attribute("cause") instanceof String s ? s : "",
                    e.durationNanos(), pause, longest));
        }
        List<Gc> longest = all.stream()
                .sorted(Comparator.comparingLong(Gc::pauseNanos).reversed()
                        .thenComparingDouble(Gc::offsetSeconds))
                .limit(limit)
                .toList();
        return new GcSummary(all.size(), pauseTotal, pauseMax, longest);
    }

    private static List<ThreadActivity> threads(JvmRecording recording, List<TmEvent> events, int limit) {
        Map<Long, long[]> byThread = new HashMap<>();
        for (TmEvent e : events) {
            if (!e.hasThread()) {
                continue;
            }
            int slot = switch (e.type()) {
                case HotMethods.EXECUTION_SAMPLE -> 0;
                case Timeline.ALLOCATION_SAMPLE -> 1;
                case Timeline.MONITOR_ENTER -> 2;
                default -> -1;
            };
            if (slot < 0) {
                continue;
            }
            long[] acc = byThread.computeIfAbsent(e.threadKey(), k -> new long[3]);
            acc[slot] += switch (slot) {
                case 0 -> 1;
                case 1 -> e.attribute("weight") instanceof Number n ? n.longValue() : 0;
                default -> e.durationNanos();
            };
        }
        return byThread.entrySet().stream()
                .map(en -> {
                    ThreadInfo t = recording.threads().get(en.getKey());
                    String name = t == null ? "thread-" + en.getKey() : t.name();
                    long[] a = en.getValue();
                    return new ThreadActivity(name, a[0], a[1], a[2]);
                })
                .sorted(Comparator.comparingLong(ThreadActivity::cpuSamples).reversed()
                        .thenComparing(Comparator.comparingLong(ThreadActivity::lockWaitNanos).reversed())
                        .thenComparing(ThreadActivity::name))
                .limit(limit)
                .toList();
    }
}
