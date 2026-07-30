package com.pixeldweller.migrape.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Alle Eingaben hier entsprechen dem, was H2 2.2.224 ueber DatabaseMetaData tatsaechlich
 * meldet -- inklusive der ueberraschenden Faelle (UUID kommt als Types.BINARY, eine
 * FLOAT-Spalte als Types.FLOAT mit TYPE_NAME "DOUBLE PRECISION", ENUM als Types.OTHER).
 * Erfundene Kombinationen wie (Types.OTHER, "UUID") wuerden hier gruen sein, ohne dass
 * die Migration funktioniert; {@link H2MetadataTypeMappingTest} sichert die Eingaben
 * zusaetzlich gegen eine echte H2-Datenbank ab.
 */
class TypeMapperTest {

    private static ColumnDefinition col(int jdbcType, String typeName, int size, int digits) {
        return new ColumnDefinition("COL", jdbcType, typeName, size, digits, true, false, 1, List.of(), null);
    }

    private static ColumnDefinition enumCol(String... values) {
        return new ColumnDefinition("COL", Types.OTHER, "ENUM(...)", 1, 0, true, false, 1,
                List.of(values), null);
    }

    // ---- Zahlen ----

    @Test
    void mapsIntegerTypes() {
        assertEquals("TINYINT", TypeMapper.toMariaDbType(col(Types.TINYINT, "TINYINT", 8, 0)));
        assertEquals("SMALLINT", TypeMapper.toMariaDbType(col(Types.SMALLINT, "SMALLINT", 16, 0)));
        assertEquals("INT", TypeMapper.toMariaDbType(col(Types.INTEGER, "INTEGER", 32, 0)));
        assertEquals("BIGINT", TypeMapper.toMariaDbType(col(Types.BIGINT, "BIGINT", 64, 0)));
    }

    @Test
    @DisplayName("H2 REAL ist 4 Byte -> FLOAT, H2 FLOAT/DOUBLE sind 8 Byte -> DOUBLE")
    void mapsFloatingPointWithoutLosingPrecision() {
        // H2 meldet REAL als Types.REAL (4 Byte)
        assertEquals("FLOAT", TypeMapper.toMariaDbType(col(Types.REAL, "REAL", 24, 0)));
        // H2 meldet eine FLOAT-Spalte als DOUBLE PRECISION mit DATA_TYPE=Types.FLOAT (8 Byte).
        // Eine Abbildung auf MariaDB FLOAT wuerde hier still Genauigkeit verlieren.
        assertEquals("DOUBLE", TypeMapper.toMariaDbType(col(Types.FLOAT, "DOUBLE PRECISION", 53, 0)));
        assertEquals("DOUBLE", TypeMapper.toMariaDbType(col(Types.DOUBLE, "DOUBLE PRECISION", 53, 0)));
    }

    @Test
    void mapsDecimalWithPrecisionAndScale() {
        assertEquals("DECIMAL(10,2)", TypeMapper.toMariaDbType(col(Types.DECIMAL, "DECIMAL", 10, 2)));
        assertEquals("DECIMAL(6,3)", TypeMapper.toMariaDbType(col(Types.NUMERIC, "DECIMAL", 6, 3)));
    }

    @Test
    @DisplayName("DECIMAL ohne Praezision: H2 meldet 100000 Stellen, MariaDB erlaubt 65")
    void clampsDecimalPrecisionToMariaDbLimit() {
        assertEquals("DECIMAL(65,0)", TypeMapper.toMariaDbType(col(Types.DECIMAL, "DECIMAL", 100000, 0)));
    }

    @Test
    @DisplayName("MariaDB erlaubt maximal Skala 30 -- DECIMAL(40,35) waere ungueltiges DDL")
    void clampsDecimalScaleToMariaDbLimit() {
        assertEquals("DECIMAL(40,30)", TypeMapper.toMariaDbType(col(Types.DECIMAL, "DECIMAL", 40, 35)));
    }

    @Test
    void clampsScaleThatExceedsPrecision() {
        assertEquals("DECIMAL(5,5)", TypeMapper.toMariaDbType(col(Types.DECIMAL, "DECIMAL", 5, 7)));
    }

    @Test
    void mapsBooleanToTinyintOne() {
        assertEquals("TINYINT(1)", TypeMapper.toMariaDbType(col(Types.BOOLEAN, "BOOLEAN", 1, 0)));
    }

    // ---- Datum und Zeit ----

    @Test
    void mapsDateAndTime() {
        assertEquals("DATE", TypeMapper.toMariaDbType(col(Types.DATE, "DATE", 10, 0)));
        assertEquals("TIME", TypeMapper.toMariaDbType(col(Types.TIME, "TIME", 8, 0)));
        assertEquals("TIME(3)", TypeMapper.toMariaDbType(col(Types.TIME, "TIME", 12, 3)));
    }

    @Test
    void mapsTimestampWithItsFractionalSecondPrecision() {
        assertEquals("DATETIME(6)", TypeMapper.toMariaDbType(col(Types.TIMESTAMP, "TIMESTAMP", 26, 6)));
        assertEquals("DATETIME", TypeMapper.toMariaDbType(col(Types.TIMESTAMP, "TIMESTAMP", 19, 0)));
        assertEquals("DATETIME(3)", TypeMapper.toMariaDbType(col(Types.TIMESTAMP, "TIMESTAMP", 23, 3)));
    }

    @Test
    @DisplayName("Mehr als 6 Sekundenbruchteil-Stellen kennt MariaDB nicht")
    void clampsFractionalSecondsToSix() {
        assertEquals("DATETIME(6)", TypeMapper.toMariaDbType(col(Types.TIMESTAMP, "TIMESTAMP", 29, 9)));
    }

    @Test
    void mapsTimestampWithTimeZoneToDatetime() {
        assertEquals("DATETIME(6)", TypeMapper.toMariaDbType(
                col(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE", 32, 6)));
    }

    // ---- Text ----

    @Test
    void mapsShortVarcharAsIs() {
        assertEquals("VARCHAR(100)", TypeMapper.toMariaDbType(col(Types.VARCHAR, "CHARACTER VARYING", 100, 0)));
        assertEquals("VARCHAR(16383)", TypeMapper.toMariaDbType(col(Types.VARCHAR, "CHARACTER VARYING", 16383, 0)));
    }

    @Test
    @DisplayName("VARCHAR ohne Laengenangabe meldet 1000000000 -- TEXT wuerde bei 64 KB abschneiden")
    void mapsUnboundedVarcharToLongtextNotText() {
        assertEquals("LONGTEXT", TypeMapper.toMariaDbType(
                col(Types.VARCHAR, "CHARACTER VARYING", 1_000_000_000, 0)));
        assertEquals("LONGTEXT", TypeMapper.toMariaDbType(col(Types.VARCHAR, "CHARACTER VARYING", 20000, 0)));
        assertEquals("LONGTEXT", TypeMapper.toMariaDbType(col(Types.VARCHAR, "CHARACTER VARYING", 0, 0)));
    }

    @Test
    void mapsClobToLongtext() {
        assertEquals("LONGTEXT", TypeMapper.toMariaDbType(
                col(Types.CLOB, "CHARACTER LARGE OBJECT", Integer.MAX_VALUE, 0)));
    }

    @Test
    void mapsCharWithinMariaDbLimit() {
        assertEquals("CHAR(2)", TypeMapper.toMariaDbType(col(Types.CHAR, "CHARACTER", 2, 0)));
        assertEquals("CHAR(255)", TypeMapper.toMariaDbType(col(Types.CHAR, "CHARACTER", 255, 0)));
    }

    @Test
    @DisplayName("CHAR ueber 255 Zeichen ist in MariaDB ungueltig -> VARCHAR bzw. LONGTEXT")
    void downgradesOversizedChar() {
        assertEquals("VARCHAR(1000)", TypeMapper.toMariaDbType(col(Types.CHAR, "CHARACTER", 1000, 0)));
        assertEquals("LONGTEXT", TypeMapper.toMariaDbType(col(Types.CHAR, "CHARACTER", 20000, 0)));
    }

    // ---- Binaer ----

    @Test
    void mapsBlobAndBinary() {
        assertEquals("LONGBLOB", TypeMapper.toMariaDbType(
                col(Types.BLOB, "BINARY LARGE OBJECT", Integer.MAX_VALUE, 0)));
        assertEquals("BINARY(16)", TypeMapper.toMariaDbType(col(Types.BINARY, "BINARY", 16, 0)));
        assertEquals("VARBINARY(100)", TypeMapper.toMariaDbType(col(Types.VARBINARY, "BINARY VARYING", 100, 0)));
    }

    @Test
    void downgradesOversizedBinary() {
        assertEquals("VARBINARY(1000)", TypeMapper.toMariaDbType(col(Types.BINARY, "BINARY", 1000, 0)));
        assertEquals("LONGBLOB", TypeMapper.toMariaDbType(col(Types.BINARY, "BINARY", 100000, 0)));
        assertEquals("LONGBLOB", TypeMapper.toMariaDbType(col(Types.VARBINARY, "BINARY VARYING", 100000, 0)));
    }

    // ---- Sondertypen ----

    @Test
    @DisplayName("UUID meldet H2 als Types.BINARY -- der BINARY-Zweig darf nicht zuerst greifen")
    void mapsUuidToChar36() {
        assertEquals("CHAR(36)", TypeMapper.toMariaDbType(col(Types.BINARY, "UUID", 16, 0)));
    }

    @Test
    @DisplayName("ENUM meldet H2 als Types.OTHER mit den Labels in TYPE_NAME")
    void mapsEnumWithItsLabels() {
        assertEquals("ENUM('DRAFT', 'PUBLISHED', 'ARCHIVED')",
                TypeMapper.toMariaDbType(enumCol("DRAFT", "PUBLISHED", "ARCHIVED")));
    }

    @Test
    void escapesQuotesAndBackslashesInEnumLabels() {
        assertEquals("ENUM('PUB''LISHED')", TypeMapper.toMariaDbType(enumCol("PUB'LISHED")));
        // MariaDB behandelt Backslash standardmaessig als Escape-Zeichen, H2 nicht
        assertEquals("ENUM('A\\\\B')", TypeMapper.toMariaDbType(enumCol("A\\B")));
    }

    @Test
    void fallsBackToVarcharWhenEnumLabelsAreUnknown() {
        ColumnDefinition column = new ColumnDefinition("COL", Types.OTHER, "ENUM('A', 'B')",
                1, 0, true, false, 1, List.of(), null);
        assertEquals("VARCHAR(255)", TypeMapper.toMariaDbType(column));
    }

    @Test
    void mapsArrayToLongtextForJsonSerialisation() {
        assertEquals("LONGTEXT", TypeMapper.toMariaDbType(col(Types.ARRAY, "INTEGER ARRAY", 65536, 0)));
    }

    @Test
    void fallsBackToLongtextForUnknownTypes() {
        assertEquals("LONGTEXT", TypeMapper.toMariaDbType(col(Types.OTHER, "GEOMETRY", 0, 0)));
    }

    // ---- Hilfsfunktionen fuer den SchemaWriter ----

    @Test
    void detectsTypesThatNeedAKeyPrefix() {
        assertTrue(TypeMapper.requiresKeyPrefix("LONGTEXT"));
        assertTrue(TypeMapper.requiresKeyPrefix("TEXT"));
        assertTrue(TypeMapper.requiresKeyPrefix("LONGBLOB"));
        assertTrue(TypeMapper.requiresKeyPrefix("JSON"));
        assertFalse(TypeMapper.requiresKeyPrefix("VARCHAR(100)"));
        assertFalse(TypeMapper.requiresKeyPrefix("CHAR(36)"));
        assertFalse(TypeMapper.requiresKeyPrefix("BIGINT"));
    }

    @Test
    void stripsLengthFromTypeName() {
        assertEquals("VARCHAR", TypeMapper.baseTypeName("VARCHAR(100)"));
        assertEquals("DECIMAL", TypeMapper.baseTypeName("DECIMAL(10,2)"));
        assertEquals("BIGINT", TypeMapper.baseTypeName("BIGINT"));
    }
}
