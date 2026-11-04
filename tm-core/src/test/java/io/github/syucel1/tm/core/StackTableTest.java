package io.github.syucel1.tm.core;

import io.github.syucel1.tm.core.model.Frame;
import io.github.syucel1.tm.core.model.StackTable;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackTableTest {

    private static final Frame A = new Frame("com.example.A", "run", "()V", 10, "JIT compiled");
    private static final Frame B = new Frame("com.example.B", "call", "()V", 20, "Interpreted");

    @Test
    void equalStacksShareOneId() {
        StackTable table = new StackTable();
        int first = table.intern(List.of(A, B), false);
        int second = table.intern(List.of(A, B), false);
        assertEquals(first, second);
        assertEquals(1, table.size());
        assertEquals(2, table.lookups());
        assertEquals(2.0, table.dedupRatio(), 1e-9);
    }

    @Test
    void differentStacksGetDifferentIds() {
        StackTable table = new StackTable();
        int ab = table.intern(List.of(A, B), false);
        int ba = table.intern(List.of(B, A), false);
        int abTruncated = table.intern(List.of(A, B), true);
        assertNotEquals(ab, ba);
        assertNotEquals(ab, abTruncated);
        assertEquals(3, table.size());
    }

    @Test
    void storedStackKeepsFramesTopFirst() {
        StackTable table = new StackTable();
        int id = table.intern(List.of(A, B), false);
        assertEquals(A, table.get(id).top());
        assertTrue(table.get(id).contains("com.example.B.call"));
        assertNull(table.get(StackTable.NO_STACK));
    }
}
