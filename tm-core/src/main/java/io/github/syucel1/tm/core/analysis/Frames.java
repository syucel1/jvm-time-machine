package io.github.syucel1.tm.core.analysis;

import io.github.syucel1.tm.core.model.Frame;
import io.github.syucel1.tm.core.model.StackTraceData;

import java.util.List;

/** Helpers for picking the frame that best answers "where in my code?". */
public final class Frames {

    private static final List<String> PLATFORM_PREFIXES =
            List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    private Frames() {
    }

    /** {@code true} for JDK and platform frames ({@code java.*}, {@code jdk.*}, {@code sun.*}, ...). */
    public static boolean isPlatform(Frame frame) {
        String cls = frame.className();
        for (String prefix : PLATFORM_PREFIXES) {
            if (cls.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The innermost non-platform frame, i.e. the application method that led to this stack;
     * falls back to the top frame when the whole stack is platform code. {@code null} for an
     * empty or missing stack.
     */
    public static Frame firstApplicationFrame(StackTraceData stack) {
        if (stack == null || stack.frames().isEmpty()) {
            return null;
        }
        for (Frame f : stack.frames()) {
            if (!isPlatform(f)) {
                return f;
            }
        }
        return stack.top();
    }
}
