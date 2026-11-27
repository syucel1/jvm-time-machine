package io.github.syucel1.tm.core.analysis;

import io.github.syucel1.tm.core.model.JvmRecording;
import io.github.syucel1.tm.core.model.ThreadInfo;
import io.github.syucel1.tm.core.model.TmEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-thread, per-second activity: the data behind the UI's thread lanes.
 *
 * <p>For each thread, bucket {@code i} of a lane covers second {@code firstIndex + i} of the
 * recording (the same buckets as {@link Timeline}). Running is counted in CPU samples; blocked
 * ({@code synchronized}) and waiting (park, sleep, {@code Object.wait}) time is in nanoseconds and
 * is split across the buckets it overlaps. JFR only records waits above its threshold (20 ms in
 * the {@code profile} settings), so short waits are not counted.
 */
public final class ThreadLanes {

    static final String THREAD_PARK = "jdk.ThreadPark";
    static final String THREAD_SLEEP = "jdk.ThreadSleep";
    static final String JAVA_MONITOR_WAIT = "jdk.JavaMonitorWait";

    private static final long SECOND = 1_000_000_000L;

    /**
     * One thread's lane.
     *
     * @param name           thread name
     * @param virtual        {@code true} for virtual threads
     * @param cpuSamples     CPU samples per bucket
     * @param blockedNanos   time blocked entering a monitor, per bucket
     * @param waitingNanos   time parked, sleeping or in {@code Object.wait}, per bucket
     * @param allocatedBytes estimated bytes allocated per bucket
     * @param totalCpuSamples      sum of {@code cpuSamples}
     * @param totalBlockedNanos    sum of {@code blockedNanos}
     * @param totalWaitingNanos    sum of {@code waitingNanos}
     * @param totalAllocatedBytes  sum of {@code allocatedBytes}
     */
    public record Lane(String name, boolean virtual, long[] cpuSamples, long[] blockedNanos, long[] waitingNanos,
                       long[] allocatedBytes, long totalCpuSamples, long totalBlockedNanos,
                       long totalWaitingNanos, long totalAllocatedBytes) {
    }

    /**
     * @param fromSeconds   window start, seconds since recording start
     * @param toSeconds     window end, seconds since recording start
     * @param bucketSeconds always 1
     * @param firstIndex    recording second of each lane's first bucket
     * @param buckets       buckets per lane
     * @param totalThreads  threads with any activity in the window, before {@code limit}
     * @param threads       busiest threads first (CPU samples, then blocked, waiting, allocated)
     */
    public record Result(double fromSeconds, double toSeconds, int bucketSeconds, int firstIndex, int buckets,
                         int totalThreads, List<Lane> threads) {
    }

    private ThreadLanes() {
    }

    public static Result of(JvmRecording recording, Window window, int limit) {
        long start = recording.startNanos();
        int first = (int) Math.max(0, (window.fromNanos() - start) / SECOND);
        int last = window.durationNanos() == 0
                ? first - 1
                : (int) ((window.toNanos() - 1 - start) / SECOND);
        int n = Math.max(0, last - first + 1);
        long laneStart = start + first * SECOND;

        Map<Long, long[][]> byThread = new HashMap<>();
        // Waits that started before the window can still overlap it, so look back up to 60 s.
        for (TmEvent e : recording.eventsStartingBetween(laneStart - 60 * SECOND, window.toNanos())) {
            if (!e.hasThread()) {
                continue;
            }
            int slot = switch (e.type()) {
                case HotMethods.EXECUTION_SAMPLE -> 0;
                case Timeline.MONITOR_ENTER -> 1;
                case THREAD_PARK, THREAD_SLEEP, JAVA_MONITOR_WAIT -> 2;
                case Timeline.ALLOCATION_SAMPLE -> 3;
                default -> -1;
            };
            if (slot < 0) {
                continue;
            }
            if (slot == 1 || slot == 2) {
                if (e.endNanos() <= window.fromNanos() || e.startNanos() >= window.toNanos()) {
                    continue;
                }
                spread(e, Math.max(laneStart, window.fromNanos()), Math.min(window.toNanos(), laneStart + n * SECOND),
                        laneStart, lane(byThread, e, n)[slot]);
            } else if (window.contains(e.startNanos())) {
                int i = (int) ((e.startNanos() - laneStart) / SECOND);
                if (i >= 0 && i < n) {
                    lane(byThread, e, n)[slot][i] += slot == 0
                            ? 1
                            : e.attribute("weight") instanceof Number w ? w.longValue() : 0;
                }
            }
        }

        List<Lane> lanes = byThread.entrySet().stream()
                .map(en -> {
                    ThreadInfo t = recording.threads().get(en.getKey());
                    String name = t == null ? "thread-" + en.getKey() : t.name();
                    long[][] a = en.getValue();
                    return new Lane(name, t != null && t.virtual(), a[0], a[1], a[2], a[3],
                            sum(a[0]), sum(a[1]), sum(a[2]), sum(a[3]));
                })
                .filter(l -> l.totalCpuSamples() + l.totalBlockedNanos() + l.totalWaitingNanos()
                        + l.totalAllocatedBytes() > 0)
                .sorted(Comparator.comparingLong(Lane::totalCpuSamples).reversed()
                        .thenComparing(Comparator.comparingLong(Lane::totalBlockedNanos).reversed())
                        .thenComparing(Comparator.comparingLong(Lane::totalWaitingNanos).reversed())
                        .thenComparing(Comparator.comparingLong(Lane::totalAllocatedBytes).reversed())
                        .thenComparing(Lane::name))
                .toList();
        return new Result(window.fromOffsetSeconds(recording), window.toOffsetSeconds(recording), 1, first, n,
                lanes.size(), lanes.stream().limit(limit).toList());
    }

    private static long[][] lane(Map<Long, long[][]> byThread, TmEvent e, int n) {
        return byThread.computeIfAbsent(e.threadKey(), k -> new long[4][n]);
    }

    /** Adds the part of {@code e} inside {@code [clipFrom, clipTo)} to the buckets it overlaps. */
    private static void spread(TmEvent e, long clipFrom, long clipTo, long laneStart, long[] target) {
        long s = Math.max(e.startNanos(), clipFrom);
        long end = Math.min(e.endNanos(), clipTo);
        while (s < end) {
            int i = (int) ((s - laneStart) / SECOND);
            if (i >= target.length) {
                return;
            }
            long bucketEnd = laneStart + (i + 1L) * SECOND;
            long part = Math.min(end, bucketEnd) - s;
            target[i] += part;
            s += part;
        }
    }

    private static long sum(long[] a) {
        long s = 0;
        for (long v : a) {
            s += v;
        }
        return s;
    }
}
