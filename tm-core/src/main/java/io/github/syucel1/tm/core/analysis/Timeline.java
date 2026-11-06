package io.github.syucel1.tm.core.analysis;

import io.github.syucel1.tm.core.model.JvmRecording;
import io.github.syucel1.tm.core.model.TmEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Per-second rollup of a recording: the data behind the UI's overview lanes.
 *
 * <p>Bucket {@code i} covers {@code [start + i s, start + (i + 1) s)}. Durations (GC pauses, lock
 * waits) are split across the buckets they overlap. Gauges (CPU load, heap, threads) take the
 * last value seen in the bucket and are carried forward into buckets with no new sample.
 */
public final class Timeline {

    static final String CPU_LOAD = "jdk.CPULoad";
    static final String HEAP_SUMMARY = "jdk.GCHeapSummary";
    static final String THREAD_STATS = "jdk.JavaThreadStatistics";
    static final String GC_PAUSE = "jdk.GCPhasePause";
    static final String ALLOCATION_SAMPLE = "jdk.ObjectAllocationSample";
    static final String MONITOR_ENTER = "jdk.JavaMonitorEnter";

    private static final long SECOND = 1_000_000_000L;

    /**
     * One second of the recording. Gauges are {@code -1} until the first sample arrives.
     *
     * @param index          seconds since the recording start
     * @param startNanos     bucket start, epoch nanoseconds
     * @param cpuJvm         JVM CPU load (user + system), 0–1 of the machine
     * @param cpuMachine     whole-machine CPU load, 0–1
     * @param heapUsedBytes  heap used, from the latest GC heap summary
     * @param threads        active Java threads
     * @param cpuSamples     CPU samples ({@code jdk.ExecutionSample}) in the bucket
     * @param allocatedBytes estimated bytes allocated (sum of allocation sample weights)
     * @param gcPauses       GC pause phases that started in the bucket
     * @param gcPauseNanos   GC pause time overlapping the bucket
     * @param lockWaitNanos  time threads spent blocked on monitors, overlapping the bucket
     */
    public record Bucket(
            int index,
            long startNanos,
            double cpuJvm,
            double cpuMachine,
            long heapUsedBytes,
            long threads,
            long cpuSamples,
            long allocatedBytes,
            long gcPauses,
            long gcPauseNanos,
            long lockWaitNanos) {
    }

    private final long startNanos;
    private final List<Bucket> buckets;

    private Timeline(long startNanos, List<Bucket> buckets) {
        this.startNanos = startNanos;
        this.buckets = List.copyOf(buckets);
    }

    public static Timeline of(JvmRecording recording) {
        long start = recording.startNanos();
        int n = recording.events().isEmpty()
                ? 0
                : (int) ((recording.endNanos() - start) / SECOND) + 1;

        double[] cpuJvm = filled(n, Double.NaN);
        double[] cpuMachine = filled(n, Double.NaN);
        long[] heap = filled(n);
        long[] threads = filled(n);
        long[] samples = new long[n];
        long[] alloc = new long[n];
        long[] pauses = new long[n];
        long[] pauseNanos = new long[n];
        long[] lockNanos = new long[n];

        for (TmEvent e : recording.events()) {
            int i = (int) ((e.startNanos() - start) / SECOND);
            switch (e.type()) {
                case HotMethods.EXECUTION_SAMPLE -> samples[i]++;
                case ALLOCATION_SAMPLE -> alloc[i] += longAttr(e, "weight");
                case CPU_LOAD -> {
                    cpuJvm[i] = doubleAttr(e, "jvmUser") + doubleAttr(e, "jvmSystem");
                    cpuMachine[i] = doubleAttr(e, "machineTotal");
                }
                case HEAP_SUMMARY -> heap[i] = longAttr(e, "heapUsed");
                case THREAD_STATS -> threads[i] = longAttr(e, "activeCount");
                case GC_PAUSE -> {
                    pauses[i]++;
                    spread(e, start, pauseNanos);
                }
                case MONITOR_ENTER -> spread(e, start, lockNanos);
                default -> {
                    // not part of the overview
                }
            }
        }

        List<Bucket> out = new ArrayList<>(n);
        double lastCpuJvm = -1;
        double lastCpuMachine = -1;
        long lastHeap = -1;
        long lastThreads = -1;
        for (int i = 0; i < n; i++) {
            lastCpuJvm = Double.isNaN(cpuJvm[i]) ? lastCpuJvm : cpuJvm[i];
            lastCpuMachine = Double.isNaN(cpuMachine[i]) ? lastCpuMachine : cpuMachine[i];
            lastHeap = heap[i] < 0 ? lastHeap : heap[i];
            lastThreads = threads[i] < 0 ? lastThreads : threads[i];
            out.add(new Bucket(i, start + i * SECOND, lastCpuJvm, lastCpuMachine, lastHeap, lastThreads,
                    samples[i], alloc[i], pauses[i], pauseNanos[i], lockNanos[i]));
        }
        return new Timeline(start, out);
    }

    /** All buckets, oldest first. */
    public List<Bucket> buckets() {
        return buckets;
    }

    public long startNanos() {
        return startNanos;
    }

    /** Buckets overlapping the window. */
    public List<Bucket> slice(Window window) {
        List<Bucket> out = new ArrayList<>();
        for (Bucket b : buckets) {
            if (b.startNanos() < window.toNanos() && b.startNanos() + SECOND > window.fromNanos()) {
                out.add(b);
            }
        }
        return out;
    }

    /** Adds the part of the event's duration that falls into each bucket. */
    private static void spread(TmEvent e, long start, long[] target) {
        long s = e.startNanos();
        long end = e.endNanos();
        while (s < end) {
            int i = (int) ((s - start) / SECOND);
            if (i >= target.length) {
                return;
            }
            long bucketEnd = start + (i + 1L) * SECOND;
            long part = Math.min(end, bucketEnd) - s;
            target[i] += part;
            s += part;
        }
    }

    private static long longAttr(TmEvent e, String name) {
        return e.attribute(name) instanceof Number n ? n.longValue() : 0;
    }

    private static double doubleAttr(TmEvent e, String name) {
        return e.attribute(name) instanceof Number n ? n.doubleValue() : 0;
    }

    private static double[] filled(int n, double value) {
        double[] a = new double[n];
        Arrays.fill(a, value);
        return a;
    }

    private static long[] filled(int n) {
        long[] a = new long[n];
        Arrays.fill(a, -1);
        return a;
    }
}
