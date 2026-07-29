package com.pixeldweller.migrape.schema;

public final class ColumnDefinition {

    public final String name;
    public final int jdbcType;      // java.sql.Types
    public final String typeName;   // H2-natives Typ-Label, z.B. "VARCHAR", "CLOB"
    public final int columnSize;
    public final int decimalDigits;
    public final boolean nullable;
    public final boolean autoIncrement;
    public final int ordinalPosition;

    public ColumnDefinition(String name, int jdbcType, String typeName, int columnSize,
                             int decimalDigits, boolean nullable, boolean autoIncrement,
                             int ordinalPosition) {
        this.name = name;
        this.jdbcType = jdbcType;
        this.typeName = typeName;
        this.columnSize = columnSize;
        this.decimalDigits = decimalDigits;
        this.nullable = nullable;
        this.autoIncrement = autoIncrement;
        this.ordinalPosition = ordinalPosition;
    }
}
