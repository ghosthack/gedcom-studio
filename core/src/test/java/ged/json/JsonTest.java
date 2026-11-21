package ged.json;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ged.json.Json.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {
    @Test
    void writesInKeyOrderWithEscapes() {
        var json = Json.write(obj("b", 1, "a", List.of(true, false), "s", "q\"\\\n\t</script>é", "n", null, "o", Optional.of(2.5)));
        assertEquals("{\"b\":1,\"a\":[true,false],\"s\":\"q\\\"\\\\\\n\\t\\u003c/script\\u003eé\",\"n\":null,\"o\":2.5}", json);
    }

    @Test
    void readsBackWhatItWrites() {
        var value = obj("name", "Maria \"Mimi\" Rossi\n", "years", List.of(1850L, -3L), "x", 1.5e3, "ok", true, "none", null,
                "nested", obj("a", List.of(obj())));
        assertEquals(value, Json.parse(Json.write(value)));
    }

    @Test
    void readsUnicodeEscapesAndSpace() {
        var m = Json.parseObject(" { \"a\" : \"\\u00e9\\n\" , \"b\":[ ] } ");
        assertEquals("é\n", m.get("a"));
        assertEquals(List.of(), m.get("b"));
        assertEquals(Map.of(), Json.parseObject("  "));
    }

    @Test
    void refusesWhatIsNotJson() {
        for (var bad : List.of("{", "{\"a\":}", "[1,]", "{'a':1}", "tru", "\"x", "{\"a\":1} x", "01x", "[\"\u0001\"]", "{\"a\" 1}")) {
            assertThrows(Json.ParseException.class, () -> Json.parse(bad), bad);
        }
        assertThrows(Json.ParseException.class, () -> Json.parseObject("[1]"));
        var deep = "[".repeat(100) + "]".repeat(100);
        assertThrows(Json.ParseException.class, () -> Json.parse(deep));
    }

    @Test
    void numbersAreLongsOrDoubles() {
        assertEquals(42L, Json.parse("42"));
        assertEquals(-0.5, Json.parse("-0.5"));
        assertEquals(1e100, Json.parse("1e100"));
        assertEquals(1.0E19, Json.parse("10000000000000000000"));
        assertNull(Json.parse("null"));
        assertEquals("[1,2]", Json.write(Arrays.asList(1, 2).toArray()));
    }
}
