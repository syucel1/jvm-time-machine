import { describe, expect, it } from "vitest";
import type { ThreadLanesResult, WindowResult } from "./api";
import { findings } from "./insights";

const D = "io.github.syucel1.tm.demo.DemoApp";

// Shaped like /api/window on demo.jfr for 2–5 s.
const win: WindowResult = {
  fromSeconds: 2, toSeconds: 5, eventCount: 11000, cpuSamples: 266,
  hotMethods: [{ method: `${D}.slowPrimeCount`, samples: 175, percent: 65.8 }],
  allocationsBySite: [{ name: `${D}.allocate`, bytes: 1.7 * 1024 ** 3, samples: 900, percent: 99.9 }],
  allocationsByClass: [{ name: "[B", bytes: 1.4 * 1024 ** 3, samples: 700, percent: 83 }],
  contention: [{ method: `${D}.contended`, monitorClass: "java.lang.Object", count: 8, totalNanos: 6.24e9, maxNanos: 1.48e9 }],
  gc: { count: 77, pauseNanos: 730e6, maxPauseNanos: 24.7e6, longest: [] },
  threads: [
    { name: "cpu-worker", cpuSamples: 175, allocatedBytes: 0, lockWaitNanos: 0 },
    { name: "main", cpuSamples: 90, allocatedBytes: 1.7 * 1024 ** 3, lockWaitNanos: 0 },
  ],
};

const lane = (name: string, blocked: number[]) => ({
  name, virtual: false, cpuSamples: blocked.map(() => 0), blockedNanos: blocked, waitingNanos: blocked.map(() => 0),
  allocatedBytes: blocked.map(() => 0), totalCpuSamples: 0, totalBlockedNanos: 0, totalWaitingNanos: 0, totalAllocatedBytes: 0,
});
const lanes: ThreadLanesResult = {
  fromSeconds: 0, toSeconds: 10, bucketSeconds: 1, firstIndex: 0, buckets: 10, totalThreads: 3,
  threads: [lane("locker-0", [0, 0, 5e8, 5e8, 5e8, 0, 0, 0, 0, 0]), lane("locker-1", [0, 0, 0, 7e8, 0, 0, 0, 0, 0, 0]),
    lane("locker-2", [9e8, 0, 0, 0, 0, 0, 0, 0, 0, 9e8])],
};

describe("findings", () => {
  const f = findings(win, lanes, { from: 2, to: 5 });
  const byId = Object.fromEntries(f.map((x) => [x.id, x]));

  it("explains all three planted problems and the GC", () => {
    expect(f.map((x) => x.id)).toEqual(["cpu", "alloc", "lock", "gc"]);
    expect(byId.cpu.detail).toContain("DemoApp.slowPrimeCount");
    expect(byId.cpu.severity).toBe("high");
    expect(byId.cpu.thread).toBe("cpu-worker");
    expect(byId.alloc.detail).toContain("DemoApp.allocate");
    expect(byId.alloc.detail).toContain("byte[]");
    expect(byId.alloc.headline).toBe("581 MB/s");
    expect(byId.lock.detail).toContain("8 waits to lock an Object in DemoApp.contended across 2 threads");
    expect(byId.lock.flame).toBe("lock");
    expect(byId.gc.flame).toBeNull();
  });

  it("reports GC pause time as a share of the window", () => {
    expect(byId.gc.headline).toBe("24.3%");
    expect(byId.gc.severity).toBe("high");
  });

  it("says when there is nothing to show", () => {
    const empty: WindowResult = { ...win, cpuSamples: 0, hotMethods: [], allocationsBySite: [], allocationsByClass: [], contention: [],
      gc: { count: 0, pauseNanos: 0, maxPauseNanos: 0, longest: [] }, threads: [] };
    expect(findings(empty, null, { from: 0, to: 1 }).every((x) => x.severity === "none")).toBe(true);
  });
});
