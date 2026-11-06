package io.github.syucel1.tm.core.model;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * An in-memory recording: events sorted by start time, plus the shared stack and thread tables
 * they refer to. Immutable once built.
 */
public final class JvmRecording {

    private final Path source;
    private final List<TmEvent> events;
    private final StackTable stacks;
    private final Map<Long, ThreadInfo> threads;
    private final long startNanos;
    private final long endNanos;

    /**
     * @param source  file the recording came from, or {@code null}
     * @param events  events, already sorted by {@link TmEvent#startNanos()}
     * @param stacks  stack table the events' {@code stackId}s refer to
     * @param threads threads by {@link ThreadInfo#key()}
     */
    public JvmRecording(Path source, List<TmEvent> events, StackTable stacks, Map<Long, ThreadInfo> threads) {
        this.source = source;
        this.events = List.copyOf(events);
        this.stacks = stacks;
        this.threads = Map.copyOf(threads);
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (TmEvent e : this.events) {
            start = Math.min(start, e.startNanos());
            end = Math.max(end, e.endNanos());
        }
        this.startNanos = this.events.isEmpty() ? 0 : start;
        this.endNanos = this.events.isEmpty() ? 0 : end;
    }

    public Path source() {
        return source;
    }

    /** All events, sorted by start time. */
    public List<TmEvent> events() {
        return events;
    }

    public Stream<TmEvent> eventsOfType(String type) {
        return events.stream().filter(e -> e.type().equals(type));
    }

    /**
     * Events that start in {@code [fromNanos, toNanos)} (epoch nanoseconds), in start order.
     * Uses binary search on the sorted event list, so it is cheap even for large recordings.
     */
    public List<TmEvent> eventsStartingBetween(long fromNanos, long toNanos) {
        if (toNanos <= fromNanos) {
            return List.of();
        }
        return events.subList(lowerBound(fromNanos), lowerBound(toNanos));
    }

    /** Index of the first event with {@code startNanos >= t}. */
    private int lowerBound(long t) {
        int lo = 0;
        int hi = events.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (events.get(mid).startNanos() < t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    public StackTable stacks() {
        return stacks;
    }

    /** The stack of this event, or {@code null} if it has none. */
    public StackTraceData stackOf(TmEvent event) {
        return stacks.get(event.stackId());
    }

    public Map<Long, ThreadInfo> threads() {
        return threads;
    }

    /** The thread of this event, or {@code null} if it is not tied to a thread. */
    public ThreadInfo threadOf(TmEvent event) {
        return threads.get(event.threadKey());
    }

    public long startNanos() {
        return startNanos;
    }

    public long endNanos() {
        return endNanos;
    }

    public Instant startTime() {
        return Instant.ofEpochSecond(0, startNanos);
    }

    public Duration duration() {
        return Duration.ofNanos(endNanos - startNanos);
    }

    /** Event counts per type, largest first. */
    public Map<String, Long> countByType() {
        Map<String, Long> counts = new HashMap<>();
        for (TmEvent e : events) {
            counts.merge(e.type(), 1L, Long::sum);
        }
        Map<String, Long> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return Collections.unmodifiableMap(sorted);
    }
}
