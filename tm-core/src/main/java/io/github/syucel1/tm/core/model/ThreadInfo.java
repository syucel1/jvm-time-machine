package io.github.syucel1.tm.core.model;

/**
 * A thread seen in a recording.
 *
 * @param key          stable key used by events: the Java thread id, or the negated OS thread id
 *                     for native threads that have no Java id (GC, compiler threads)
 * @param javaThreadId Java thread id, or {@code -1} for native threads
 * @param osThreadId   OS thread id, or {@code -1} when unknown
 * @param name         Java name, or OS name for native threads
 * @param virtual      {@code true} for virtual threads
 */
public record ThreadInfo(long key, long javaThreadId, long osThreadId, String name, boolean virtual) {

    /** Key used by events that are not tied to a thread. */
    public static final long NO_THREAD = Long.MIN_VALUE;

    /** Computes the {@link #key()} for a thread from its ids. */
    public static long keyOf(long javaThreadId, long osThreadId) {
        if (javaThreadId > 0) {
            return javaThreadId;
        }
        return osThreadId > 0 ? -osThreadId : NO_THREAD;
    }
}
