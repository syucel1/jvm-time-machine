// Number and name formatting shared by the panels, tooltips and canvases.

const KB = 1024;

export function bytes(b: number): string {
  if (!Number.isFinite(b) || b < 0) return "–";
  if (b < KB) return `${Math.round(b)} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let v = b;
  let u = -1;
  while (v >= KB && u < units.length - 1) {
    v /= KB;
    u++;
  }
  return `${v >= 100 ? v.toFixed(0) : v.toFixed(1)} ${units[u]}`;
}

/** A duration given in nanoseconds, e.g. `850 µs`, `12.3 ms`, `4.20 s`. */
export function nanos(n: number): string {
  if (!Number.isFinite(n) || n < 0) return "–";
  if (n === 0) return "0 ms";
  if (n < 1e6) return `${(n / 1e3).toFixed(0)} µs`;
  if (n < 1e9) return `${(n / 1e6).toFixed(n < 1e7 ? 2 : 1)} ms`;
  return `${(n / 1e9).toFixed(2)} s`;
}

export function percent(p: number, digits = 1): string {
  if (!Number.isFinite(p)) return "–";
  return `${p.toFixed(digits)}%`;
}

/** Seconds since the recording start, e.g. `12.5 s`. */
export function seconds(s: number): string {
  if (!Number.isFinite(s)) return "–";
  return `${s.toFixed(s >= 100 ? 0 : 1)} s`;
}

export function count(n: number): string {
  return Math.round(n).toLocaleString("en-US");
}

/** A flame graph value in its unit. */
export function flameValue(v: number, unit: "samples" | "bytes" | "nanos"): string {
  switch (unit) {
    case "bytes":
      return bytes(v);
    case "nanos":
      return nanos(v);
    default:
      return `${count(v)} sample${v === 1 ? "" : "s"}`;
  }
}

const PLATFORM = ["java.", "javax.", "jdk.", "sun.", "com.sun."];

/** True for JDK and platform frames; mirrors `Frames.isPlatform` in tm-core. */
export function isPlatform(qualifiedName: string): boolean {
  return PLATFORM.some((p) => qualifiedName.startsWith(p));
}

/** Hides JVM-generated lambda and method-handle class addresses: `Foo$$Lambda.0x7f…` → `Foo$$Lambda`. */
export function tidy(qualifiedName: string): string {
  return qualifiedName.replace(/\.0x[0-9a-f]+(?=\.|$)/gi, "").replace(/\/0x[0-9a-f]+/gi, "");
}

/** `com.acme.Foo$Bar.baz` → `Foo$Bar.baz`: the class's simple name and the method. */
export function shortMethod(qualifiedName: string): string {
  const name = tidy(qualifiedName);
  const dot = name.lastIndexOf(".");
  if (dot < 0) return name;
  const cls = name.slice(0, dot);
  const method = name.slice(dot + 1);
  const simple = cls.slice(cls.lastIndexOf(".") + 1);
  return `${simple}.${method}`;
}

/** `com.acme.Foo` → `com.acme`. */
export function packageOf(qualifiedName: string): string {
  const name = tidy(qualifiedName);
  const dot = name.lastIndexOf(".");
  const cls = dot < 0 ? name : name.slice(0, dot);
  const pkgDot = cls.lastIndexOf(".");
  return pkgDot < 0 ? "" : cls.slice(0, pkgDot);
}

const PRIMITIVES: Record<string, string> = {
  B: "byte", C: "char", D: "double", F: "float", I: "int", J: "long", S: "short", Z: "boolean",
};

/** JVM class names as Java source names: `[B` → `byte[]`, `[Ljava.lang.String;` → `java.lang.String[]`. */
export function prettyClass(name: string): string {
  let dims = 0;
  while (name[dims] === "[") dims++;
  if (dims === 0) return name;
  const rest = name.slice(dims);
  const base = rest.startsWith("L") && rest.endsWith(";") ? rest.slice(1, -1) : PRIMITIVES[rest] ?? rest;
  return base + "[]".repeat(dims);
}

/** `java.lang.String` → `String`, after {@link prettyClass}. */
export function simpleClass(name: string): string {
  const p = prettyClass(name);
  return p.slice(p.lastIndexOf(".") + 1);
}

/** "a lock" / "an Object": the indefinite article for a word. */
export function article(word: string): string {
  return /^[aeiou]/i.test(word) ? "an" : "a";
}
