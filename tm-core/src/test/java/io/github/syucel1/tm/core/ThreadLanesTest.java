package io.github.syucel1.tm.core;

import io.github.syucel1.tm.core.analysis.ThreadLanes;
import io.github.syucel1.tm.core.analysis.Timeline;
import io.github.syucel1.tm.core.analysis.Window;
import io.github.syucel1.tm.core.model.JvmRecording;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 4: thread lanes show who runs, who is blocked and who waits in demo.jfr. */
class ThreadLanesTest {

    @Test
    void lanesShowTheCpuWorkerRunningAndTheLockersBlocked() throws Exception {
        JvmRecording rec = DemoJfr.load();
        ThreadLanes.Result r = ThreadLanes.of(rec, Window.all(rec), 50);

        assertEquals(Timeline.of(rec).buckets().size(), r.buckets(), "same buckets as the timeline");
        assertEquals(0, r.firstIndex());
        ThreadLanes.Lane first = r.threads().get(0);
        assertEquals("cpu-worker", first.name(), "busiest thread first");
        assertTrue(first.totalBlockedNanos() == 0, "cpu-worker never blocks");

        List<ThreadLanes.Lane> lockers = r.threads().stream().filter(l -> l.name().startsWith("locker-")).toList();
        assertEquals(4, lockers.size());
        for (ThreadLanes.Lane l : lockers) {
            assertTrue(l.totalBlockedNanos() > 1_000_000_000L, l.name() + " blocked for over a second");
            assertTrue(l.totalWaitingNanos() > 0, l.name() + " sleeps while holding the lock");
        }
        assertTrue(r.threads().stream().anyMatch(l -> l.name().equals("main") && l.totalAllocatedBytes() > 0),
                "main allocates");
    }

    @Test
    void blockedTimeIsSplitAcrossBucketsAndNeverExceedsOneSecond() throws Exception {
        JvmRecording rec = DemoJfr.load();
        ThreadLanes.Result r = ThreadLanes.of(rec, Window.all(rec), 50);
        for (ThreadLanes.Lane l : r.threads()) {
            assertEquals(r.buckets(), l.cpuSamples().length);
            assertEquals(l.totalBlockedNanos(), Arrays.stream(l.blockedNanos()).sum());
            for (int i = 0; i < r.buckets(); i++) {
                assertTrue(l.blockedNanos()[i] <= 1_000_000_000L, l.name() + " bucket " + i);
                assertTrue(l.waitingNanos()[i] <= 1_000_000_000L, l.name() + " bucket " + i);
            }
        }
    }

    @Test
    void windowSelectsBucketsAndClipsWaits() throws Exception {
        JvmRecording rec = DemoJfr.load();
        ThreadLanes.Result r = ThreadLanes.of(rec, Window.ofOffsets(rec, 2.5, 4.0), 50);
        assertEquals(2, r.firstIndex());
        assertEquals(2, r.buckets(), "seconds 2 and 3");
        for (ThreadLanes.Lane l : r.threads()) {
            assertTrue(l.blockedNanos()[0] <= 500_000_000L, "only the half second inside the window counts");
        }
    }

    @Test
    void limitKeepsTheBusiestThreads() throws Exception {
        JvmRecording rec = DemoJfr.load();
        ThreadLanes.Result r = ThreadLanes.of(rec, Window.all(rec), 2);
        assertEquals(2, r.threads().size());
        assertTrue(r.totalThreads() >= 6, "cpu-worker, main and 4 lockers: " + r.totalThreads());
    }
}
