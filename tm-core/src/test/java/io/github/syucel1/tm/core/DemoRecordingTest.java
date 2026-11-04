package io.github.syucel1.tm.core;

import io.github.syucel1.tm.core.analysis.HotMethods;
import io.github.syucel1.tm.core.analysis.RecordingSummary;
import io.github.syucel1.tm.core.jfr.JfrReader;
import io.github.syucel1.tm.core.model.JvmRecording;
import io.github.syucel1.tm.core.model.StackTraceData;
import io.github.syucel1.tm.core.model.ThreadInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 2 exit gate: {@code demo.jfr}, recorded from {@code DemoApp} by {@code :demo:recordDemo},
 * is parsed into the event model and the three planted problems are visible in it.
 */
class DemoRecordingTest {

    private static final String DEMO = "io.github.syucel1.tm.demo.DemoApp";

    private static JvmRecording cached;

    private static JvmRecording demo() throws Exception {
        if (cached == null) {
            String prop = System.getProperty("tm.demoJfr");
            Path file = prop == null ? null : Path.of(prop);
            boolean present = file != null && Files.isRegularFile(file);
            if (System.getenv("CI") != null) {
                assertTrue(present, "demo.jfr must exist in CI: " + prop);
            }
            assumeTrue(present, "demo.jfr not found; run ./gradlew :demo:recordDemo");
            cached = JfrReader.create().read(file);
        }
        return cached;
    }

    @Test
    void parsesTheExpectedEventTypes() throws Exception {
        Map<String, Long> counts = demo().countByType();
        assertTrue(demo().events().size() > 1_000, "events: " + demo().events().size());
        for (String type : List.of("jdk.ExecutionSample", "jdk.ObjectAllocationSample",
                "jdk.JavaMonitorEnter", "jdk.GarbageCollection", "jdk.ThreadSleep")) {
            assertTrue(counts.getOrDefault(type, 0L) > 0, "missing " + type);
        }
    }

    @Test
    void deduplicatesStacks() throws Exception {
        RecordingSummary summary = RecordingSummary.of(demo(), 10);
        assertTrue(summary.uniqueStacks() > 0);
        assertTrue(summary.dedupRatio() > 2.0, "dedup ratio " + summary.dedupRatio());
    }

    @Test
    void knowsTheDemoThreads() throws Exception {
        Set<String> names = demo().threads().values().stream().map(ThreadInfo::name).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of("main", "cpu-worker", "locker-0", "locker-3")), "threads: " + names);
    }

    @Test
    void findsTheCpuHotspot() throws Exception {
        List<HotMethods.MethodCount> top = HotMethods.top(demo(), 3);
        assertTrue(top.stream().anyMatch(m -> m.method().equals(DEMO + ".slowPrimeCount")),
                "slowPrimeCount not in top 3: " + top);
    }

    @Test
    void findsTheLockContention() throws Exception {
        JvmRecording rec = demo();
        long contended = rec.eventsOfType("jdk.JavaMonitorEnter")
                .filter(e -> e.durationNanos() > 10_000_000L)
                .map(rec::stackOf)
                .filter(s -> s != null && s.contains(DEMO + ".contended"))
                .count();
        assertTrue(contended > 0, "no contended monitor enters in DemoApp.contended");
    }

    @Test
    void findsTheAllocationSite() throws Exception {
        JvmRecording rec = demo();
        long fromAllocate = rec.eventsOfType("jdk.ObjectAllocationSample")
                .map(rec::stackOf)
                .filter(s -> s != null && s.contains(DEMO + ".allocate"))
                .count();
        assertTrue(fromAllocate > 0, "no allocation samples from DemoApp.allocate");
    }

    @Test
    void stacksResolveForStackBearingEvents() throws Exception {
        JvmRecording rec = demo();
        StackTraceData any = rec.eventsOfType("jdk.ExecutionSample").findFirst().map(rec::stackOf).orElse(null);
        assertTrue(any != null && any.depth() > 0);
    }
}
