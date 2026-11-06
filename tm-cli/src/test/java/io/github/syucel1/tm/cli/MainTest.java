package io.github.syucel1.tm.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MainTest {

    private record Run(int code, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String demoJfr() {
        String prop = System.getProperty("tm.demoJfr");
        assumeTrue(prop != null && Files.isRegularFile(Path.of(prop)), "demo.jfr not recorded");
        return prop;
    }

    @Test
    void windowCommandPrintsAllSections() {
        Run r = run("window", demoJfr(), "--from", "1", "--to", "4", "--top", "3");
        assertEquals(0, r.code(), r.err());
        for (String section : new String[] {"Hot methods", "Allocations by site", "Lock contention", "GC:", "Threads"}) {
            assertTrue(r.out().contains(section), section + " missing:\n" + r.out());
        }
        assertTrue(r.out().contains("Window 1.0-4.0 s"), r.out());
    }

    @Test
    void usageErrorsExitWith2() {
        assertEquals(2, run().code());
        assertEquals(2, run("bogus", "x.jfr").code());
        assertEquals(2, run("window", "x.jfr", "--from").code());
        assertEquals(2, run("summary", demoJfr(), "--nope", "1").code());
        assertEquals(1, run("summary", "does-not-exist.jfr").code());
    }

    @Test
    void formatsBytesAndMillis() {
        assertEquals("512 B", Main.bytes(512));
        assertEquals("1.5 KB", Main.bytes(1536));
        assertEquals("2.0 MB", Main.bytes(2L * 1024 * 1024));
        assertEquals("1.5 ms", Main.millis(1_500_000));
    }
}
