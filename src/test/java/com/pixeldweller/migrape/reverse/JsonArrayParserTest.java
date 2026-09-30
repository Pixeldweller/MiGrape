package com.pixeldweller.migrape.reverse;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonArrayParserTest {

    @Test
    void parsesWhatTheForwardMigrationWrites() {
        // So schreibt BatchInserter.arrayToJson ein H2-ARRAY nach MariaDB.
        assertArrayEquals(new Object[]{3L, 7L, 11L}, JsonArrayParser.parse("[3,7,11]"));
        assertArrayEquals(new Object[]{"a\"b", null, "c\\d\ne\tf"},
                JsonArrayParser.parse("[\"a\\\"b\",null,\"c\\\\d\\ne\\tf\"]"));
        assertArrayEquals(new Object[]{true, false, new BigDecimal("1.5"), -2L},
                JsonArrayParser.parse("[true,false,1.5,-2]"));
    }

    @Test
    void parsesNestedAndEmptyArraysWithWhitespace() {
        assertArrayEquals(new Object[]{new Object[]{1L, 2L}, new Object[]{}},
                JsonArrayParser.parse(" [ [1, 2] , [ ] ] "));
        assertArrayEquals(new Object[]{}, JsonArrayParser.parse("[]"));
    }

    @Test
    void keepsLargeAndUnicodeValues() {
        assertArrayEquals(new Object[]{new BigDecimal("99999999999999999999"), "äö€"},
                JsonArrayParser.parse("[99999999999999999999,\"\\u00e4ö\\u20ac\"]"));
    }

    @Test
    void rejectsAnythingThatIsNotAnArray() {
        assertThrows(IllegalArgumentException.class, () -> JsonArrayParser.parse("{\"a\":1}"));
        assertThrows(IllegalArgumentException.class, () -> JsonArrayParser.parse("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> JsonArrayParser.parse("[1] x"));
        assertThrows(IllegalArgumentException.class, () -> JsonArrayParser.parse("[\"offen]"));
    }
}
