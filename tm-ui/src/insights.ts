// "What stands out" in a window: plain-language findings built from the window query and
// the thread lanes, each pointing at the view that shows the evidence.
import type { FlameKind, Range, ThreadLanesResult, WindowResult } from "./api";
import { article, bytes, nanos, percent, shortMethod, simpleClass } from "./format";

export type Severity = "high" | "medium" | "low" | "none";

export interface Finding {
  id: "cpu" | "alloc" | "lock" | "gc";
  title: string;
  /** The one number that matters, e.g. `65%` or `7.1 s`. */
  headline: string;
  headlineLabel: string;
  detail: string;
  severity: Severity;
  /** Flame graph that shows the evidence, if any. */
  flame: FlameKind | null;
  /** Thread to filter the flame graph to, if one thread dominates. */
  thread: string | null;
}

const SECOND = 1e9;

/** Sums a per-second lane over the buckets that overlap the range. */
function laneTotal(lanes: ThreadLanesResult, values: (l: ThreadLanesResult["threads"][number]) => number[], r: Range) {
  return lanes.threads.map((l) => {
    const v = values(l);
    let s = 0;
    for (let i = 0; i < v.length; i++) {
      const sec = lanes.firstIndex + i;
      if (sec + 1 > r.from && sec < r.to) s += v[i];
    }
    return { name: l.name, total: s };
  });
}

export function findings(w: WindowResult, lanes: ThreadLanesResult | null, r: Range): Finding[] {
  const duration = Math.max(r.to - r.from, 1e-9);
  const out: Finding[] = [];

  // CPU hotspot: one method as the top frame of a large share of samples.
  const hot = w.hotMethods[0];
  if (hot && w.cpuSamples > 0) {
    const busiest = [...w.threads].sort((a, b) => b.cpuSamples - a.cpuSamples)[0];
    const share = busiest && w.cpuSamples ? (100 * busiest.cpuSamples) / w.cpuSamples : 0;
    out.push({
      id: "cpu",
      title: "CPU hotspot",
      headline: percent(hot.percent, 0),
      headlineLabel: "of CPU samples",
      detail: `${shortMethod(hot.method)} is on top of the stack in ${percent(hot.percent, 0)} of ${w.cpuSamples.toLocaleString("en-US")} samples`
        + (busiest ? `; ${busiest.name} took ${percent(share, 0)} of all samples.` : "."),
      severity: hot.percent >= 40 ? "high" : hot.percent >= 15 ? "medium" : "low",
      flame: "cpu",
      thread: share >= 50 && busiest ? busiest.name : null,
    });
  } else {
    out.push(none("cpu", "CPU hotspot", "No CPU samples in this window.", "cpu"));
  }

  // Allocation pressure: bytes per second and the site responsible.
  const site = w.allocationsBySite[0];
  const totalAlloc = w.allocationsBySite.length && site && site.percent > 0 ? (site.bytes * 100) / site.percent : 0;
  if (site && totalAlloc > 0) {
    const rate = totalAlloc / duration;
    const cls = w.allocationsByClass[0];
    out.push({
      id: "alloc",
      title: "Allocation pressure",
      headline: `${bytes(rate)}/s`,
      headlineLabel: "allocated",
      detail: `${shortMethod(site.name)} allocates ${percent(site.percent, 0)} of ${bytes(totalAlloc)}`
        + (cls ? `, mostly ${simpleClass(cls.name)} (${percent(cls.percent, 0)}).` : "."),
      severity: rate >= 256 * 1024 * 1024 ? "high" : rate >= 32 * 1024 * 1024 ? "medium" : "low",
      flame: "alloc",
      thread: null,
    });
  } else {
    out.push(none("alloc", "Allocation pressure", "No allocation samples in this window.", "alloc"));
  }

  // Lock contention: time threads spent blocked on synchronized.
  const top = w.contention[0];
  const blocked = w.contention.reduce((s, c) => s + c.totalNanos, 0);
  if (top && blocked > 0) {
    const blockedThreads = lanes ? laneTotal(lanes, (l) => l.blockedNanos, r).filter((t) => t.total > 0).length : 0;
    const perSecond = blocked / duration / SECOND;
    out.push({
      id: "lock",
      title: "Lock contention",
      headline: nanos(blocked),
      headlineLabel: "blocked",
      detail: `${top.count.toLocaleString("en-US")} wait${top.count === 1 ? "" : "s"} to lock ${article(simpleClass(top.monitorClass))} ${simpleClass(top.monitorClass)} in ${shortMethod(top.method)}`
        + (blockedThreads ? ` across ${blockedThreads} thread${blockedThreads === 1 ? "" : "s"}` : "")
        + `; on average ${perSecond.toFixed(1)} threads were blocked at any moment.`,
      severity: perSecond >= 1 ? "high" : perSecond >= 0.1 ? "medium" : "low",
      flame: "lock",
      thread: null,
    });
  } else {
    out.push(none("lock", "Lock contention", "No thread blocked on a monitor in this window.", "lock"));
  }

  // GC: pause time as a share of the window.
  const gc = w.gc;
  if (gc.count > 0) {
    const overhead = (100 * gc.pauseNanos) / (duration * SECOND);
    out.push({
      id: "gc",
      title: "Garbage collection",
      headline: percent(overhead, overhead < 1 ? 2 : 1),
      headlineLabel: "of time paused",
      detail: `${gc.count} collection${gc.count === 1 ? "" : "s"}, ${nanos(gc.pauseNanos)} paused in total; longest pause ${nanos(gc.maxPauseNanos)}.`,
      severity: overhead >= 10 || gc.maxPauseNanos >= 500e6 ? "high" : overhead >= 2 || gc.maxPauseNanos >= 100e6 ? "medium" : "low",
      flame: null,
      thread: null,
    });
  } else {
    out.push(none("gc", "Garbage collection", "No collections in this window.", null));
  }
  return out;
}

function none(id: Finding["id"], title: string, detail: string, flame: FlameKind | null): Finding {
  return { id, title, headline: "–", headlineLabel: "", detail, severity: "none", flame, thread: null };
}
