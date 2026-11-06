package io.github.syucel1.tm.core;

import io.github.syucel1.tm.core.analysis.HotMethods;
import io.github.syucel1.tm.core.analysis.Timeline;
import io.github.syucel1.tm.core.analysis.Window;
import io.github.syucel1.tm.core.model.JvmRecording;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-second overview of demo.jfr (Phase 3). */
class TimelineTest {

    @Test
    void hasOneBucketPerSecond() throws Exception {
        JvmRecording rec = DemoJfr.load();
        List<Timeline.Bucket> buckets = Timeline.of(rec).buckets();
        long expected = rec.duration().toNanos() / 1_000_000_000L + 1;
        assertEquals(expected, buckets.size());
        for (int i = 0; i < buckets.size(); i++) {
            assertEquals(i, buckets.get(i).index());
            assertEquals(rec.startNanos() + i * 1_000_000_000L, buckets.get(i).startNanos());
        }
    }

    @Test
    void cpuSamplesAddUpToTheRecording() throws Exception {
        JvmRecording rec = DemoJfr.load();
        long inBuckets = Timeline.of(rec).buckets().stream().mapToLong(Timeline.Bucket::cpuSamples).sum();
        assertEquals(rec.eventsOfType(HotMethods.EXECUTION_SAMPLE).count(), inBuckets);
    }

    @Test
    void lockWaitsAreSpreadAcrossBucketsWithoutLoss() throws Exception {
        JvmRecording rec = DemoJfr.load();
        long total = rec.eventsOfType("jdk.JavaMonitorEnter").mapToLong(e -> e.durationNanos()).sum();
        long inBuckets = Timeline.of(rec).buckets().stream().mapToLong(Timeline.Bucket::lockWaitNanos).sum();
        assertTrue(total > 0, "demo has lock contention");
        assertEquals(total, inBuckets);
    }

    @Test
    void showsThePlantedProblemsWhileTheDemoRuns() throws Exception {
        JvmRecording rec = DemoJfr.load();
        List<Timeline.Bucket> buckets = Timeline.of(rec).buckets();
        // Ignore the first and last second (JVM startup and shutdown).
        List<Timeline.Bucket> middle = buckets.subList(1, Math.max(1, buckets.size() - 1));
        assertTrue(middle.stream().allMatch(b -> b.cpuSamples() > 0), "CPU samples every second");
        assertTrue(middle.stream().filter(b -> b.allocatedBytes() > 0).count() >= middle.size() / 2,
                "allocation in most seconds");
        assertTrue(middle.stream().anyMatch(b -> b.lockWaitNanos() > 0), "lock waits");
        assertTrue(buckets.stream().anyMatch(b -> b.gcPauses() > 0 && b.gcPauseNanos() > 0), "GC pauses");
        Timeline.Bucket last = buckets.get(buckets.size() - 1);
        assertTrue(last.heapUsedBytes() > 0, "heap gauge carried forward");
        assertTrue(last.threads() > 0, "thread gauge carried forward");
        assertTrue(buckets.stream().anyMatch(b -> b.cpuJvm() > 0 && b.cpuJvm() <= 1.0), "CPU load 0..1");
    }

    @Test
    void sliceReturnsOnlyOverlappingBuckets() throws Exception {
        JvmRecording rec = DemoJfr.load();
        Timeline t = Timeline.of(rec);
        List<Timeline.Bucket> slice = t.slice(Window.ofOffsets(rec, 2.0, 4.0));
        assertEquals(List.of(2, 3), slice.stream().map(Timeline.Bucket::index).toList());
    }
}
