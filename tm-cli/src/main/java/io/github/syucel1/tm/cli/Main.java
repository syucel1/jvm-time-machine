package io.github.syucel1.tm.cli;

import io.github.syucel1.tm.core.analysis.HotMethods;
import io.github.syucel1.tm.core.analysis.RecordingSummary;
import io.github.syucel1.tm.core.analysis.Window;
import io.github.syucel1.tm.core.analysis.WindowQuery;
import io.github.syucel1.tm.core.jfr.JfrReader;
import io.github.syucel1.tm.core.model.JvmRecording;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * {@code tm-cli} entry point.
 *
 * <pre>
 * tm-cli summary &lt;file.jfr&gt; [--top N]
 * tm-cli window  &lt;file.jfr&gt; [--from S] [--to S] [--top N]
 * tm-cli serve   &lt;file.jfr&gt; [--port 7070] [--host 127.0.0.1]
 * </pre>
 */
public final class Main {

    private static final Locale L = Locale.ROOT;

    private static final String USAGE = """
            Usage: tm-cli <command> <file.jfr> [options]

            Commands:
              summary <file.jfr> [--top N]                  Events, threads, stack dedup and hot methods
              window  <file.jfr> [--from S] [--to S] [--top N]
                                                            Hot methods, allocations, lock contention,
                                                            GC and threads between two points in time
              serve   <file.jfr> [--port 7070] [--host 127.0.0.1]
                                                            Serve the recording over a JSON API
              help                                          Show this help
              version                                       Show the version

            Times (--from, --to) are seconds since the recording start, e.g. --from 2.5 --to 5.
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
        if (args[0].equals("version") || args[0].equals("--version")) {
            String v = Main.class.getPackage().getImplementationVersion();
            out.println("tm-cli " + (v == null ? "dev" : v));
            return 0;
        }
        try {
            Args a = Args.parse(args);
            return switch (a.command) {
                case "summary" -> summary(a, out);
                case "window" -> window(a, out);
                case "serve" -> serve(a, out);
                default -> throw new UsageException("unknown command: " + a.command);
            };
        } catch (UsageException e) {
            err.println("Error: " + e.getMessage());
            err.print(USAGE);
            return 2;
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    // ---------------------------------------------------------------- commands

    private static int summary(Args a, PrintStream out) throws IOException {
        a.allowOnly("top");
        int top = a.intOpt("top", 10);
        long t0 = System.nanoTime();
        RecordingSummary s = RecordingSummary.of(load(a), top);
        long parseMs = (System.nanoTime() - t0) / 1_000_000;

        out.printf(L, "Recording   %s%n", s.source());
        out.printf(L, "Start       %s%n", s.start());
        out.printf(L, "Duration    %.1f s%n", s.duration().toMillis() / 1000.0);
        out.printf(L, "Events      %,d (%d types)%n", s.eventCount(), s.typeCounts().size());
        out.printf(L, "Threads     %,d%n", s.threadCount());
        out.printf(L, "Stacks      %,d unique for %,d stack references (%.1fx dedup)%n",
                s.uniqueStacks(), s.stackReferences(), s.dedupRatio());
        out.printf(L, "Parsed in   %,d ms%n", parseMs);

        out.printf(L, "%nTop event types%n");
        s.typeCounts().entrySet().stream().limit(top)
                .forEach(e -> out.printf(L, "  %-40s %,10d%n", e.getKey(), e.getValue()));

        out.printf(L, "%nHot methods (%s, top frame)%n", HotMethods.EXECUTION_SAMPLE);
        printHotMethods(s.hotMethods(), out);
        return 0;
    }

    private static int window(Args a, PrintStream out) throws IOException {
        a.allowOnly("from", "to", "top");
        int top = a.intOpt("top", 5);
        JvmRecording rec = load(a);
        Window w = Window.ofOffsets(rec, a.doubleOpt("from"), a.doubleOpt("to"));
        WindowQuery.Result r = WindowQuery.run(rec, w, top);

        out.printf(L, "Window %.1f-%.1f s of %s (%,d events, %,d CPU samples)%n",
                r.fromSeconds(), r.toSeconds(), rec.source().getFileName(), r.eventCount(), r.cpuSamples());

        out.printf(L, "%nHot methods%n");
        printHotMethods(r.hotMethods(), out);

        out.printf(L, "%nAllocations by site (first application frame)%n");
        r.allocationsBySite().forEach(x ->
                out.printf(L, "  %6.2f%% %10s  %s%n", x.percent(), bytes(x.bytes()), x.name()));
        out.printf(L, "%nAllocations by class%n");
        r.allocationsByClass().forEach(x ->
                out.printf(L, "  %6.2f%% %10s  %s%n", x.percent(), bytes(x.bytes()), x.name()));

        out.printf(L, "%nLock contention%n");
        if (r.contention().isEmpty()) {
            out.println("  (none)");
        }
        r.contention().forEach(c -> out.printf(L, "  %5d x  total %9s  max %9s  %s  [%s]%n",
                c.count(), millis(c.totalNanos()), millis(c.maxNanos()), c.method(), c.monitorClass()));

        WindowQuery.GcSummary gc = r.gc();
        out.printf(L, "%nGC: %d collections, %s paused in total, longest pause %s%n",
                gc.count(), millis(gc.pauseNanos()), millis(gc.maxPauseNanos()));
        gc.longest().forEach(g -> out.printf(L, "  at %6.2f s  %9s  %-12s %s%n",
                g.offsetSeconds(), millis(g.pauseNanos()), g.name(), g.cause()));

        out.printf(L, "%nThreads%n");
        r.threads().forEach(t -> out.printf(L, "  %-24s %6d samples  %10s allocated  %9s blocked%n",
                t.name(), t.cpuSamples(), bytes(t.allocatedBytes()), millis(t.lockWaitNanos())));
        return 0;
    }

    private static int serve(Args a, PrintStream out) throws IOException {
        a.allowOnly("port", "host");
        int port = a.intOpt("port", 7070);
        String host = a.opt("host", "127.0.0.1");
        long t0 = System.nanoTime();
        JvmRecording rec = load(a);
        try (TmServer server = new TmServer(rec, host, port)) {
            server.start();
            out.printf(L, "Loaded %s: %,d events in %,d ms%n", rec.source().getFileName(),
                    rec.events().size(), (System.nanoTime() - t0) / 1_000_000);
            out.printf(L, "Serving on %s  (Ctrl+C to stop)%n", server.url());
            out.flush();
            CountDownLatch stop = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown, "tm-cli-shutdown"));
            stop.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    // ---------------------------------------------------------------- helpers

    private static JvmRecording load(Args a) throws IOException {
        if (a.file == null) {
            throw new UsageException(a.command + ": missing <file.jfr>");
        }
        if (!Files.isRegularFile(a.file)) {
            throw new IOException("no such file: " + a.file);
        }
        return JfrReader.create().read(a.file);
    }

    private static void printHotMethods(java.util.List<HotMethods.MethodCount> methods, PrintStream out) {
        if (methods.isEmpty()) {
            out.println("  (no CPU samples)");
        }
        methods.forEach(m -> out.printf(L, "  %6.2f%% %8d  %s%n", m.percent(), m.samples(), m.method()));
    }

    static String bytes(long b) {
        if (b < 1024) {
            return b + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = b;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(L, "%.1f %s", v, units[u]);
    }

    static String millis(long nanos) {
        return String.format(L, "%.1f ms", nanos / 1e6);
    }

    /** Thrown for bad command-line usage (exit code 2). */
    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    /** Parsed arguments: {@code <command> <file> [--name value]...}. */
    static final class Args {
        String command;
        Path file;
        final Map<String, String> opts = new HashMap<>();

        static Args parse(String[] args) {
            Args a = new Args();
            a.command = args[0];
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("--")) {
                    if (i + 1 >= args.length) {
                        throw new UsageException("missing value for " + arg);
                    }
                    a.opts.put(arg.substring(2), args[++i]);
                } else if (a.file == null) {
                    a.file = Path.of(arg);
                } else {
                    throw new UsageException("unexpected argument: " + arg);
                }
            }
            return a;
        }

        void allowOnly(String... names) {
            for (String k : opts.keySet()) {
                if (!java.util.List.of(names).contains(k)) {
                    throw new UsageException(command + ": unknown option --" + k);
                }
            }
        }

        String opt(String name, String def) {
            return opts.getOrDefault(name, def);
        }

        int intOpt(String name, int def) {
            String v = opts.get(name);
            try {
                return v == null ? def : Integer.parseInt(v);
            } catch (NumberFormatException e) {
                throw new UsageException("--" + name + " must be an integer: " + v);
            }
        }

        Double doubleOpt(String name) {
            String v = opts.get(name);
            try {
                return v == null ? null : Double.valueOf(v);
            } catch (NumberFormatException e) {
                throw new UsageException("--" + name + " must be a number of seconds: " + v);
            }
        }
    }
}
