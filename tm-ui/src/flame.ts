// Pure layout for the flame graph: which rectangle each call-tree node occupies.
import type { FlameNode } from "./api";

export interface FlameRect {
  node: FlameNode;
  /** Names from the root's first child down to this node. */
  path: string[];
  depth: number;
  /** Horizontal extent as fractions of the full width, 0–1. */
  x0: number;
  x1: number;
  /** True for ancestors of the focused node, drawn full-width above it. */
  ancestor: boolean;
}

/** The nodes along `path` from the root; stops early where the path no longer exists. */
export function resolvePath(root: FlameNode, path: string[]): FlameNode[] {
  const out: FlameNode[] = [root];
  let node = root;
  for (const name of path) {
    const next = node.children.find((c) => c.name === name);
    if (!next) break;
    out.push(next);
    node = next;
  }
  return out;
}

/**
 * Lays out the tree with the node at `focusPath` spanning the full width. Its ancestors stack
 * above it at full width (so you can click back up), and its descendants are sized by value.
 * Rectangles narrower than `minWidth` (a fraction) are dropped, along with their subtrees.
 */
export function layout(root: FlameNode, focusPath: string[] = [], minWidth = 0): FlameRect[] {
  const chain = resolvePath(root, focusPath);
  const rects: FlameRect[] = [];
  chain.forEach((node, depth) => {
    rects.push({ node, path: focusPath.slice(0, depth), depth, x0: 0, x1: 1, ancestor: depth < chain.length - 1 });
  });
  const focus = chain[chain.length - 1];
  const focusDepth = chain.length - 1;
  const basePath = focusPath.slice(0, focusDepth);
  if (focus.value <= 0) return rects;

  const walk = (node: FlameNode, path: string[], depth: number, x0: number, width: number) => {
    let x = x0;
    for (const child of node.children) {
      const w = (child.value / node.value) * width;
      if (w >= minWidth && w > 0) {
        const childPath = [...path, child.name];
        rects.push({ node: child, path: childPath, depth, x0: x, x1: x + w, ancestor: false });
        walk(child, childPath, depth + 1, x, w);
      }
      x += w;
    }
  };
  walk(focus, basePath, focusDepth + 1, 0, 1);
  return rects;
}

/** The deepest level in a layout, for sizing the canvas. */
export function maxDepth(rects: FlameRect[]): number {
  return rects.reduce((m, r) => Math.max(m, r.depth), 0);
}

/** Total value of nodes whose name contains `query` (case-insensitive), not double-counting nested matches. */
export function matchedValue(root: FlameNode, query: string): number {
  const q = query.trim().toLowerCase();
  if (!q) return 0;
  const visit = (n: FlameNode): number =>
    n.name.toLowerCase().includes(q) ? n.value : n.children.reduce((s, c) => s + visit(c), 0);
  return root.children.reduce((s, c) => s + visit(c), 0);
}
