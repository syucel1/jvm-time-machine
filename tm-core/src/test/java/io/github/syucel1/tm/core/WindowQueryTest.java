package io.github.syucel1.tm.core;

import io.github.syucel1.tm.core.analysis.Window;
import io.github.syucel1.tm.core.analysis.WindowQuery;
import io.github.syucel1.tm.core.model.JvmRecording;
import io.github.syucel1.tm.core.model.TmEvent;

import java.util.List;

import org.junit.jupiter.api.Test;

import static io.github.syucel1.tm.core.DemoJfr.DEMO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 exit gate: window queries answer hot-method, allocation, lock and GC questions for any
 * window of demo.jfr.
 */
class WindowQueryTest {

    @Test
    void wholeRecordingFindsAllPlantedProblems() throws Exception {
        JvmRecording rec = DemoJfr.load();
        WindowQuery.Result r = WindowQuery.run(rec, Window.all(rec), 5);

        assertEquals(rec.events().size(), r.eventCount());
        assertTrue(r.hotMethods().stream().anyMatch(m -> m.method().equals(DEMO + ".slowPrimeCount")),
                "hot methods: " + r.hotMethods());
        assertTrue(r.allocationsBySite().stream().anyMatch(a -> a.name().startsWith(DEMO + ".")),
                "allocation sites: " + r.allocationsBySite());
        assertEquals(DEMO + ".contended", r.contention().get(0).method(), "contention: " + r.contention());
        assertEquals("java.lang.Object", r.contention().get(0).monitorClass());
        assertTrue(r.gc().count() > 0 && r.gc().pauseNanos() > 0, "gc: " + r.gc());
        assertTrue(r.threads().stream().anyMatch(t -> t.name().equals("cpu-worker") && t.cpuSamples() > 0));
    }

    @Test
    void anyWindowOfTheDemoShowsTheCpuHotspot() throws Exception {
        JvmRecording rec = DemoJfr.load();
        double seconds = rec.duration().toNanos() / 1e9;
        for (double from = 1; from + 2 <= seconds - 1; from += 2) {
            WindowQuery.Result r = WindowQuery.run(rec, Window.ofOffsets(rec, from, from + 2), 3);
            assertTrue(r.cpuSamples() > 0, "samples in window " + from);
            assertTrue(r.hotMethods().stream().anyMatch(m -> m.method().equals(DEMO + ".slowPrimeCount")),
                    "window " + from + "s: " + r.hotMethods());
        }
    }

    @Test
    void windowOnlyCountsEventsStartingInside() throws Exception {
        JvmRecording rec = DemoJfr.load();
        Window w = Window.ofOffsets(rec, 2.0, 3.0);
        List<TmEvent> events = w.events(rec);
        assertTrue(events.stream().allMatch(e -> w.contains(e.startNanos())));
        long expected = rec.events().stream().filter(e -> w.contains(e.startNanos())).count();
        assertEquals(expected, events.size());
        WindowQuery.Result r = WindowQuery.run(rec, w, 5);
        assertEquals(expected, r.eventCount());
        assertEquals(2.0, r.fromSeconds(), 1e-9);
        assertEquals(3.0, r.toSeconds(), 1e-9);
    }

    @Test
    void offsetsAreClampedToTheRecording() throws Exception {
        JvmRecording rec = DemoJfr.load();
        Window w = Window.ofOffsets(rec, -5.0, 1e6);
        assertEquals(rec.startNanos(), w.fromNanos());
        assertEquals(rec.endNanos() + 1, w.toNanos());
        assertEquals(0, Window.ofOffsets(rec, null, null).fromOffsetSeconds(rec), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> new Window(10, 5));
    }
}
