package com.pixeldweller.migrape.schema;

import com.pixeldweller.migrape.util.Log;

import java.sql.Types;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Uebersetzt H2-Spaltentypen in MariaDB-DDL-Typen.
 *
 *  Wichtig: H2 2.x meldet ueber DatabaseMetaData SQL-Standard-Typnamen, nicht die
 *  H2-Schreibweise aus dem CREATE TABLE. Konkret gemessen (H2 2.2.224):
 *  <pre>
 *    Deklaration                 TYPE_NAME                  DATA_TYPE
 *    UUID                        UUID                       -2  (BINARY!)
 *    ENUM('A','B')               ENUM('A', 'B')             1111 (OTHER)
 *    FLOAT                       DOUBLE PRECISION           6   (FLOAT)
 *    DOUBLE                      DOUBLE PRECISION           8   (DOUBLE)
 *    VARCHAR (ohne Laenge)       CHARACTER VARYING          12, COLUMN_SIZE=1000000000
 *    CLOB                        CHARACTER LARGE OBJECT     2005
 *  </pre>
 *  Deshalb werden UUID und ENUM <em>vor</em> der Auswertung von jdbcType behandelt: sonst
 *  greift bei UUID der BINARY-Zweig und bei ENUM der Fallback. */
public final class TypeMapper {

    /** MariaDB begrenzt eine Zeile auf 65535 Byte; unter utf8mb4 sind das 16383 Zeichen. */
    private static final int MAX_VARCHAR_CHARS = 16383;
    private static final int MAX_CHAR_CHARS = 255;
    private static final int MAX_BINARY_BYTES = 255;
    private static final int MAX_VARBINARY_BYTES = 65535;
    private static final int MAX_DECIMAL_PRECISION = 65;
    private static final int MAX_DECIMAL_SCALE = 30;
    private static final int MAX_FRACTIONAL_SECONDS = 6;

    /** MariaDB-Typen, die in einem Index nur mit Praefixlaenge verwendet werden koennen und
     *  kein einfaches DEFAULT erlauben. */
    private static final Set<String> LOB_TYPES = Set.of(
            "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT",
            "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "JSON");

    private TypeMapper() {
    }

    public static String toMariaDbType(ColumnDefinition col) {
        String h2Type = col.typeName == null ? "" : col.typeName.toUpperCase(Locale.ROOT).trim();

        // ENUM und UUID muessen vor dem jdbcType-Switch geprueft werden, siehe Klassen-Javadoc.
        if (col.isEnum()) {
            return enumType(col);
        }
        if (h2Type.startsWith("ENUM")) {
            Log.warn("Spalte '" + col.name + "': ENUM-Labels konnten nicht ermittelt werden, "
                    + "verwende VARCHAR(255) als Fallback");
            return "VARCHAR(255)";
        }
        if (h2Type.equals("UUID")) {
            return "CHAR(36)";
        }
        if (h2Type.equals("JSON")) {
            return "JSON";
        }

        return switch (col.jdbcType) {
            case Types.BOOLEAN, Types.BIT -> "TINYINT(1)";
            case Types.TINYINT -> "TINYINT";
            case Types.SMALLINT -> "SMALLINT";
            case Types.INTEGER -> "INT";
            case Types.BIGINT -> "BIGINT";
            // Types.REAL = 4 Byte. Types.FLOAT ist laut SQL-Standard doppelt genau -- H2 meldet
            // eine FLOAT-Spalte als DOUBLE PRECISION mit DATA_TYPE=FLOAT. Auf MariaDB FLOAT
            // abzubilden wuerde 8 Byte auf 4 Byte verkuerzen.
            case Types.REAL -> "FLOAT";
            case Types.FLOAT, Types.DOUBLE -> "DOUBLE";
            case Types.DECIMAL, Types.NUMERIC -> decimalType(col);
            case Types.DATE -> "DATE";
            case Types.TIME -> withPrecision("TIME", col);
            case Types.TIME_WITH_TIMEZONE -> {
                Log.warn("Spalte '" + col.name + "': TIME WITH TIME ZONE -> TIME, "
                        + "die Zeitzone wird beim Kopieren nach UTC normalisiert");
                yield withPrecision("TIME", col);
            }
            case Types.TIMESTAMP -> withPrecision("DATETIME", col);
            case Types.TIMESTAMP_WITH_TIMEZONE -> {
                Log.warn("Spalte '" + col.name + "': TIMESTAMP WITH TIME ZONE -> DATETIME, "
                        + "die Werte werden beim Kopieren nach UTC normalisiert (Offset entfaellt)");
                yield withPrecision("DATETIME", col);
            }
            case Types.CHAR, Types.NCHAR -> charType(col);
            case Types.VARCHAR, Types.NVARCHAR -> varcharType(col);
            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> "LONGTEXT";
            case Types.BLOB, Types.LONGVARBINARY -> "LONGBLOB";
            case Types.BINARY -> binaryType(col);
            case Types.VARBINARY -> varbinaryType(col);
            case Types.ARRAY -> {
                Log.warn("Spalte '" + col.name + "': H2-ARRAY wird nicht nativ unterstuetzt, "
                        + "die Werte werden als JSON-Array in LONGTEXT geschrieben");
                yield "LONGTEXT";
            }
            default -> {
                Log.warn("Spalte '" + col.name + "': unbekannter Typ '" + h2Type + "' (JDBC "
                        + col.jdbcType + "), verwende LONGTEXT als Fallback");
                yield "LONGTEXT";
            }
        };
    }

    /** true, wenn der Zieltyp in Indizes eine Praefixlaenge braucht (TEXT/BLOB/JSON). */
    public static boolean requiresKeyPrefix(String mariaDbType) {
        return LOB_TYPES.contains(baseTypeName(mariaDbType));
    }

    /** Nackter Typname ohne Laengenangabe, z.B. "VARCHAR(20)" -> "VARCHAR". */
    public static String baseTypeName(String mariaDbType) {
        if (mariaDbType == null) {
            return "";
        }
        int paren = mariaDbType.indexOf('(');
        String base = paren >= 0 ? mariaDbType.substring(0, paren) : mariaDbType;
        return base.trim().toUpperCase(Locale.ROOT);
    }

    /** Sekundenbruchteile aus DECIMAL_DIGITS, auf die von MariaDB unterstuetzten 0..6 begrenzt. */
    private static String withPrecision(String baseType, ColumnDefinition col) {
        int digits = Math.max(col.decimalDigits, 0);
        if (digits > MAX_FRACTIONAL_SECONDS) {
            Log.warn("Spalte '" + col.name + "': " + digits + " Sekundenbruchteil-Stellen werden auf "
                    + MAX_FRACTIONAL_SECONDS + " gekuerzt (MariaDB-Limit)");
            digits = MAX_FRACTIONAL_SECONDS;
        }
        return digits == 0 ? baseType : baseType + "(" + digits + ")";
    }

    /** MariaDB versteht dieselbe ENUM('a','b',...)-Syntax wie H2. Die Labels kommen exakt aus
     *  INFORMATION_SCHEMA.ENUM_VALUES, werden hier also nur noch fuer MariaDB escaped
     *  (Hochkomma verdoppeln, Backslash verdoppeln -- MariaDB behandelt \ standardmaessig
     *  als Escape-Zeichen, H2 nicht). */
    private static String enumType(ColumnDefinition col) {
        return col.enumValues.stream()
                .map(v -> "'" + v.replace("\\", "\\\\").replace("'", "''") + "'")
                .collect(Collectors.joining(", ", "ENUM(", ")"));
    }

    private static String decimalType(ColumnDefinition col) {
        int precision = col.columnSize;
        int scale = Math.max(col.decimalDigits, 0);

        if (precision > MAX_DECIMAL_PRECISION) {
            // H2 meldet fuer DECIMAL ohne Praezision 100000. Werte mit mehr als 65 Stellen
            // wuerden auf MariaDB nicht mehr passen -- das ist in der Praxis nie der Fall,
            // muss aber sichtbar sein.
            Log.warn("Spalte '" + col.name + "': Praezision " + precision + " wird auf "
                    + MAX_DECIMAL_PRECISION + " begrenzt (MariaDB-Limit); Werte mit mehr Stellen "
                    + "wuerden abgewiesen");
            precision = MAX_DECIMAL_PRECISION;
        }
        if (precision < 1) {
            precision = MAX_DECIMAL_PRECISION;
        }
        if (scale > MAX_DECIMAL_SCALE) {
            Log.warn("Spalte '" + col.name + "': Skala " + scale + " wird auf " + MAX_DECIMAL_SCALE
                    + " begrenzt (MariaDB-Limit), Nachkommastellen gehen verloren");
            scale = MAX_DECIMAL_SCALE;
        }
        if (scale > precision) {
            scale = precision;
        }
        return "DECIMAL(" + precision + "," + scale + ")";
    }

    private static String charType(ColumnDefinition col) {
        int size = col.columnSize;
        if (size <= 0) {
            return "CHAR(1)";
        }
        if (size <= MAX_CHAR_CHARS) {
            return "CHAR(" + size + ")";
        }
        Log.warn("Spalte '" + col.name + "': CHAR(" + size + ") uebersteigt das MariaDB-Limit von "
                + MAX_CHAR_CHARS + " Zeichen, verwende " + (size <= MAX_VARCHAR_CHARS ? "VARCHAR" : "LONGTEXT")
                + " (rechts auffuellende Leerzeichen entfallen)");
        return size <= MAX_VARCHAR_CHARS ? "VARCHAR(" + size + ")" : "LONGTEXT";
    }

    private static String varcharType(ColumnDefinition col) {
        int size = col.columnSize;
        if (size > 0 && size <= MAX_VARCHAR_CHARS) {
            return "VARCHAR(" + size + ")";
        }
        // H2-VARCHAR ohne Laengenangabe meldet 1000000000. TEXT wuerde bei 65535 Byte
        // abschneiden, deshalb direkt LONGTEXT.
        return "LONGTEXT";
    }

    private static String binaryType(ColumnDefinition col) {
        int size = col.columnSize;
        if (size <= 0) {
            return "BINARY(1)";
        }
        if (size <= MAX_BINARY_BYTES) {
            return "BINARY(" + size + ")";
        }
        Log.warn("Spalte '" + col.name + "': BINARY(" + size + ") uebersteigt das MariaDB-Limit von "
                + MAX_BINARY_BYTES + " Byte, verwende "
                + (size <= MAX_VARBINARY_BYTES ? "VARBINARY" : "LONGBLOB"));
        return size <= MAX_VARBINARY_BYTES ? "VARBINARY(" + size + ")" : "LONGBLOB";
    }

    private static String varbinaryType(ColumnDefinition col) {
        int size = col.columnSize;
        return size > 0 && size <= MAX_VARBINARY_BYTES ? "VARBINARY(" + size + ")" : "LONGBLOB";
    }
}
