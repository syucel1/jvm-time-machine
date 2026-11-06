package io.github.syucel1.tm.core.analysis;

import io.github.syucel1.tm.core.model.JvmRecording;
import io.github.syucel1.tm.core.model.ThreadInfo;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Recording metadata served by {@code GET /api/recording}.
 *
 * @param source          file name
 * @param startEpochMillis first event start, epoch milliseconds
 * @param durationSeconds first event start to last event end
 * @param eventCount      events
 * @param uniqueStacks    unique stack traces
 * @param eventTypes      events per type, largest first
 * @param threads         thread names, sorted
 */
public record RecordingInfo(
        String source,
        long startEpochMillis,
        double durationSeconds,
        long eventCount,
        int uniqueStacks,
        Map<String, Long> eventTypes,
        List<String> threads) {

    public static RecordingInfo of(JvmRecording recording) {
        return new RecordingInfo(
                recording.source() == null ? "" : String.valueOf(recording.source().getFileName()),
                recording.startNanos() / 1_000_000L,
                recording.duration().toNanos() / 1e9,
                recording.events().size(),
                recording.stacks().size(),
                recording.countByType(),
                recording.threads().values().stream()
                        .map(ThreadInfo::name)
                        .sorted(Comparator.naturalOrder())
                        .toList());
    }
}
