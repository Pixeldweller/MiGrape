package com.pixeldweller.migrape.migration;

import com.pixeldweller.migrape.db.DbDialect;
import com.pixeldweller.migrape.schema.ColumnDefinition;
import com.pixeldweller.migrape.schema.TableDefinition;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Kopiert die Daten einer einzelnen Tabelle von H2 nach MariaDB per Streaming-Select + Batch-Insert. */
public final class BatchInserter {

    private final Connection h2;
    private final Connection maria;
    private final int fetchSize;
    private final int batchSize;

    public BatchInserter(Connection h2, Connection maria, int fetchSize, int batchSize) {
        this.h2 = h2;
        this.maria = maria;
        this.fetchSize = fetchSize;
        this.batchSize = batchSize;
    }

    public long copy(TableDefinition table, ProgressPrinter progress) throws SQLException {
        List<ColumnDefinition> columns = table.orderedColumns();

        // Auch die SELECT-Spalten muessen gequotet werden, sonst brechen Spalten mit
        // Reserved-Word-Namen (KEY, VALUE, YEAR, ...) die Abfrage.
        String selectSql = "SELECT "
                + columns.stream().map(c -> DbDialect.H2.quote(c.name)).collect(Collectors.joining(", "))
                + " FROM " + DbDialect.H2.quote(table.name);

        String insertSql = "INSERT INTO " + DbDialect.MARIADB.quote(table.name) + " ("
                + columns.stream().map(c -> DbDialect.MARIADB.quote(c.name)).collect(Collectors.joining(", "))
                + ") VALUES ("
                + columns.stream().map(c -> "?").collect(Collectors.joining(", "))
                + ")";

        long total = countRows(table.name);
        long copied = 0;
        int pending = 0;

        try (Statement selectStmt = h2.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement insertStmt = maria.prepareStatement(insertSql)) {

            selectStmt.setFetchSize(fetchSize);
            try (ResultSet rs = selectStmt.executeQuery(selectSql)) {
                while (rs.next()) {
                    for (int i = 0; i < columns.size(); i++) {
                        insertStmt.setObject(i + 1, toTargetValue(rs.getObject(i + 1)));
                    }
                    insertStmt.addBatch();
                    pending++;
                    copied++;

                    if (pending >= batchSize) {
                        insertStmt.executeBatch();
                        maria.commit();
                        pending = 0;
                        progress.update(table.name, copied, total);
                    }
                }
                if (pending > 0) {
                    insertStmt.executeBatch();
                    maria.commit();
                }
            }
        }

        progress.update(table.name, copied, total);
        progress.finish();
        return copied;
    }

    /** Wandelt H2-Werte in Typen, die der MariaDB-Treiber schreiben kann.
     *
     *  Zwei Gruende fuer die Konvertierung:
     *  <ul>
     *    <li>LOBs werden sofort materialisiert: die Handles von H2 sind an die Position des
     *        ResultSet gebunden, gelesen wuerden sie aber erst bei executeBatch() -- also bis
     *        zu batchSize Zeilen spaeter.</li>
     *    <li>java.sql.Date/Time/Timestamp sind Zeitpunkte in Epoch-Millisekunden und damit
     *        zeitzonenabhaengig. Werden sie ueber UTC interpretiert, verschieben sich Datumswerte
     *        aus der Zeit vor der Einfuehrung der Zonenzeit um bis zu einen Tag (Europe/Berlin
     *        hatte 1815 den Offset +00:53:28). Die java.time-Typen tragen keine Zone und werden
     *        vom Treiber unveraendert als Datum/Uhrzeit geschrieben.</li>
     *  </ul>
     */
    private static Object toTargetValue(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Time time) {
            return toLocalTime(time);
        }
        if (value instanceof UUID uuid) {
            // Zieltyp ist CHAR(36), siehe TypeMapper.
            return uuid.toString();
        }
        if (value instanceof Clob clob) {
            return readClob(clob);
        }
        if (value instanceof Blob blob) {
            return readBlob(blob);
        }
        if (value instanceof java.sql.Array array) {
            return arrayToJson(array);
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            // Zieltyp DATETIME kennt keinen Offset -> auf UTC normalisieren.
            return offsetDateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
        if (value instanceof OffsetTime offsetTime) {
            return offsetTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalTime();
        }
        return value;
    }

    /** java.sql.Time.toLocalTime() verwirft die Millisekunden -- H2 liefert fuer TIME(3) aber
     *  java.sql.Time mit gesetztem Sub-Sekunden-Anteil. */
    private static java.time.LocalTime toLocalTime(Time time) {
        java.time.LocalTime local = time.toLocalTime();
        long millis = Math.floorMod(time.getTime(), 1000L);
        return millis == 0 ? local : local.withNano((int) millis * 1_000_000);
    }

    private static String readClob(Clob clob) throws SQLException {
        long length = clob.length();
        if (length > Integer.MAX_VALUE) {
            throw new SQLException("CLOB mit " + length + " Zeichen ist zu gross zum Kopieren");
        }
        try {
            return length == 0 ? "" : clob.getSubString(1, (int) length);
        } finally {
            freeQuietly(clob);
        }
    }

    private static byte[] readBlob(Blob blob) throws SQLException {
        long length = blob.length();
        if (length > Integer.MAX_VALUE) {
            throw new SQLException("BLOB mit " + length + " Byte ist zu gross zum Kopieren");
        }
        try {
            return length == 0 ? new byte[0] : blob.getBytes(1, (int) length);
        } finally {
            freeQuietly(blob);
        }
    }

    private static void freeQuietly(Object lob) {
        try {
            if (lob instanceof Clob clob) {
                clob.free();
            } else if (lob instanceof Blob blob) {
                blob.free();
            }
        } catch (SQLException | UnsupportedOperationException ignored) {
            // free() ist optional; ein nicht freigegebenes LOB ist kein Fehlerfall.
        }
    }

    /** H2-ARRAY wird als JSON-Array in eine LONGTEXT-Spalte geschrieben (siehe TypeMapper),
     *  weil der MariaDB-Treiber java.sql.Array nicht als Parameter akzeptiert. */
    private static String arrayToJson(java.sql.Array array) throws SQLException {
        Object raw = array.getArray();
        StringBuilder sb = new StringBuilder("[");
        int length = java.lang.reflect.Array.getLength(raw);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            appendJsonValue(sb, java.lang.reflect.Array.get(raw, i));
        }
        return sb.append("]").toString();
    }

    private static void appendJsonValue(StringBuilder sb, Object element) throws SQLException {
        if (element == null) {
            sb.append("null");
        } else if (element instanceof Number || element instanceof Boolean) {
            sb.append(element);
        } else if (element instanceof java.sql.Array nested) {
            sb.append(arrayToJson(nested));
        } else {
            sb.append('"')
                    .append(String.valueOf(element)
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("\t", "\\t"))
                    .append('"');
        }
    }

    private long countRows(String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + DbDialect.H2.quote(tableName);
        try (Statement stmt = h2.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
