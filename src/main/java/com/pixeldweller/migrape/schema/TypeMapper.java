package com.pixeldweller.migrape.schema;

import com.pixeldweller.migrape.util.Log;

import java.sql.Types;

/** Uebersetzt H2-Spaltentypen in MariaDB-DDL-Typen. */
public final class TypeMapper {

    private TypeMapper() {
    }

    public static String toMariaDbType(ColumnDefinition col) {
        String h2Type = col.typeName == null ? "" : col.typeName.toUpperCase();

        return switch (col.jdbcType) {
            case Types.BOOLEAN, Types.BIT -> "TINYINT(1)";
            case Types.TINYINT -> "TINYINT";
            case Types.SMALLINT -> "SMALLINT";
            case Types.INTEGER -> "INT";
            case Types.BIGINT -> "BIGINT";
            case Types.REAL, Types.FLOAT -> "FLOAT";
            case Types.DOUBLE -> "DOUBLE";
            case Types.DECIMAL, Types.NUMERIC -> decimalType(col);
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "DATETIME(6)";
            case Types.CHAR -> "CHAR(" + Math.max(col.columnSize, 1) + ")";
            case Types.VARCHAR -> varcharType(col, h2Type);
            case Types.CLOB, Types.LONGVARCHAR -> "LONGTEXT";
            case Types.BLOB, Types.LONGVARBINARY -> "LONGBLOB";
            case Types.VARBINARY -> col.columnSize > 0 && col.columnSize <= 65535
                    ? "VARBINARY(" + col.columnSize + ")" : "LONGBLOB";
            case Types.BINARY -> "BINARY(" + Math.max(col.columnSize, 1) + ")";
            case Types.ARRAY -> {
                Log.warn("Spalte '" + col.name + "': H2-ARRAY wird nicht nativ unterstuetzt, verwende LONGTEXT (JSON-Serialisierung empfohlen)");
                yield "LONGTEXT";
            }
            default -> {
                if (h2Type.contains("UUID")) {
                    yield "CHAR(36)";
                }
                Log.warn("Spalte '" + col.name + "': unbekannter Typ '" + h2Type + "' (JDBC " + col.jdbcType + "), verwende LONGTEXT als Fallback");
                yield "LONGTEXT";
            }
        };
    }

    private static String decimalType(ColumnDefinition col) {
        int precision = Math.min(Math.max(col.columnSize, 1), 65);
        int scale = Math.max(col.decimalDigits, 0);
        if (scale > precision) {
            scale = precision;
        }
        return "DECIMAL(" + precision + "," + scale + ")";
    }

    private static String varcharType(ColumnDefinition col, String h2Type) {
        if (h2Type.contains("UUID")) {
            return "CHAR(36)";
        }
        int size = col.columnSize;
        // MariaDB VARCHAR ist auf 65535 Bytes je Zeile begrenzt (inkl. anderer Spalten) -> ab hier TEXT
        if (size <= 0 || size > 16383) {
            return "TEXT";
        }
        return "VARCHAR(" + size + ")";
    }
}
