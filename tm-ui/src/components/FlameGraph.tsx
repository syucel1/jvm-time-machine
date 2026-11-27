// Interactive flame graph on Canvas, drawn as an icicle (callers on top, callees below).
// Width is weight (samples, bytes or blocked time). Application frames are blue, JDK frames
// gray, search matches yellow. Click a frame to zoom into it; click an ancestor bar to zoom out.
import { useEffect, useMemo, useRef, useState } from "react";
import type { FlameGraphResult, FlameNode } from "../api";
import { layout, matchedValue, maxDepth, resolvePath, type FlameRect } from "../flame";
import { flameValue, isPlatform, percent, shortMethod, tidy } from "../format";
import { prepareCanvas, useWidth, type Palette } from "../theme";

const ROW = 20;
const PAD = 1;

interface Props {
  data: FlameGraphResult;
  focus: string[];
  onFocus: (path: string[]) => void;
  search: string;
  palette: Palette;
}

export function FlameGraph({ data, focus, onFocus, search, palette }: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const width = useWidth(wrapRef);
  const [hover, setHover] = useState<{ rect: FlameRect; x: number; y: number } | null>(null);

  const rects = useMemo(() => layout(data.root, focus, width > 0 ? 0.5 / width : 0), [data, focus, width]);
  const depth = maxDepth(rects);
  const height = (depth + 1) * ROW + 4;
  const q = search.trim().toLowerCase();
  const total = data.root.value;

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || width === 0) return;
    const ctx = prepareCanvas(canvas, width, height);
    ctx.font = "12px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";
    ctx.textBaseline = "middle";
    for (const r of rects) {
      const x = r.x0 * width;
      const w = (r.x1 - r.x0) * width;
      const y = r.depth * ROW;
      const isRoot = r.depth === 0;
      const match = q && !isRoot && r.node.name.toLowerCase().includes(q);
      const app = !isRoot && !isPlatform(r.node.name);
      const fill = match ? palette.flameMatch : app ? palette.flameApp : palette.flamePlatform;
      const ink = match ? palette.flameMatchText : app ? palette.flameAppText : palette.flamePlatformText;
      ctx.globalAlpha = r.ancestor ? 0.6 : q && !match ? 0.55 : 1;
      ctx.fillStyle = fill;
      ctx.beginPath();
      ctx.roundRect(x + PAD / 2, y + PAD, Math.max(0.5, w - PAD), ROW - 2 * PAD, w > 6 ? 3 : 0);
      ctx.fill();
      if (hover && hover.rect.node === r.node && hover.rect.depth === r.depth) {
        ctx.globalAlpha = 1;
        ctx.strokeStyle = palette.text;
        ctx.lineWidth = 1.5;
        ctx.stroke();
      }
      ctx.globalAlpha = 1;
      if (w > 34) {
        const label = isRoot
          ? `all · ${flameValue(total, data.unit)}`
          : fit(ctx, [tidy(r.node.name), shortMethod(r.node.name)], w - 8);
        if (label) {
          ctx.fillStyle = ink;
          ctx.globalAlpha = r.ancestor ? 0.8 : 1;
          ctx.fillText(label, x + 5, y + ROW / 2 + 0.5);
          ctx.globalAlpha = 1;
        }
      }
    }
  });

  function rectAt(x: number, y: number): FlameRect | null {
    const d = Math.floor(y / ROW);
    const fx = x / width;
    return rects.find((r) => r.depth === d && fx >= r.x0 && fx < r.x1) ?? null;
  }

  function local(e: React.MouseEvent) {
    const b = canvasRef.current!.getBoundingClientRect();
    return { x: e.clientX - b.left, y: e.clientY - b.top };
  }

  if (total <= 0) {
    return <div className="empty">No {data.kind === "cpu" ? "CPU samples" : data.kind === "alloc" ? "allocation samples" : "lock waits"} with stacks in this window{data.thread ? ` on ${data.thread}` : ""}.</div>;
  }

  const matched = q ? matchedValue(data.root, q) : 0;
  const focusNode = resolvePath(data.root, focus).at(-1)!;

  return (
    <>
      <div className="canvas-wrap" ref={wrapRef}>
        <canvas
          ref={canvasRef}
          role="img"
          aria-label={`Flame graph of ${flameValue(total, data.unit)}; its heaviest path ends in ${shortMethod(widest(focusNode))}.`}
          style={{ cursor: hover ? "pointer" : "default" }}
          onMouseMove={(e) => {
            const { x, y } = local(e);
            const r = rectAt(x, y);
            setHover(r ? { rect: r, x, y } : null);
          }}
          onMouseLeave={() => setHover(null)}
          onClick={(e) => {
            const { x, y } = local(e);
            const r = rectAt(x, y);
            if (!r) return;
            onFocus(r.depth === 0 ? [] : r.path);
          }}
        />
        {hover && <FlameTooltip rect={hover.rect} x={hover.x} y={hover.y} width={width} data={data} />}
      </div>
      <div className="flame-status">
        <span>{data.events.toLocaleString("en-US")} {data.kind === "cpu" ? "sample" : data.kind === "alloc" ? "allocation sample" : "lock wait"}{data.events === 1 ? "" : "s"}</span>
        <span>{data.nodes.toLocaleString("en-US")} frames (under 0.1% hidden)</span>
        {q && <span>“{search.trim()}” matches {percent((100 * matched) / total)} of {flameValue(total, data.unit)}</span>}
        <span className="legend">
          <span><i style={{ background: palette.flameApp }} />Application</span>
          <span><i style={{ background: palette.flamePlatform }} />JDK</span>
          {q && <span><i style={{ background: palette.flameMatch }} />Match</span>}
        </span>
      </div>
    </>
  );
}

function FlameTooltip({ rect, x, y, width, data }: { rect: FlameRect; x: number; y: number; width: number; data: FlameGraphResult }) {
  const n = rect.node;
  const total = data.root.value;
  const left = x + 16 + 320 > width ? Math.max(0, x - 336) : x + 16;
  return (
    <div className="tooltip" style={{ left, top: y + 16 }}>
      <div className="t mono">{rect.depth === 0 ? "all" : tidy(n.name)}</div>
      <div className="row"><span>Total</span><span>{flameValue(n.value, data.unit)} · {percent((100 * n.value) / total)}</span></div>
      <div className="row"><span>Self</span><span>{flameValue(n.self, data.unit)} · {percent((100 * n.self) / total)}</span></div>
      {n.children.length > 0 && <div className="row"><span>Callees</span><span>{n.children.length}</span></div>}
      <div style={{ color: "var(--muted)", marginTop: 4 }}>{rect.ancestor ? "Click to zoom out to here" : rect.depth === 0 ? "Click to reset zoom" : "Click to zoom in"}</div>
    </div>
  );
}

/** The longest of the candidate labels that fits, or the shortest one cut with an ellipsis. */
function fit(ctx: CanvasRenderingContext2D, candidates: string[], max: number): string {
  for (const c of candidates) if (ctx.measureText(c).width <= max) return c;
  const s = candidates[candidates.length - 1];
  let lo = 0;
  let hi = s.length;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (ctx.measureText(s.slice(0, mid) + "…").width <= max) lo = mid;
    else hi = mid - 1;
  }
  return lo >= 2 ? s.slice(0, lo) + "…" : "";
}

function widest(node: FlameNode): string {
  let n = node;
  while (n.children.length) n = n.children[0];
  return n.name;
}
