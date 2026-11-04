package io.github.syucel1.tm.core.analysis;

import io.github.syucel1.tm.core.model.Frame;
import io.github.syucel1.tm.core.model.JvmRecording;
import io.github.syucel1.tm.core.model.StackTraceData;
import io.github.syucel1.tm.core.model.TmEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks methods by how often they are the top (executing) frame of sampled stacks,
 * the same idea as {@code jfr view hot-methods}.
 */
public final class HotMethods {

    /** JFR's CPU sampling event. */
    public static final String EXECUTION_SAMPLE = "jdk.ExecutionSample";

    /**
     * @param method  {@code className.methodName}
     * @param samples number of samples with this method as top frame
     * @param percent share of all samples of the event type, 0–100
     */
    public record MethodCount(String method, long samples, double percent) {
    }

    private HotMethods() {
    }

    /** Top methods by CPU samples ({@value #EXECUTION_SAMPLE}). */
    public static List<MethodCount> top(JvmRecording recording, int limit) {
        return top(recording, EXECUTION_SAMPLE, limit);
    }

    /** Top methods by top-frame count for any stack-bearing event type. */
    public static List<MethodCount> top(JvmRecording recording, String eventType, int limit) {
        Map<String, Long> counts = new HashMap<>();
        long total = 0;
        for (TmEvent e : recording.eventsOfType(eventType).toList()) {
            StackTraceData stack = recording.stackOf(e);
            Frame top = stack == null ? null : stack.top();
            if (top == null) {
                continue;
            }
            counts.merge(top.qualifiedName(), 1L, Long::sum);
            total++;
        }
        long finalTotal = total;
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(en -> new MethodCount(en.getKey(), en.getValue(), 100.0 * en.getValue() / finalTotal))
                .toList();
    }
}
