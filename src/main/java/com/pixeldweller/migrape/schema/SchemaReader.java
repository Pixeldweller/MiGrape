package com.pixeldweller.migrape.schema;

import com.pixeldweller.migrape.MigrationConfig;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Liest Tabellen, Spalten, Primary Keys und Foreign Keys direkt aus DatabaseMetaData. */
public final class SchemaReader {

    private final Connection h2;
    private final MigrationConfig config;

    public SchemaReader(Connection h2, MigrationConfig config) {
        this.h2 = h2;
        this.config = config;
    }

    public Map<String, TableDefinition> readSchema() throws SQLException {
        Map<String, TableDefinition> tables = new LinkedHashMap<>();
        DatabaseMetaData meta = h2.getMetaData();

        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                if (config.isTableIncluded(tableName)) {
                    tables.put(tableName, new TableDefinition(tableName));
                }
            }
        }

        for (TableDefinition table : tables.values()) {
            readColumns(meta, table);
            readPrimaryKeys(meta, table);
            readForeignKeys(meta, table);
        }

        return tables;
    }

    private void readColumns(DatabaseMetaData meta, TableDefinition table) throws SQLException {
        try (ResultSet rs = meta.getColumns(null, null, table.name, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                int jdbcType = rs.getInt("DATA_TYPE");
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                int digits = rs.getInt("DECIMAL_DIGITS");
                boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                int ordinal = rs.getInt("ORDINAL_POSITION");
                String autoIncr = rs.getString("IS_AUTOINCREMENT");
                boolean autoIncrement = "YES".equalsIgnoreCase(autoIncr);

                table.columns.put(name, new ColumnDefinition(
                        name, jdbcType, typeName, size, digits, nullable, autoIncrement, ordinal));
            }
        }
    }

    private void readPrimaryKeys(DatabaseMetaData meta, TableDefinition table) throws SQLException {
        try (ResultSet rs = meta.getPrimaryKeys(null, null, table.name)) {
            // KEY_SEQ bestimmt die Reihenfolge innerhalb des zusammengesetzten Keys
            Map<Short, String> bySeq = new LinkedHashMap<>();
            while (rs.next()) {
                bySeq.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
            bySeq.keySet().stream().sorted().forEach(seq -> table.primaryKeyColumns.add(bySeq.get(seq)));
        }
    }

    private void readForeignKeys(DatabaseMetaData meta, TableDefinition table) throws SQLException {
        try (ResultSet rs = meta.getImportedKeys(null, null, table.name)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                String fkColumn = rs.getString("FKCOLUMN_NAME");
                String pkTable = rs.getString("PKTABLE_NAME");
                String pkColumn = rs.getString("PKCOLUMN_NAME");
                // Nur beruecksichtigen, wenn die referenzierte Tabelle ebenfalls migriert wird
                table.foreignKeys.add(new TableDefinition.ForeignKey(fkName, fkColumn, pkTable, pkColumn));
            }
        }
    }
}
