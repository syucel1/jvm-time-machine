package io.github.syucel1.tm.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deduplicating store for stack traces.
 *
 * <p>Samples repeat the same stacks over and over, so each unique stack is stored once and events
 * refer to it by an {@code int} id. This is usually the biggest memory saving in a recording.
 *
 * <p>Not thread-safe; build it on one thread, then share it read-only.
 */
public final class StackTable {

    /** Id used by events that carry no stack trace. */
    public static final int NO_STACK = -1;

    private record Key(List<Frame> frames, boolean truncated) {
    }

    private final Map<Key, Integer> ids = new HashMap<>();
    private final List<StackTraceData> stacks = new ArrayList<>();
    private long lookups;

    /**
     * Returns the id of this stack, adding it if it has not been seen before.
     * Equal frame lists (and equal {@code truncated} flags) always get the same id.
     */
    public int intern(List<Frame> frames, boolean truncated) {
        lookups++;
        Key key = new Key(List.copyOf(frames), truncated);
        Integer existing = ids.get(key);
        if (existing != null) {
            return existing;
        }
        int id = stacks.size();
        stacks.add(new StackTraceData(id, key.frames(), truncated));
        ids.put(key, id);
        return id;
    }

    /** The stack with this id, or {@code null} for {@link #NO_STACK}. */
    public StackTraceData get(int id) {
        if (id == NO_STACK) {
            return null;
        }
        return stacks.get(id);
    }

    /** Number of unique stacks. */
    public int size() {
        return stacks.size();
    }

    /** Number of {@link #intern} calls, i.e. stack references before deduplication. */
    public long lookups() {
        return lookups;
    }

    /** How many references share each stored stack on average ({@code lookups / size}). */
    public double dedupRatio() {
        return stacks.isEmpty() ? 0 : (double) lookups / stacks.size();
    }

    public List<StackTraceData> all() {
        return Collections.unmodifiableList(stacks);
    }
}
