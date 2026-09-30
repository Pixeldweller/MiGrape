package com.pixeldweller.migrape.reverse;

import com.pixeldweller.migrape.db.Lobs;
import com.pixeldweller.migrape.schema.ColumnDefinition;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

/** Liest einen MariaDB-Wert so, dass er in die H2-Zielspalte passt.
 *
 *  Massgeblich ist der Typ der <em>H2-Spalte</em> -- sie stammt aus dem Entity-Modell der
 *  Anwendung und ist damit das, was Hibernate erwartet. Die Umwandlungen kehren genau die der
 *  Hinrichtung um (siehe TypeMapper/BatchInserter):
 *  <ul>
 *    <li>UUID: in MariaDB CHAR(36) (oder BINARY(16)/UUID, falls Hibernate die Tabelle angelegt hat)</li>
 *    <li>BOOLEAN: in MariaDB TINYINT(1) bzw. BIT(1)</li>
 *    <li>ARRAY: in MariaDB ein JSON-Array in LONGTEXT</li>
 *    <li>JSON: muss als Bytes an H2 gehen -- als String gebunden wuerde H2 das ganze Dokument
 *        als JSON-<em>String</em> speichern ({@code "{\"a\":1}"} statt {@code {"a":1}})</li>
 *    <li>TIMESTAMP/TIME WITH TIME ZONE: in MariaDB zonenlos in UTC, wird mit Offset +00:00
 *        zurueckgeschrieben (der urspruengliche Offset ist nicht mehr bekannt)</li>
 *    <li>Datum/Zeit generell ueber java.time, damit keine Zeitzone der JVM hineinrechnet</li>
 *  </ul>
 */
final class ReverseValues {

    private ReverseValues() {
    }

    static Object read(ResultSet rs, int index, ColumnDefinition target) throws SQLException {
        String typeName = target.typeName == null ? "" : target.typeName.trim().toUpperCase(Locale.ROOT);
        if (typeName.equals("UUID")) {
            return toUuid(rs.getObject(index));
        }
        if (typeName.equals("JSON")) {
            return toJsonBytes(rs.getObject(index));
        }
        return switch (target.jdbcType) {
            case Types.TIMESTAMP -> rs.getObject(index, LocalDateTime.class);
            case Types.TIMESTAMP_WITH_TIMEZONE -> {
                LocalDateTime utc = rs.getObject(index, LocalDateTime.class);
                yield utc == null ? null : utc.atOffset(ZoneOffset.UTC);
            }
            case Types.DATE -> rs.getObject(index, LocalDate.class);
            case Types.TIME -> rs.getObject(index, LocalTime.class);
            case Types.TIME_WITH_TIMEZONE -> {
                LocalTime utc = rs.getObject(index, LocalTime.class);
                yield utc == null ? null : utc.atOffset(ZoneOffset.UTC);
            }
            case Types.BOOLEAN, Types.BIT -> toBoolean(rs.getObject(index));
            case Types.ARRAY -> toArray(rs.getObject(index), target);
            default -> materialize(rs.getObject(index));
        };
    }

    private static Object toUuid(Object value) throws SQLException {
        value = materialize(value);
        if (value instanceof String text) {
            return UUID.fromString(text.trim());
        }
        if (value instanceof byte[] bytes && bytes.length == 16) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        return value;
    }

    private static Object toJsonBytes(Object value) throws SQLException {
        value = materialize(value);
        if (value instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        return value;
    }

    private static Object toBoolean(Object value) throws SQLException {
        value = materialize(value);
        if (value instanceof BigDecimal decimal) {
            return decimal.signum() != 0;
        }
        if (value instanceof Number number) {
            return number.longValue() != 0;
        }
        if (value instanceof byte[] bytes) {
            for (byte b : bytes) {
                if (b != 0) {
                    return true;
                }
            }
            return false;
        }
        return value;
    }

    private static Object toArray(Object value, ColumnDefinition target) throws SQLException {
        if (value instanceof java.sql.Array array) {
            return array.getArray();
        }
        value = materialize(value);
        if (value instanceof byte[] bytes) {
            value = new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof String text) {
            try {
                return JsonArrayParser.parse(text);
            } catch (IllegalArgumentException e) {
                throw new SQLException("Spalte '" + target.name + "': " + e.getMessage(), e);
            }
        }
        return value;
    }

    private static Object materialize(Object value) throws SQLException {
        if (value instanceof Clob clob) {
            return Lobs.readClob(clob);
        }
        if (value instanceof Blob blob) {
            return Lobs.readBlob(blob);
        }
        return value;
    }
}
