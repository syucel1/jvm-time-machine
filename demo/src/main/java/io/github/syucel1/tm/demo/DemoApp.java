package io.github.syucel1.tm.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A small application with three deliberately planted performance problems,
 * used to produce {@code demo.jfr} for tests and demos:
 *
 * <ol>
 *   <li><b>CPU hotspot</b>: {@link #slowPrimeCount(int)} on thread {@code cpu-worker}</li>
 *   <li><b>Allocation pressure</b>: {@link #allocate()} on the main thread</li>
 *   <li><b>Lock contention</b>: {@link #contended()} on threads {@code locker-0..3}</li>
 * </ol>
 *
 * Usage: {@code java -XX:StartFlightRecording=filename=demo.jfr,settings=profile DemoApp [seconds]}
 */
public final class DemoApp {

    private static final Object LOCK = new Object();
    private static volatile long sink;

    private DemoApp() {
    }

    /** Problem 1: naive prime counting, deliberately O(n^2). */
    static long slowPrimeCount(int n) {
        long count = 0;
        for (int i = 2; i < n; i++) {
            boolean prime = true;
            for (int j = 2; j < i; j++) {
                if (i % j == 0) {
                    prime = false;
                    break;
                }
            }
            if (prime) {
                count++;
            }
        }
        return count;
    }

    /** Problem 2: lots of short-lived strings and UUIDs. */
    static void allocate() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) {
            list.add("item-" + i + UUID.randomUUID());
        }
        sink += list.size();
    }

    /** Problem 3: four threads competing for one lock held for 50 ms. */
    static void contended() {
        synchronized (LOCK) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 15;
        long end = System.currentTimeMillis() + seconds * 1000L;
        System.out.printf("DemoApp running for %d s (CPU hotspot, allocations, lock contention)%n", seconds);

        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread locker = new Thread(() -> {
                while (System.currentTimeMillis() < end && !Thread.currentThread().isInterrupted()) {
                    contended();
                }
            }, "locker-" + t);
            locker.start();
            threads.add(locker);
        }

        Thread cpu = new Thread(() -> {
            while (System.currentTimeMillis() < end) {
                sink += slowPrimeCount(20_000);
            }
        }, "cpu-worker");
        cpu.start();
        threads.add(cpu);

        while (System.currentTimeMillis() < end) {
            allocate();
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("DemoApp done (sink=" + sink + ")");
    }
}
