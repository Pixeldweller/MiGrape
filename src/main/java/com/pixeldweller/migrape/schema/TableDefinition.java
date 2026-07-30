package com.pixeldweller.migrape.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TableDefinition {

    public final String name;
    /** Erhaelt die Spaltenreihenfolge aus H2 (LinkedHashMap). */
    public final Map<String, ColumnDefinition> columns = new LinkedHashMap<>();
    public final List<String> primaryKeyColumns = new ArrayList<>();
    public final List<ForeignKey> foreignKeys = new ArrayList<>();
    public final List<UniqueConstraint> uniqueConstraints = new ArrayList<>();
    public final List<Index> indexes = new ArrayList<>();

    public TableDefinition(String name) {
        this.name = name;
    }

    public List<ColumnDefinition> orderedColumns() {
        List<ColumnDefinition> list = new ArrayList<>(columns.values());
        list.sort(Comparator.comparingInt(c -> c.ordinalPosition));
        return list;
    }

    /** Ein Fremdschluessel -- mehrspaltig, daher Listen. Die Reihenfolge der beiden Listen
     *  entspricht einander (KEY_SEQ aus den JDBC-Metadaten). */
    public record ForeignKey(String fkName, List<String> fkColumns,
                             String referencedTable, List<String> referencedColumns) {
        public ForeignKey {
            fkColumns = List.copyOf(fkColumns);
            referencedColumns = List.copyOf(referencedColumns);
        }
    }

    public record UniqueConstraint(String name, List<String> columns) {
        public UniqueConstraint {
            columns = List.copyOf(columns);
        }
    }

    public record Index(String name, List<String> columns) {
        public Index {
            columns = List.copyOf(columns);
        }
    }
}
