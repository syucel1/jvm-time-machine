// Overview lanes (CPU, heap, allocation, GC, blocked) and per-thread lanes on one shared time
// axis, with a scrubber: drag to select a window, drag its edges to resize, drag inside to move
// it, click a second to select just that second, double-click for the whole recording.
import { useEffect, useMemo, useRef, useState } from "react";
import type { Bucket, Overview, Range, ThreadLane, ThreadLanesResult } from "../api";
import { bytes, nanos, percent, seconds } from "../format";
import { prepareCanvas, useWidth, type Palette } from "../theme";

interface Props {
  overview: Overview;
  lanes: ThreadLanesResult | null;
  duration: number;
  range: Range;
  onRange: (r: Range) => void;
  thread: string | null;
  onThread: (t: string | null) => void;
  palette: Palette;
}

const GUTTER = 156;
const RIGHT = 14;
const AXIS = 22;
const LANE_H = 44;
const LANE_GAP = 8;
const SECTION = 26;
const ROW_H = 16;
const ROW_GAP = 3;
const EDGE = 7;
const SNAP = 0.1;

type LaneKind = "line" | "bars";

interface MetricLane {
  id: string;
  label: string;
  unitLabel: string;
  kind: LaneKind;
  value: (b: Bucket) => number | null;
  reference?: (b: Bucket) => number | null;
  max: number;
  format: (v: number) => string;
}

function metricLanes(buckets: Bucket[]): MetricLane[] {
  const maxOf = (f: (b: Bucket) => number) => buckets.reduce((m, b) => Math.max(m, f(b)), 0);
  const heapMax = maxOf((b) => b.heapUsedBytes);
  return [
    {
      id: "cpu", label: "CPU", unitLabel: "% of machine", kind: "line",
      value: (b) => (b.cpuJvm < 0 ? null : b.cpuJvm * 100),
      reference: (b) => (b.cpuMachine < 0 ? null : b.cpuMachine * 100),
      max: 100, format: (v) => percent(v, 0),
    },
    {
      id: "heap", label: "Heap used", unitLabel: "bytes", kind: "line",
      value: (b) => (b.heapUsedBytes < 0 ? null : b.heapUsedBytes),
      max: heapMax, format: bytes,
    },
    {
      id: "alloc", label: "Allocation", unitLabel: "bytes / s", kind: "bars",
      value: (b) => b.allocatedBytes, max: maxOf((b) => b.allocatedBytes), format: (v) => `${bytes(v)}/s`,
    },
    {
      id: "gc", label: "GC pauses", unitLabel: "paused / s", kind: "bars",
      value: (b) => b.gcPauseNanos, max: maxOf((b) => b.gcPauseNanos), format: nanos,
    },
    {
      id: "blocked", label: "Blocked", unitLabel: "threads on locks", kind: "bars",
      value: (b) => b.lockWaitNanos / 1e9, max: maxOf((b) => b.lockWaitNanos / 1e9), format: (v) => v.toFixed(2),
    },
  ];
}

interface Hover { x: number; y: number; t: number; laneIndex: number | null; thread: ThreadLane | null }

type Drag =
  | { mode: "new"; anchor: number; moved: boolean; startX: number }
  | { mode: "move"; offset: number; width: number; moved: boolean; startX: number }
  | { mode: "from" | "to"; moved: boolean; startX: number };

export function Timeline({ overview, lanes, duration, range, onRange, thread, onThread, palette }: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const width = useWidth(wrapRef);
  const [draft, setDraft] = useState<Range | null>(null);
  const [hover, setHover] = useState<Hover | null>(null);
  const drag = useRef<Drag | null>(null);

  const buckets = overview.buckets;
  const metrics = useMemo(() => metricLanes(buckets), [buckets]);
  const threadRows = lanes?.threads ?? [];
  const maxRunning = useMemo(
    () => Math.max(1, ...threadRows.flatMap((l) => l.cpuSamples)),
    [threadRows],
  );

  const plotW = Math.max(10, width - GUTTER - RIGHT);
  const metricsH = metrics.length * (LANE_H + LANE_GAP);
  const threadsTop = AXIS + metricsH + SECTION;
  const height = threadsTop + threadRows.length * (ROW_H + ROW_GAP) + 6;
  const span = Math.max(duration, 1e-6);
  const xOf = (t: number) => GUTTER + (t / span) * plotW;
  const tOf = (x: number) => Math.min(span, Math.max(0, ((x - GUTTER) / plotW) * span));
  const shown = draft ?? range;

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || width === 0) return;
    const ctx = prepareCanvas(canvas, width, height);
    draw(ctx);
  });

  function draw(ctx: CanvasRenderingContext2D) {
    const p = palette;
    ctx.font = "12px -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif";
    ctx.textBaseline = "middle";

    // time axis: ticks at a "nice" step
    const step = niceStep(span / Math.max(2, plotW / 90));
    ctx.fillStyle = p.muted;
    ctx.strokeStyle = p.grid;
    ctx.lineWidth = 1;
    ctx.textAlign = "center";
    for (let t = 0; t <= span + 1e-9; t += step) {
      const x = Math.round(xOf(t)) + 0.5;
      ctx.beginPath();
      ctx.moveTo(x, AXIS - 4);
      ctx.lineTo(x, height);
      ctx.stroke();
      ctx.fillText(step < 1 ? `${t.toFixed(1)}s` : `${Math.round(t)}s`, Math.min(Math.max(x, GUTTER + 12), width - 16), 9);
    }

    // metric lanes
    metrics.forEach((lane, i) => {
      const top = AXIS + i * (LANE_H + LANE_GAP);
      ctx.textAlign = "left";
      ctx.fillStyle = p.text;
      ctx.font = "600 12px -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif";
      ctx.fillText(lane.label, 8, top + 12);
      ctx.font = "11px -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif";
      ctx.fillStyle = p.muted;
      ctx.fillText(lane.max > 0 ? `max ${lane.format(lane.max)}` : lane.unitLabel, 8, top + 28);
      // baseline
      ctx.strokeStyle = p.border;
      ctx.beginPath();
      ctx.moveTo(GUTTER, top + LANE_H + 0.5);
      ctx.lineTo(GUTTER + plotW, top + LANE_H + 0.5);
      ctx.stroke();
      if (lane.max <= 0) return;
      const yOf = (v: number) => top + LANE_H - (Math.min(v, lane.max) / lane.max) * (LANE_H - 4);
      if (lane.kind === "bars") drawBars(ctx, lane, top, yOf);
      else drawLine(ctx, lane, top, yOf);
    });

    // thread lanes
    const secTop = AXIS + metricsH;
    ctx.textAlign = "left";
    ctx.fillStyle = p.text;
    ctx.font = "600 12px -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif";
    ctx.fillText("Threads", 8, secTop + 13);
    if (lanes && lanes.totalThreads > threadRows.length) {
      ctx.font = "11px -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif";
      ctx.fillStyle = p.muted;
      ctx.fillText(`busiest ${threadRows.length} of ${lanes.totalThreads}`, 64, secTop + 13);
    }
    ctx.font = "12px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";
    threadRows.forEach((l, r) => {
      const top = threadsTop + r * (ROW_H + ROW_GAP);
      const selected = l.name === thread;
      if (selected) {
        ctx.fillStyle = p.accentSoft;
        ctx.fillRect(0, top - 1, width, ROW_H + 2);
      }
      ctx.fillStyle = selected ? p.accent : p.muted;
      ctx.fillText(truncate(ctx, l.name, GUTTER - 16), 8, top + ROW_H / 2);
      ctx.fillStyle = p.surface2;
      ctx.fillRect(GUTTER, top, plotW, ROW_H);
      for (let i = 0; i < l.cpuSamples.length; i++) {
        const sec = (lanes!.firstIndex + i);
        const x0 = xOf(sec);
        const x1 = xOf(Math.min(sec + 1, span));
        const gap = x1 - x0 > 4 ? 1 : 0;
        const w = Math.max(1, x1 - x0 - gap);
        let run = l.cpuSamples[i] / maxRunning;
        let blk = Math.min(1, l.blockedNanos[i] / 1e9);
        let wait = Math.min(1, l.waitingNanos[i] / 1e9);
        const sum = run + blk + wait;
        if (sum > 1) {
          run /= sum; blk /= sum; wait /= sum;
        }
        let y = top + ROW_H;
        for (const [frac, color] of [[run, p.series1], [blk, p.series2], [wait, p.series3]] as [number, string][]) {
          if (frac <= 0) continue;
          const h = Math.max(1, frac * ROW_H);
          ctx.fillStyle = color;
          ctx.fillRect(x0, y - h, w, h);
          y -= h;
        }
      }
    });

    // selection: dim outside, accent edges
    const sx0 = xOf(shown.from);
    const sx1 = xOf(shown.to);
    const full = shown.from <= 0 && shown.to >= span;
    if (!full) {
      ctx.fillStyle = p.dim;
      ctx.fillRect(GUTTER, AXIS - 4, Math.max(0, sx0 - GUTTER), height - AXIS + 4);
      ctx.fillRect(sx1, AXIS - 4, Math.max(0, GUTTER + plotW - sx1), height - AXIS + 4);
      ctx.strokeStyle = p.accent;
      ctx.lineWidth = 2;
      for (const x of [sx0, sx1]) {
        ctx.beginPath();
        ctx.moveTo(x, AXIS - 4);
        ctx.lineTo(x, height);
        ctx.stroke();
        ctx.fillStyle = p.accent;
        roundRect(ctx, x - 3, AXIS - 6, 6, 12, 2);
        ctx.fill();
      }
    }

    // hover crosshair
    if (hover && !drag.current) {
      const x = Math.round(xOf(hover.t)) + 0.5;
      ctx.strokeStyle = p.muted;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(x, AXIS - 4);
      ctx.lineTo(x, height);
      ctx.stroke();
    }
  }

  function bucketX(i: number): [number, number] {
    return [xOf(i), xOf(Math.min(i + 1, span))];
  }

  function drawBars(ctx: CanvasRenderingContext2D, lane: MetricLane, top: number, yOf: (v: number) => number) {
    ctx.fillStyle = palette.series1;
    for (const b of buckets) {
      const v = lane.value(b);
      if (v === null || v <= 0) continue;
      const [x0, x1] = bucketX(b.index);
      const gap = x1 - x0 > 6 ? 2 : 0;
      const w = Math.max(1, x1 - x0 - gap);
      const y = Math.min(yOf(v), top + LANE_H - 1);
      const h = top + LANE_H - y;
      roundRect(ctx, x0 + gap / 2, y, w, h, w > 8 ? Math.min(4, h) : 0, true);
      ctx.fill();
    }
  }

  function drawLine(ctx: CanvasRenderingContext2D, lane: MetricLane, top: number, yOf: (v: number) => number) {
    const series = (f: (b: Bucket) => number | null) =>
      buckets.flatMap((b) => {
        const v = f(b);
        if (v === null) return [];
        const [x0, x1] = bucketX(b.index);
        return [[(x0 + x1) / 2, yOf(v)] as [number, number]];
      });
    if (lane.reference) {
      const ref = series(lane.reference);
      ctx.strokeStyle = palette.reference;
      ctx.lineWidth = 1.5;
      polyline(ctx, ref);
    }
    const pts = series(lane.value);
    if (pts.length === 0) return;
    ctx.globalAlpha = 0.14;
    ctx.fillStyle = palette.series1;
    ctx.beginPath();
    ctx.moveTo(pts[0][0], top + LANE_H);
    pts.forEach(([x, y]) => ctx.lineTo(x, y));
    ctx.lineTo(pts[pts.length - 1][0], top + LANE_H);
    ctx.closePath();
    ctx.fill();
    ctx.globalAlpha = 1;
    ctx.strokeStyle = palette.series1;
    ctx.lineWidth = 2;
    polyline(ctx, pts);
  }

  // ------------------------------------------------------------------ pointer handling

  function local(e: React.PointerEvent | React.MouseEvent) {
    const rect = canvasRef.current!.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  }

  function threadAt(y: number): ThreadLane | null {
    if (y < threadsTop) return null;
    const r = Math.floor((y - threadsTop) / (ROW_H + ROW_GAP));
    return threadRows[r] ?? null;
  }

  function laneAt(y: number): number | null {
    if (y < AXIS || y >= AXIS + metricsH) return null;
    return Math.floor((y - AXIS) / (LANE_H + LANE_GAP));
  }

  const snap = (t: number) => Math.round(t / SNAP) * SNAP;
  const clampRange = (a: number, b: number): Range => {
    const from = Math.max(0, Math.min(a, b));
    const to = Math.min(span, Math.max(a, b));
    return { from: snap(from), to: Math.min(span, snap(to)) };
  };

  function onPointerDown(e: React.PointerEvent) {
    const { x } = local(e);
    if (x < GUTTER - 4) return;
    (e.target as Element).setPointerCapture(e.pointerId);
    const t = tOf(x);
    const sx0 = xOf(range.from);
    const sx1 = xOf(range.to);
    const full = range.from <= 0 && range.to >= span;
    if (!full && Math.abs(x - sx0) <= EDGE) drag.current = { mode: "from", moved: false, startX: x };
    else if (!full && Math.abs(x - sx1) <= EDGE) drag.current = { mode: "to", moved: false, startX: x };
    else if (!full && x > sx0 && x < sx1) drag.current = { mode: "move", offset: t - range.from, width: range.to - range.from, moved: false, startX: x };
    else drag.current = { mode: "new", anchor: t, moved: false, startX: x };
  }

  function onPointerMove(e: React.PointerEvent) {
    const { x, y } = local(e);
    const t = tOf(x);
    const d = drag.current;
    if (d) {
      if (Math.abs(x - d.startX) > 3) d.moved = true;
      if (!d.moved) return;
      const base = draft ?? range;
      if (d.mode === "new") setDraft(clampRange(d.anchor, t));
      else if (d.mode === "from") setDraft(clampRange(t, base.to));
      else if (d.mode === "to") setDraft(clampRange(base.from, t));
      else if (d.mode === "move") {
        const from = Math.max(0, Math.min(span - d.width, t - d.offset));
        setDraft({ from: snap(from), to: Math.min(span, snap(from + d.width)) });
      }
      setHover(null);
      return;
    }
    if (x < GUTTER || x > GUTTER + plotW) {
      setHover(null);
      return;
    }
    setHover({ x, y, t, laneIndex: laneAt(y), thread: threadAt(y) });
  }

  function onPointerUp(e: React.PointerEvent) {
    const d = drag.current;
    drag.current = null;
    if (!d) return;
    if (d.moved && draft) {
      if (draft.to - draft.from >= SNAP) onRange(draft);
      setDraft(null);
      return;
    }
    setDraft(null);
    const { x, y } = local(e);
    const row = threadAt(y);
    if (row) {
      onThread(row.name === thread ? null : row.name);
      return;
    }
    if (e.detail >= 2) return; // the dblclick handler resets
    const sec = Math.floor(tOf(x));
    onRange({ from: sec, to: Math.min(span, sec + 1) });
  }

  const cursor = (() => {
    if (!hover) return "crosshair";
    if (hover.thread) return "pointer";
    const full = range.from <= 0 && range.to >= span;
    const hx = xOf(hover.t);
    if (!full && (Math.abs(hx - xOf(range.from)) <= EDGE || Math.abs(hx - xOf(range.to)) <= EDGE)) return "ew-resize";
    if (!full && hx > xOf(range.from) && hx < xOf(range.to)) return "grab";
    return "crosshair";
  })();

  return (
    <div className="canvas-wrap" ref={wrapRef}>
      <canvas
        ref={canvasRef}
        role="img"
        aria-label={`Timeline of ${seconds(duration)}: CPU, heap, allocation, GC pauses, blocked threads and ${threadRows.length} thread lanes. Selected ${seconds(range.from)} to ${seconds(range.to)}.`}
        style={{ cursor }}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerLeave={() => setHover(null)}
        onDoubleClick={() => onRange({ from: 0, to: span })}
      />
      {hover && <TimelineTooltip hover={hover} buckets={buckets} metrics={metrics} lanes={lanes} width={width} palette={palette} />}
    </div>
  );
}

function TimelineTooltip({ hover, buckets, metrics, lanes, width, palette }: {
  hover: Hover; buckets: Bucket[]; metrics: MetricLane[]; lanes: ThreadLanesResult | null; width: number; palette: Palette;
}) {
  const sec = Math.floor(hover.t);
  const b = buckets.find((x) => x.index === sec);
  const left = hover.x + 16 + 240 > width ? hover.x - 256 : hover.x + 16;
  const style = { left: Math.max(0, left), top: hover.y + 14 };
  if (hover.thread && lanes) {
    const i = sec - lanes.firstIndex;
    const l = hover.thread;
    return (
      <div className="tooltip" style={style}>
        <div className="t">{l.name}{l.virtual ? " (virtual)" : ""} · {sec}–{sec + 1} s</div>
        <Row color={palette.series1} label="Running" value={`${l.cpuSamples[i] ?? 0} CPU samples`} />
        <Row color={palette.series2} label="Blocked" value={nanos(l.blockedNanos[i] ?? 0)} />
        <Row color={palette.series3} label="Waiting" value={nanos(l.waitingNanos[i] ?? 0)} />
        <Row label="Allocated" value={bytes(l.allocatedBytes[i] ?? 0)} />
        <div style={{ color: "var(--muted)", marginTop: 4 }}>Click to filter the flame graph to this thread</div>
      </div>
    );
  }
  if (!b) return null;
  return (
    <div className="tooltip" style={style}>
      <div className="t">{sec}–{sec + 1} s</div>
      <Row color={palette.series1} label="CPU (JVM)" value={b.cpuJvm < 0 ? "–" : percent(b.cpuJvm * 100, 0)} bold={hover.laneIndex === 0} />
      <Row color={palette.reference} label="CPU (machine)" value={b.cpuMachine < 0 ? "–" : percent(b.cpuMachine * 100, 0)} bold={hover.laneIndex === 0} />
      {metrics.slice(1).map((m, i) => {
        const v = m.value(b);
        return <Row key={m.id} label={m.label} value={v === null ? "–" : m.format(v)} bold={hover.laneIndex === i + 1} />;
      })}
      <Row label="CPU samples" value={String(b.cpuSamples)} />
      <Row label="GC pauses" value={String(b.gcPauses)} />
      <div style={{ color: "var(--muted)", marginTop: 4 }}>Click to select this second · drag to select a window</div>
    </div>
  );
}

function Row({ label, value, color, bold }: { label: string; value: string; color?: string; bold?: boolean }) {
  return (
    <div className="row" style={bold ? { fontWeight: 600 } : undefined}>
      <span>{color && <i className="sw" style={{ background: color }} />}{label}</span>
      <span>{value}</span>
    </div>
  );
}

// ------------------------------------------------------------------ canvas helpers

function niceStep(raw: number): number {
  const steps = [0.1, 0.2, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600];
  return steps.find((s) => s >= raw) ?? 3600;
}

function polyline(ctx: CanvasRenderingContext2D, pts: [number, number][]) {
  if (pts.length === 0) return;
  ctx.beginPath();
  ctx.lineJoin = "round";
  ctx.moveTo(pts[0][0], pts[0][1]);
  for (const [x, y] of pts.slice(1)) ctx.lineTo(x, y);
  if (pts.length === 1) ctx.lineTo(pts[0][0] + 0.1, pts[0][1]);
  ctx.stroke();
}

/** Rounded rectangle path; with `topOnly` only the data end (top) is rounded. */
function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number, topOnly = false) {
  ctx.beginPath();
  if (r <= 0) {
    ctx.rect(x, y, w, h);
    return;
  }
  ctx.roundRect(x, y, w, h, topOnly ? [r, r, 0, 0] : r);
}

function truncate(ctx: CanvasRenderingContext2D, s: string, max: number): string {
  if (ctx.measureText(s).width <= max) return s;
  let lo = 0;
  let hi = s.length;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (ctx.measureText(s.slice(0, mid) + "…").width <= max) lo = mid;
    else hi = mid - 1;
  }
  return s.slice(0, lo) + "…";
}
