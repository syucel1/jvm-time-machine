package io.github.syucel1.tm.core.model;

import java.util.List;

/**
 * A unique stack trace stored once in a {@link StackTable} and referenced by id from events.
 *
 * @param id        id within its {@link StackTable}
 * @param frames    frames, top (innermost) frame first, as JFR records them
 * @param truncated {@code true} if JFR cut the stack at its depth limit
 */
public record StackTraceData(int id, List<Frame> frames, boolean truncated) {

    public StackTraceData {
        frames = List.copyOf(frames);
    }

    /** The innermost frame, i.e. the method that was executing, or {@code null} for an empty stack. */
    public Frame top() {
        return frames.isEmpty() ? null : frames.get(0);
    }

    /** {@code true} if any frame's {@link Frame#qualifiedName()} equals {@code qualifiedMethod}. */
    public boolean contains(String qualifiedMethod) {
        for (Frame f : frames) {
            if (f.qualifiedName().equals(qualifiedMethod)) {
                return true;
            }
        }
        return false;
    }

    public int depth() {
        return frames.size();
    }
}
