package io.github.syucel1.tm.core.analysis;

import io.github.syucel1.tm.core.model.JvmRecording;
import io.github.syucel1.tm.core.model.TmEvent;

import java.util.List;

/**
 * A time range {@code [fromNanos, toNanos)} in epoch nanoseconds.
 *
 * <p>Events belong to a window if they <em>start</em> inside it; durations are counted in full.
 */
public record Window(long fromNanos, long toNanos) {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    public Window {
        if (toNanos < fromNanos) {
            throw new IllegalArgumentException("window ends before it starts: " + fromNanos + " > " + toNanos);
        }
    }

    /** The whole recording. */
    public static Window all(JvmRecording recording) {
        return new Window(recording.startNanos(), recording.endNanos() + 1);
    }

    /**
     * A window given in seconds relative to the recording start, e.g. {@code (2.5, 5)} is
     * 2.5 s to 5 s into the recording. {@code null} bounds mean "from the start" / "to the end".
     * Bounds are clamped to the recording.
     */
    public static Window ofOffsets(JvmRecording recording, Double fromSeconds, Double toSeconds) {
        long start = recording.startNanos();
        long end = recording.endNanos() + 1;
        long from = fromSeconds == null ? start : start + Math.round(fromSeconds * NANOS_PER_SECOND);
        long to = toSeconds == null ? end : start + Math.round(toSeconds * NANOS_PER_SECOND);
        from = Math.max(start, Math.min(from, end));
        to = Math.max(from, Math.min(to, end));
        return new Window(from, to);
    }

    public long durationNanos() {
        return toNanos - fromNanos;
    }

    public boolean contains(long nanos) {
        return nanos >= fromNanos && nanos < toNanos;
    }

    /** Events of the recording that start in this window. */
    public List<TmEvent> events(JvmRecording recording) {
        return recording.eventsStartingBetween(fromNanos, toNanos);
    }

    /** Start of the window in seconds since the recording start. */
    public double fromOffsetSeconds(JvmRecording recording) {
        return (fromNanos - recording.startNanos()) / (double) NANOS_PER_SECOND;
    }

    /** End of the window in seconds since the recording start. */
    public double toOffsetSeconds(JvmRecording recording) {
        return (toNanos - recording.startNanos()) / (double) NANOS_PER_SECOND;
    }
}
