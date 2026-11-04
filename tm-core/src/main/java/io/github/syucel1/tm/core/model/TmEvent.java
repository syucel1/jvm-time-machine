package io.github.syucel1.tm.core.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One event on the shared time axis. Everything the time machine shows (CPU samples, GC pauses,
 * lock waits, snapshots, requests) is a {@code TmEvent}.
 *
 * @param type          JFR event type name, e.g. {@code jdk.ExecutionSample}
 * @param startNanos    start time in nanoseconds since the Unix epoch
 * @param durationNanos duration in nanoseconds; {@code 0} for instant events
 * @param threadKey     {@link ThreadInfo#key()} of the thread, or {@link ThreadInfo#NO_THREAD}
 * @param stackId       id in the recording's {@link StackTable}, or {@link StackTable#NO_STACK}
 * @param attributes    the event's other fields, simplified to strings, numbers and booleans
 */
public record TmEvent(
        String type,
        long startNanos,
        long durationNanos,
        long threadKey,
        int stackId,
        Map<String, Object> attributes) {

    public TmEvent {
        Objects.requireNonNull(type, "type");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public long endNanos() {
        return startNanos + durationNanos;
    }

    public boolean hasStack() {
        return stackId != StackTable.NO_STACK;
    }

    public boolean hasThread() {
        return threadKey != ThreadInfo.NO_THREAD;
    }

    public Instant startTime() {
        return Instant.ofEpochSecond(0, startNanos);
    }

    public Object attribute(String name) {
        return attributes.get(name);
    }
}
