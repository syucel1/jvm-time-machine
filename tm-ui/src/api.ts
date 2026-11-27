// Types and fetchers for the tm-cli JSON API (see TmServer.java). Times are seconds since the
// recording start; durations from the server are nanoseconds.

export interface RecordingInfo {
  source: string;
  startEpochMillis: number;
  durationSeconds: number;
  eventCount: number;
  uniqueStacks: number;
  eventTypes: Record<string, number>;
  threads: string[];
}

export interface Bucket {
  index: number;
  startNanos: number;
  /** JVM CPU load 0–1 of the machine, or -1 before the first sample. */
  cpuJvm: number;
  cpuMachine: number;
  heapUsedBytes: number;
  threads: number;
  cpuSamples: number;
  allocatedBytes: number;
  gcPauses: number;
  gcPauseNanos: number;
  lockWaitNanos: number;
}

export interface Overview {
  fromSeconds: number;
  toSeconds: number;
  bucketSeconds: number;
  buckets: Bucket[];
}

export interface MethodCount { method: string; samples: number; percent: number }
export interface Allocation { name: string; bytes: number; samples: number; percent: number }
export interface Contention { method: string; monitorClass: string; count: number; totalNanos: number; maxNanos: number }
export interface Gc { offsetSeconds: number; name: string; cause: string; durationNanos: number; pauseNanos: number; longestPauseNanos: number }
export interface ThreadActivity { name: string; cpuSamples: number; allocatedBytes: number; lockWaitNanos: number }

export interface WindowResult {
  fromSeconds: number;
  toSeconds: number;
  eventCount: number;
  cpuSamples: number;
  hotMethods: MethodCount[];
  allocationsByClass: Allocation[];
  allocationsBySite: Allocation[];
  contention: Contention[];
  gc: { count: number; pauseNanos: number; maxPauseNanos: number; longest: Gc[] };
  threads: ThreadActivity[];
}

export type FlameKind = "cpu" | "alloc" | "lock";

export interface FlameNode { name: string; value: number; self: number; children: FlameNode[] }

export interface FlameGraphResult {
  kind: FlameKind;
  unit: "samples" | "bytes" | "nanos";
  fromSeconds: number;
  toSeconds: number;
  thread: string | null;
  events: number;
  nodes: number;
  root: FlameNode;
}

export interface ThreadLane {
  name: string;
  virtual: boolean;
  cpuSamples: number[];
  blockedNanos: number[];
  waitingNanos: number[];
  allocatedBytes: number[];
  totalCpuSamples: number;
  totalBlockedNanos: number;
  totalWaitingNanos: number;
  totalAllocatedBytes: number;
}

export interface ThreadLanesResult {
  fromSeconds: number;
  toSeconds: number;
  bucketSeconds: number;
  firstIndex: number;
  buckets: number;
  totalThreads: number;
  threads: ThreadLane[];
}

/** A time range in seconds since the recording start. */
export interface Range { from: number; to: number }

export class ApiError extends Error {}

async function get<T>(path: string, params: Record<string, string | number | null | undefined> = {}, signal?: AbortSignal): Promise<T> {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== null && v !== undefined && v !== "") q.set(k, String(v));
  }
  const url = q.size ? `${path}?${q}` : path;
  const res = await fetch(url, { signal, headers: { Accept: "application/json" } });
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    throw new ApiError(body && typeof body.error === "string" ? body.error : `${res.status} ${res.statusText}`);
  }
  return body as T;
}

const secs = (s: number) => Number(s.toFixed(3));

export const api = {
  recording: (signal?: AbortSignal) => get<RecordingInfo>("/api/recording", {}, signal),
  overview: (signal?: AbortSignal) => get<Overview>("/api/overview", {}, signal),
  threads: (signal?: AbortSignal) => get<ThreadLanesResult>("/api/threads", { limit: 40 }, signal),
  window: (r: Range, signal?: AbortSignal) =>
    get<WindowResult>("/api/window", { from: secs(r.from), to: secs(r.to), top: 10 }, signal),
  flame: (r: Range, kind: FlameKind, thread: string | null, signal?: AbortSignal) =>
    get<FlameGraphResult>("/api/flamegraph", { from: secs(r.from), to: secs(r.to), kind, thread, minPercent: 0.1 }, signal),
};
