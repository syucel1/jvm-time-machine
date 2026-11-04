package io.github.syucel1.tm.core.analysis;

import io.github.syucel1.tm.core.model.JvmRecording;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Headline facts about a recording, used by {@code tm-cli summary}.
 *
 * @param source           file the recording came from
 * @param start            time of the first event
 * @param duration         time from first event start to last event end
 * @param eventCount       number of events
 * @param typeCounts       events per type, largest first
 * @param threadCount      distinct threads
 * @param uniqueStacks     unique stack traces after deduplication
 * @param stackReferences  events that carried a stack trace
 * @param hotMethods       top methods by CPU samples
 */
public record RecordingSummary(
        Path source,
        Instant start,
        Duration duration,
        long eventCount,
        Map<String, Long> typeCounts,
        int threadCount,
        int uniqueStacks,
        long stackReferences,
        List<HotMethods.MethodCount> hotMethods) {

    public static RecordingSummary of(JvmRecording recording, int topMethods) {
        return new RecordingSummary(
                recording.source(),
                recording.startTime(),
                recording.duration(),
                recording.events().size(),
                recording.countByType(),
                recording.threads().size(),
                recording.stacks().size(),
                recording.stacks().lookups(),
                HotMethods.top(recording, topMethods));
    }

    /** Average number of events sharing each stored stack. */
    public double dedupRatio() {
        return uniqueStacks == 0 ? 0 : (double) stackReferences / uniqueStacks;
    }
}
