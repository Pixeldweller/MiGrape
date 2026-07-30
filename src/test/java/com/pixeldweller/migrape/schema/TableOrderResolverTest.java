package com.pixeldweller.migrape.schema;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableOrderResolverTest {

    private static TableDefinition table(String name, TableDefinition.ForeignKey... fks) {
        TableDefinition t = new TableDefinition(name);
        for (TableDefinition.ForeignKey fk : fks) {
            t.foreignKeys.add(fk);
        }
        return t;
    }

    private static TableDefinition.ForeignKey fkTo(String referencedTable) {
        return new TableDefinition.ForeignKey("FK_" + referencedTable, List.of("REF_ID"),
                referencedTable, List.of("ID"));
    }

    @Test
    void ordersReferencedTableBeforeDependent() {
        Map<String, TableDefinition> tables = new LinkedHashMap<>();
        // Bewusst in "falscher" Reihenfolge eingefuegt: BOOK vor AUTHOR
        tables.put("BOOK", table("BOOK", fkTo("AUTHOR")));
        tables.put("AUTHOR", table("AUTHOR"));

        List<String> order = TableOrderResolver.resolve(tables);

        assertTrue(order.indexOf("AUTHOR") < order.indexOf("BOOK"),
                "AUTHOR muss vor BOOK erstellt werden, Reihenfolge war: " + order);
    }

    @Test
    void ordersThreeLevelChainCorrectly() {
        Map<String, TableDefinition> tables = new LinkedHashMap<>();
        tables.put("BOOK_REVIEW", table("BOOK_REVIEW", fkTo("BOOK")));
        tables.put("BOOK", table("BOOK", fkTo("AUTHOR"), fkTo("PUBLISHER")));
        tables.put("AUTHOR", table("AUTHOR"));
        tables.put("PUBLISHER", table("PUBLISHER"));

        List<String> order = TableOrderResolver.resolve(tables);

        assertEquals(4, order.size());
        assertTrue(order.indexOf("AUTHOR") < order.indexOf("BOOK"));
        assertTrue(order.indexOf("PUBLISHER") < order.indexOf("BOOK"));
        assertTrue(order.indexOf("BOOK") < order.indexOf("BOOK_REVIEW"));
    }

    @Test
    void ignoresForeignKeysPointingOutsideMigratedTables() {
        Map<String, TableDefinition> tables = new LinkedHashMap<>();
        tables.put("BOOK", table("BOOK", fkTo("AUTHOR"), fkTo("NOT_MIGRATED")));

        List<String> order = TableOrderResolver.resolve(tables);

        assertEquals(List.of("BOOK"), order);
    }

    @Test
    void handlesCyclicForeignKeysWithoutThrowingAndKeepsAllTables() {
        Map<String, TableDefinition> tables = new LinkedHashMap<>();
        tables.put("A", table("A", fkTo("B")));
        tables.put("B", table("B", fkTo("A")));

        List<String> order = TableOrderResolver.resolve(tables);

        assertEquals(2, order.size());
        assertTrue(order.contains("A"));
        assertTrue(order.contains("B"));
    }

    @Test
    void handlesTablesWithoutAnyForeignKeys() {
        Map<String, TableDefinition> tables = new LinkedHashMap<>();
        tables.put("X", table("X"));
        tables.put("Y", table("Y"));

        List<String> order = TableOrderResolver.resolve(tables);

        assertEquals(2, order.size());
        assertTrue(order.containsAll(List.of("X", "Y")));
    }
}
