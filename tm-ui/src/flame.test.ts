import { describe, expect, it } from "vitest";
import type { FlameNode } from "./api";
import { layout, matchedValue, maxDepth, resolvePath } from "./flame";

const n = (name: string, value: number, children: FlameNode[] = []): FlameNode => ({
  name, value, self: value - children.reduce((s, c) => s + c.value, 0), children,
});

// all(100) → main(60) → [work(45) → hot(45), io(15)] ; worker(40) → hot(40)
const tree = n("all", 100, [
  n("app.Main.main", 60, [n("app.Main.work", 45, [n("app.Main.hot", 45)]), n("java.io.read", 15)]),
  n("app.Worker.run", 40, [n("app.Main.hot", 40)]),
]);

describe("flame layout", () => {
  it("sizes children by value from the left", () => {
    const rects = layout(tree);
    const main = rects.find((r) => r.node.name === "app.Main.main")!;
    const worker = rects.find((r) => r.node.name === "app.Worker.run")!;
    expect(main.depth).toBe(1);
    expect([main.x0, main.x1]).toEqual([0, 0.6]);
    expect(worker.x0).toBeCloseTo(0.6);
    expect(worker.x1).toBeCloseTo(1);
    const io = rects.find((r) => r.node.name === "java.io.read")!;
    expect(io.x0).toBeCloseTo(0.45);
    expect(io.path).toEqual(["app.Main.main", "java.io.read"]);
    expect(maxDepth(rects)).toBe(3);
  });

  it("zooms so the focused node spans the full width with ancestors above", () => {
    const rects = layout(tree, ["app.Main.main", "app.Main.work"]);
    const ancestors = rects.filter((r) => r.ancestor).map((r) => r.node.name);
    expect(ancestors).toEqual(["all", "app.Main.main"]);
    const focus = rects.find((r) => r.node.name === "app.Main.work")!;
    expect([focus.x0, focus.x1, focus.depth]).toEqual([0, 1, 2]);
    const hot = rects.find((r) => r.node.name === "app.Main.hot")!;
    expect([hot.x0, hot.x1, hot.depth]).toEqual([0, 1, 3]);
    expect(rects.some((r) => r.node.name === "app.Worker.run")).toBe(false);
  });

  it("falls back to the deepest existing node when the focus path is stale", () => {
    expect(resolvePath(tree, ["app.Main.main", "gone"]).map((x) => x.name)).toEqual(["all", "app.Main.main"]);
    const rects = layout(tree, ["app.Main.main", "gone"]);
    expect(rects.find((r) => r.node.name === "app.Main.main")!.x1).toBe(1);
  });

  it("drops rectangles narrower than the minimum width", () => {
    const rects = layout(tree, [], 0.2);
    expect(rects.some((r) => r.node.name === "java.io.read")).toBe(false);
  });

  it("counts search matches without double-counting nested frames", () => {
    expect(matchedValue(tree, "hot")).toBe(85);
    expect(matchedValue(tree, "Main")).toBe(100); // main(60) + hot under worker(40)
    expect(matchedValue(tree, " ")).toBe(0);
  });
});
