import { describe, expect, it } from "vitest";
import { formatHash, parseHash } from "./hash";

describe("url hash", () => {
  it("round-trips a window, kind and thread", () => {
    const h = formatHash({ range: { from: 2, to: 5.25 }, kind: "lock", thread: "locker-0" });
    expect(h).toBe("#from=2&to=5.25&kind=lock&thread=locker-0");
    expect(parseHash(h)).toEqual({ range: { from: 2, to: 5.25 }, kind: "lock", thread: "locker-0" });
  });

  it("omits defaults", () => {
    expect(formatHash({ range: null, kind: "cpu", thread: null })).toBe("");
    expect(parseHash("")).toEqual({ range: null, kind: "cpu", thread: null });
  });

  it("ignores bad values", () => {
    expect(parseHash("#from=5&to=2&kind=heap").range).toBeNull();
    expect(parseHash("#from=abc&to=2").range).toBeNull();
    expect(parseHash("#kind=heap").kind).toBe("cpu");
  });
});
