// The view state lives in the URL hash, so a window and flame graph can be shared as a link:
//   #from=2&to=5&kind=lock&thread=locker-0
import type { FlameKind, Range } from "./api";

export interface ViewState {
  range: Range | null;
  kind: FlameKind;
  thread: string | null;
}

const KINDS: FlameKind[] = ["cpu", "alloc", "lock"];

export function parseHash(hash: string): ViewState {
  const p = new URLSearchParams(hash.replace(/^#/, ""));
  const from = Number(p.get("from"));
  const to = Number(p.get("to"));
  const kindParam = p.get("kind") as FlameKind | null;
  const range = p.has("from") && p.has("to") && Number.isFinite(from) && Number.isFinite(to) && to > from && from >= 0
    ? { from, to }
    : null;
  return {
    range,
    kind: kindParam && KINDS.includes(kindParam) ? kindParam : "cpu",
    thread: p.get("thread") || null,
  };
}

export function formatHash(s: ViewState): string {
  const p = new URLSearchParams();
  if (s.range) {
    p.set("from", trim(s.range.from));
    p.set("to", trim(s.range.to));
  }
  if (s.kind !== "cpu") p.set("kind", s.kind);
  if (s.thread) p.set("thread", s.thread);
  const q = p.toString();
  return q ? `#${q}` : "";
}

function trim(n: number): string {
  return String(Number(n.toFixed(2)));
}
