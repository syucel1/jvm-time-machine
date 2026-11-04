package io.github.syucel1.tm.core;

import io.github.syucel1.tm.core.jfr.JfrReader;
import io.github.syucel1.tm.core.model.JvmRecording;
import io.github.syucel1.tm.core.model.StackTraceData;
import io.github.syucel1.tm.core.model.ThreadInfo;
import io.github.syucel1.tm.core.model.TmEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Records a small, known set of custom events in-process and checks they are read back exactly. */
class JfrReaderTest {

    private static final int EVENTS = 100;

    @Name("tm.test.Hello")
    @Label("Hello")
    static class HelloEvent extends Event {
        @Label("Message")
        String message;
        @Label("Count")
        int count;
    }

    private static void emitHello(int i) {
        HelloEvent e = new HelloEvent();
        e.message = "hello";
        e.count = i;
        e.commit();
    }

    private static Path recordHelloEvents() throws Exception {
        Path file = Files.createTempFile("tm-reader-test", ".jfr");
        try (Recording r = new Recording()) {
            r.enable(HelloEvent.class);
            r.enable("jdk.ThreadSleep").withoutThreshold();
            r.start();
            for (int i = 0; i < EVENTS; i++) {
                emitHello(i);
            }
            Thread.sleep(5);
            r.stop();
            r.dump(file);
        }
        file.toFile().deleteOnExit();
        return file;
    }

    @Test
    void readsCustomEventsWithAttributesThreadAndStack() throws Exception {
        JvmRecording rec = JfrReader.create().read(recordHelloEvents());
        List<TmEvent> hellos = rec.eventsOfType("tm.test.Hello").toList();

        assertEquals(EVENTS, hellos.size());
        long countSum = hellos.stream().mapToLong(e -> ((Number) e.attribute("count")).longValue()).sum();
        assertEquals((long) EVENTS * (EVENTS - 1) / 2, countSum);
        assertEquals("hello", hellos.get(0).attribute("message"));

        ThreadInfo thread = rec.threadOf(hellos.get(0));
        assertNotNull(thread);
        assertEquals(Thread.currentThread().getName(), thread.name());

        StackTraceData stack = rec.stackOf(hellos.get(0));
        assertNotNull(stack);
        assertTrue(stack.contains(JfrReaderTest.class.getName() + ".emitHello"), "stack: " + stack.frames());
    }

    @Test
    void deduplicatesStacksFromTheSameCallSite() throws Exception {
        JvmRecording rec = JfrReader.create().read(recordHelloEvents());
        long distinct = rec.eventsOfType("tm.test.Hello").mapToInt(TmEvent::stackId).distinct().count();
        assertEquals(1, distinct, "100 events from one call site should share one stack");
    }

    @Test
    void eventsAreSortedByStartTime() throws Exception {
        JvmRecording rec = JfrReader.create().read(recordHelloEvents());
        List<TmEvent> events = rec.events();
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i - 1).startNanos() <= events.get(i).startNanos());
        }
        assertTrue(rec.duration().toNanos() >= 0);
    }

    @Test
    void typeFilterKeepsOnlyMatchingEvents() throws Exception {
        JvmRecording rec = JfrReader.create()
                .withTypeFilter(t -> t.equals("tm.test.Hello"))
                .withoutAttributes()
                .read(recordHelloEvents());
        assertEquals(EVENTS, rec.events().size());
        assertTrue(rec.events().stream().allMatch(e -> e.attributes().isEmpty()));
    }
}
