package io.github.syucel1.tm.core;

import io.github.syucel1.tm.core.jfr.JfrReader;
import io.github.syucel1.tm.core.model.JvmRecording;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads {@code demo.jfr} (written by {@code :demo:recordDemo}) once per test run.
 * Required on CI; tests are skipped locally if it has not been recorded.
 */
final class DemoJfr {

    /** Fully qualified name of the demo app class, for matching frames. */
    static final String DEMO = "io.github.syucel1.tm.demo.DemoApp";

    private static JvmRecording cached;

    private DemoJfr() {
    }

    static synchronized JvmRecording load() throws Exception {
        if (cached == null) {
            String prop = System.getProperty("tm.demoJfr");
            Path file = prop == null ? null : Path.of(prop);
            boolean present = file != null && Files.isRegularFile(file);
            if (System.getenv("CI") != null) {
                assertTrue(present, "demo.jfr must exist in CI: " + prop);
            }
            assumeTrue(present, "demo.jfr not found; run ./gradlew :demo:recordDemo");
            cached = JfrReader.create().read(file);
        }
        return cached;
    }
}
