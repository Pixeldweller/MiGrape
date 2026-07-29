package com.pixeldweller.migrape.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TableDefinition {

    public final String name;
    /** Erhaelt die Spaltenreihenfolge aus H2 (LinkedHashMap). */
    public final Map<String, ColumnDefinition> columns = new LinkedHashMap<>();
    public final List<String> primaryKeyColumns = new ArrayList<>();
    public final List<ForeignKey> foreignKeys = new ArrayList<>();

    public TableDefinition(String name) {
        this.name = name;
    }

    public List<ColumnDefinition> orderedColumns() {
        List<ColumnDefinition> list = new ArrayList<>(columns.values());
        list.sort((a, b) -> Integer.compare(a.ordinalPosition, b.ordinalPosition));
        return list;
    }

    public record ForeignKey(String fkName, String fkColumn, String referencedTable, String referencedColumn) {
    }
}
