package io.github.syucel1.tm.core;

import io.github.syucel1.tm.core.json.Json;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {

    record Point(String name, int x, double y, List<String> tags) {
    }

    @Test
    void writesRecordsListsAndMaps() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("p", new Point("a", 1, 2.5, List.of("t1", "t2")));
        m.put("n", null);
        m.put("ok", true);
        m.put("arr", Arrays.asList(1L, 2L));
        assertEquals("{\"p\":{\"name\":\"a\",\"x\":1,\"y\":2.5,\"tags\":[\"t1\",\"t2\"]},"
                + "\"n\":null,\"ok\":true,\"arr\":[1,2]}", Json.write(m));
    }

    @Test
    void escapesStrings() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", Json.write("a\"b\\c\nd\u0001"));
    }

    @Test
    void writesWholeDoublesAsIntegersAndNonFiniteAsNull() {
        assertEquals("[3,0.25,null,null,-1]", Json.write(List.of(3.0, 0.25, Double.NaN, Double.POSITIVE_INFINITY, -1.0)));
    }
}
