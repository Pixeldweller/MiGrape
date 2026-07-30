package com.pixeldweller.migrape.schema;

import java.util.List;

public final class ColumnDefinition {

    public final String name;
    public final int jdbcType;      // java.sql.Types
    /** Typbezeichnung wie von H2 gemeldet. Achtung: H2 2.x liefert hier SQL-Standardnamen
     *  ("CHARACTER VARYING", "DOUBLE PRECISION") und bei ENUM-Spalten die komplette
     *  Deklaration inkl. Labels ("ENUM('A', 'B')"). */
    public final String typeName;
    public final int columnSize;
    public final int decimalDigits;
    public final boolean nullable;
    public final boolean autoIncrement;
    public final int ordinalPosition;
    /** ENUM-Labels in Deklarationsreihenfolge; leer fuer alle anderen Typen. */
    public final List<String> enumValues;
    /** DEFAULT-Ausdruck aus INFORMATION_SCHEMA.COLUMNS.COLUMN_DEFAULT, oder null. */
    public final String defaultExpression;

    public ColumnDefinition(String name, int jdbcType, String typeName, int columnSize,
                            int decimalDigits, boolean nullable, boolean autoIncrement,
                            int ordinalPosition, List<String> enumValues, String defaultExpression) {
        this.name = name;
        this.jdbcType = jdbcType;
        this.typeName = typeName;
        this.columnSize = columnSize;
        this.decimalDigits = decimalDigits;
        this.nullable = nullable;
        this.autoIncrement = autoIncrement;
        this.ordinalPosition = ordinalPosition;
        this.enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        this.defaultExpression = defaultExpression;
    }

    public boolean isEnum() {
        return !enumValues.isEmpty();
    }
}
