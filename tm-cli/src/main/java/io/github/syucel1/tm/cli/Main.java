package io.github.syucel1.tm.cli;

import io.github.syucel1.tm.core.analysis.HotMethods;
import io.github.syucel1.tm.core.analysis.RecordingSummary;
import io.github.syucel1.tm.core.jfr.JfrReader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * {@code tm-cli} entry point.
 *
 * <pre>
 * tm-cli summary &lt;file.jfr&gt; [--top N]
 * </pre>
 */
public final class Main {

    private static final String USAGE = """
            Usage: tm-cli <command> [options]

            Commands:
              summary <file.jfr> [--top N]   Events, threads, stack dedup and hot methods of a recording
              help                           Show this help
              version                        Show the version
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            out.print(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        try {
            return switch (args[0]) {
                case "summary" -> summary(args, out, err);
                case "version", "--version" -> {
                    String v = Main.class.getPackage().getImplementationVersion();
                    out.println("tm-cli " + (v == null ? "dev" : v));
                    yield 0;
                }
                default -> {
                    err.println("Unknown command: " + args[0]);
                    err.print(USAGE);
                    yield 2;
                }
            };
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static int summary(String[] args, PrintStream out, PrintStream err) throws IOException {
        Path file = null;
        int top = 10;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--top") && i + 1 < args.length) {
                top = Integer.parseInt(args[++i]);
            } else if (file == null) {
                file = Path.of(args[i]);
            } else {
                err.println("Unexpected argument: " + args[i]);
                return 2;
            }
        }
        if (file == null) {
            err.println("summary: missing <file.jfr>");
            return 2;
        }
        if (!Files.isRegularFile(file)) {
            err.println("summary: no such file: " + file);
            return 1;
        }
        long t0 = System.nanoTime();
        RecordingSummary s = RecordingSummary.of(JfrReader.create().read(file), top);
        long parseMs = (System.nanoTime() - t0) / 1_000_000;
        print(s, top, parseMs, out);
        return 0;
    }

    private static void print(RecordingSummary s, int top, long parseMs, PrintStream out) {
        Locale l = Locale.ROOT;
        out.printf(l, "Recording   %s%n", s.source());
        out.printf(l, "Start       %s%n", s.start());
        out.printf(l, "Duration    %.1f s%n", s.duration().toMillis() / 1000.0);
        out.printf(l, "Events      %,d (%d types)%n", s.eventCount(), s.typeCounts().size());
        out.printf(l, "Threads     %,d%n", s.threadCount());
        out.printf(l, "Stacks      %,d unique for %,d stack references (%.1fx dedup)%n",
                s.uniqueStacks(), s.stackReferences(), s.dedupRatio());
        out.printf(l, "Parsed in   %,d ms%n", parseMs);

        out.printf(l, "%nTop event types%n");
        int shown = 0;
        for (Map.Entry<String, Long> e : s.typeCounts().entrySet()) {
            if (shown++ == top) {
                break;
            }
            out.printf(l, "  %-40s %,10d%n", e.getKey(), e.getValue());
        }

        out.printf(l, "%nHot methods (%s, top frame)%n", HotMethods.EXECUTION_SAMPLE);
        if (s.hotMethods().isEmpty()) {
            out.println("  (no CPU samples in this recording)");
        }
        for (HotMethods.MethodCount m : s.hotMethods()) {
            out.printf(l, "  %6.2f%% %8d  %s%n", m.percent(), m.samples(), m.method());
        }
    }
}
