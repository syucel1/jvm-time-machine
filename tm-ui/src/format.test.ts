import { describe, expect, it } from "vitest";
import { article, bytes, flameValue, isPlatform, nanos, packageOf, prettyClass, shortMethod, simpleClass, tidy } from "./format";

describe("format", () => {
  it("formats bytes with binary units", () => {
    expect(bytes(512)).toBe("512 B");
    expect(bytes(1536)).toBe("1.5 KB");
    expect(bytes(5.0 * 1024 ** 3)).toBe("5.0 GB");
    expect(bytes(-1)).toBe("–");
  });

  it("formats nanosecond durations", () => {
    expect(nanos(0)).toBe("0 ms");
    expect(nanos(850_000)).toBe("850 µs");
    expect(nanos(2_500_000)).toBe("2.50 ms");
    expect(nanos(30_100_000)).toBe("30.1 ms");
    expect(nanos(7_183_000_000)).toBe("7.18 s");
  });

  it("formats flame values by unit", () => {
    expect(flameValue(1, "samples")).toBe("1 sample");
    expect(flameValue(960, "samples")).toBe("960 samples");
    expect(flameValue(2048, "bytes")).toBe("2.0 KB");
    expect(flameValue(1.48e9, "nanos")).toBe("1.48 s");
  });

  it("tells JDK frames from application frames like tm-core", () => {
    expect(isPlatform("java.lang.Thread.run")).toBe(true);
    expect(isPlatform("sun.security.provider.NativePRNG.engineNextBytes")).toBe(true);
    expect(isPlatform("io.github.syucel1.tm.demo.DemoApp.slowPrimeCount")).toBe(false);
  });

  it("shortens and tidies method names", () => {
    expect(shortMethod("io.github.syucel1.tm.demo.DemoApp.slowPrimeCount")).toBe("DemoApp.slowPrimeCount");
    expect(packageOf("io.github.syucel1.tm.demo.DemoApp.slowPrimeCount")).toBe("io.github.syucel1.tm.demo");
    expect(tidy("com.x.Demo$$Lambda.0x00007f3a04009c00.run")).toBe("com.x.Demo$$Lambda.run");
    expect(shortMethod("com.x.Demo$$Lambda.0x00007f3a04009c00.run")).toBe("Demo$$Lambda.run");
    expect(tidy("java.lang.invoke.LambdaForm$MH/0x0000000801001400.invoke")).toBe("java.lang.invoke.LambdaForm$MH.invoke");
  });

  it("prints JVM array class names as Java names", () => {
    expect(prettyClass("[B")).toBe("byte[]");
    expect(prettyClass("[[I")).toBe("int[][]");
    expect(prettyClass("[Ljava.lang.String;")).toBe("java.lang.String[]");
    expect(prettyClass("java.lang.Object")).toBe("java.lang.Object");
    expect(simpleClass("[Ljava.lang.String;")).toBe("String[]");
    expect(article("Object")).toBe("an");
    expect(article("ReentrantLock")).toBe("a");
  });
});
