package io.github.syucel1.tm.core.analysis;

import io.github.syucel1.tm.core.model.Frame;
import io.github.syucel1.tm.core.model.JvmRecording;
import io.github.syucel1.tm.core.model.StackTraceData;
import io.github.syucel1.tm.core.model.ThreadInfo;
import io.github.syucel1.tm.core.model.TmEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Merges the stacks of one event kind in a {@link Window} into a call tree for a flame graph.
 *
 * <p>The root is the whole window; each child is a caller-to-callee step, outermost frame first.
 * Frames are merged by {@link Frame#qualifiedName()}, so different lines of one method share a
 * node. Each stack is weighted by its {@link Kind}: one per CPU sample, sampled bytes for
 * allocations, blocked nanoseconds for lock waits.
 *
 * <pre>{@code
 * FlameGraph.Result cpu = FlameGraph.build(rec, Window.ofOffsets(rec, 2.0, 5.0), Kind.CPU, null, 0.001);
 * }</pre>
 */
public final class FlameGraph {

    /** What a flame graph is built from, and what its values mean. */
    public enum Kind {
        /** CPU samples ({@code jdk.ExecutionSample}); value = samples. */
        CPU(HotMethods.EXECUTION_SAMPLE, "samples"),
        /** Allocation samples ({@code jdk.ObjectAllocationSample}); value = estimated bytes. */
        ALLOCATION(Timeline.ALLOCATION_SAMPLE, "bytes"),
        /** Blocking on {@code synchronized} ({@code jdk.JavaMonitorEnter}); value = nanoseconds. */
        LOCK(Timeline.MONITOR_ENTER, "nanos");

        private final String eventType;
        private final String unit;

        Kind(String eventType, String unit) {
            this.eventType = eventType;
            this.unit = unit;
        }

        public String eventType() {
            return eventType;
        }

        public String unit() {
            return unit;
        }

        /** Parses {@code cpu}, {@code alloc}/{@code allocation} or {@code lock}, ignoring case. */
        public static Kind parse(String s) {
            return switch (s == null ? "cpu" : s.toLowerCase(Locale.ROOT)) {
                case "cpu" -> CPU;
                case "alloc", "allocation", "allocations" -> ALLOCATION;
                case "lock", "locks", "contention" -> LOCK;
                default -> throw new IllegalArgumentException("unknown flame graph kind: " + s
                        + " (expected cpu, alloc or lock)");
            };
        }

        long weight(TmEvent e) {
            return switch (this) {
                case CPU -> 1;
                case ALLOCATION -> e.attribute("weight") instanceof Number n ? n.longValue() : 0;
                case LOCK -> e.durationNanos();
            };
        }
    }

    /**
     * One node of the call tree.
     *
     * @param name     {@code className.methodName}, or {@code "all"} for the root
     * @param value    total weight of stacks passing through this node
     * @param self     weight of stacks ending here, including pruned children
     * @param children callees, heaviest first
     */
    public record Node(String name, long value, long self, List<Node> children) {
    }

    /**
     * @param kind        {@code cpu}, {@code alloc} or {@code lock}
     * @param unit        {@code samples}, {@code bytes} or {@code nanos}
     * @param fromSeconds window start, seconds since recording start
     * @param toSeconds   window end, seconds since recording start
     * @param thread      thread name filter, or {@code null} for all threads
     * @param events      events that contributed a stack
     * @param nodes       nodes in the tree after pruning, root included
     * @param root        the tree; {@code root.value()} is the total weight
     */
    public record Result(String kind, String unit, double fromSeconds, double toSeconds, String thread,
                         long events, int nodes, Node root) {
    }

    /** Default share of the total below which subtrees are folded into their parent's self time. */
    public static final double DEFAULT_MIN_FRACTION = 0.0005;

    private FlameGraph() {
    }

    /**
     * Builds the tree.
     *
     * @param threadName  only events on threads with this name, or {@code null} for all
     * @param minFraction subtrees lighter than this share of the total (0–1) are pruned
     */
    public static Result build(JvmRecording recording, Window window, Kind kind, String threadName,
                               double minFraction) {
        if (!(minFraction >= 0 && minFraction < 1)) {
            throw new IllegalArgumentException("minFraction must be in [0, 1): " + minFraction);
        }
        MutableNode root = new MutableNode("all");
        long events = 0;
        for (TmEvent e : window.events(recording)) {
            if (!e.type().equals(kind.eventType())) {
                continue;
            }
            if (threadName != null) {
                ThreadInfo t = recording.threadOf(e);
                if (t == null || !threadName.equals(t.name())) {
                    continue;
                }
            }
            StackTraceData stack = recording.stackOf(e);
            if (stack == null || stack.frames().isEmpty()) {
                continue;
            }
            long w = kind.weight(e);
            if (w <= 0) {
                continue;
            }
            events++;
            root.value += w;
            MutableNode node = root;
            List<Frame> frames = stack.frames();
            for (int i = frames.size() - 1; i >= 0; i--) { // outermost caller first
                node = node.child(frames.get(i).qualifiedName());
                node.value += w;
            }
        }
        long minValue = (long) Math.ceil(root.value * minFraction);
        int[] count = {0};
        Node tree = root.freeze(Math.max(minValue, 1), count);
        return new Result(kind.name().toLowerCase(Locale.ROOT).replace("allocation", "alloc"), kind.unit(),
                window.fromOffsetSeconds(recording), window.toOffsetSeconds(recording), threadName,
                events, count[0], tree);
    }

    private static final class MutableNode {
        final String name;
        long value;
        Map<String, MutableNode> children;

        MutableNode(String name) {
            this.name = name;
        }

        MutableNode child(String childName) {
            if (children == null) {
                children = new HashMap<>();
            }
            return children.computeIfAbsent(childName, MutableNode::new);
        }

        Node freeze(long minValue, int[] count) {
            count[0]++;
            List<Node> kept = new ArrayList<>();
            long keptValue = 0;
            if (children != null) {
                List<MutableNode> sorted = new ArrayList<>(children.values());
                sorted.sort(Comparator.comparingLong((MutableNode n) -> n.value).reversed()
                        .thenComparing(n -> n.name));
                for (MutableNode c : sorted) {
                    if (c.value >= minValue) {
                        kept.add(c.freeze(minValue, count));
                        keptValue += c.value;
                    }
                }
            }
            return new Node(name, value, value - keptValue, List.copyOf(kept));
        }
    }
}
