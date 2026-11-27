package io.github.syucel1.tm.core;

import io.github.syucel1.tm.core.analysis.FlameGraph;
import io.github.syucel1.tm.core.analysis.FlameGraph.Kind;
import io.github.syucel1.tm.core.analysis.FlameGraph.Node;
import io.github.syucel1.tm.core.analysis.Window;
import io.github.syucel1.tm.core.model.JvmRecording;

import org.junit.jupiter.api.Test;

import static io.github.syucel1.tm.core.DemoJfr.DEMO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 4: flame graphs of demo.jfr show each planted problem as a wide frame. */
class FlameGraphTest {

    @Test
    void cpuFlameGraphIsDominatedBySlowPrimeCount() throws Exception {
        JvmRecording rec = DemoJfr.load();
        FlameGraph.Result r = FlameGraph.build(rec, Window.all(rec), Kind.CPU, null, 0);

        assertEquals("cpu", r.kind());
        assertEquals("samples", r.unit());
        long samples = rec.eventsOfType("jdk.ExecutionSample").filter(e -> rec.stackOf(e) != null).count();
        assertEquals(samples, r.root().value(), "root holds every sample with a stack");
        Node hot = find(r.root(), DEMO + ".slowPrimeCount");
        assertNotNull(hot, "slowPrimeCount in the tree");
        assertTrue(hot.value() > r.root().value() / 3, "slowPrimeCount is a wide frame: " + hot.value());
        assertEquals(hot.value(), hot.self(), "slowPrimeCount calls nothing sampled");
    }

    @Test
    void valuesAddUp() throws Exception {
        JvmRecording rec = DemoJfr.load();
        for (Kind kind : Kind.values()) {
            FlameGraph.Result r = FlameGraph.build(rec, Window.all(rec), kind, null, 0.01);
            assertConsistent(r.root());
        }
    }

    @Test
    void allocationAndLockGraphsPointAtTheDemo() throws Exception {
        JvmRecording rec = DemoJfr.load();
        FlameGraph.Result alloc = FlameGraph.build(rec, Window.all(rec), Kind.ALLOCATION, null, 0);
        assertEquals("alloc", alloc.kind());
        Node allocate = find(alloc.root(), DEMO + ".allocate");
        assertNotNull(allocate);
        assertTrue(allocate.value() > alloc.root().value() / 2, "allocate() allocates most bytes");

        FlameGraph.Result lock = FlameGraph.build(rec, Window.all(rec), Kind.LOCK, null, 0);
        assertEquals("nanos", lock.unit());
        Node contended = find(lock.root(), DEMO + ".contended");
        assertNotNull(contended);
        assertEquals(lock.root().value(), contended.value(), "all blocking is in contended()");
    }

    @Test
    void threadFilterKeepsOnlyThatThread() throws Exception {
        JvmRecording rec = DemoJfr.load();
        FlameGraph.Result cpu = FlameGraph.build(rec, Window.all(rec), Kind.CPU, "cpu-worker", 0);
        assertEquals("cpu-worker", cpu.thread());
        assertNotNull(find(cpu.root(), DEMO + ".slowPrimeCount"));
        assertEquals(null, find(cpu.root(), DEMO + ".allocate"), "allocate() runs on main");
        assertEquals(0, FlameGraph.build(rec, Window.all(rec), Kind.CPU, "no-such-thread", 0).root().value());
    }

    @Test
    void pruningFoldsSmallSubtreesIntoSelf() throws Exception {
        JvmRecording rec = DemoJfr.load();
        FlameGraph.Result full = FlameGraph.build(rec, Window.all(rec), Kind.CPU, null, 0);
        FlameGraph.Result pruned = FlameGraph.build(rec, Window.all(rec), Kind.CPU, null, 0.05);
        assertEquals(full.root().value(), pruned.root().value());
        assertTrue(pruned.nodes() < full.nodes(), pruned.nodes() + " < " + full.nodes());
        assertMinValue(pruned.root(), (long) Math.ceil(pruned.root().value() * 0.05));
    }

    @Test
    void emptyWindowGivesEmptyRoot() throws Exception {
        JvmRecording rec = DemoJfr.load();
        Window empty = new Window(rec.startNanos(), rec.startNanos());
        FlameGraph.Result r = FlameGraph.build(rec, empty, Kind.CPU, null, 0);
        assertEquals(0, r.root().value());
        assertTrue(r.root().children().isEmpty());
        assertEquals(1, r.nodes());
    }

    @Test
    void parsesKinds() {
        assertEquals(Kind.CPU, Kind.parse("CPU"));
        assertEquals(Kind.ALLOCATION, Kind.parse("alloc"));
        assertEquals(Kind.LOCK, Kind.parse("lock"));
        assertThrows(IllegalArgumentException.class, () -> Kind.parse("heap"));
    }

    private static Node find(Node n, String name) {
        if (n.name().equals(name)) {
            return n;
        }
        for (Node c : n.children()) {
            Node f = find(c, name);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    private static void assertConsistent(Node n) {
        long children = n.children().stream().mapToLong(Node::value).sum();
        assertEquals(n.value(), n.self() + children, "value = self + children at " + n.name());
        assertTrue(n.self() >= 0);
        for (int i = 1; i < n.children().size(); i++) {
            assertTrue(n.children().get(i - 1).value() >= n.children().get(i).value(), "heaviest first");
        }
        n.children().forEach(FlameGraphTest::assertConsistent);
    }

    private static void assertMinValue(Node root, long min) {
        for (Node c : root.children()) {
            assertTrue(c.value() >= min, c.name() + " " + c.value() + " >= " + min);
            assertMinValue(c, min);
        }
    }
}
