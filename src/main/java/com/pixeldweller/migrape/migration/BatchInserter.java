package com.pixeldweller.migrape.migration;

import com.pixeldweller.migrape.db.DbDialect;
import com.pixeldweller.migrape.schema.ColumnDefinition;
import com.pixeldweller.migrape.schema.TableDefinition;
import com.pixeldweller.migrape.util.Log;

import java.sql.*;
import java.util.List;

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
        String quotedH2Table = DbDialect.H2.quote(table.name);
        String quotedMariaTable = DbDialect.MARIADB.quote(table.name);

        String columnList = String.join(", ", columns.stream().map(c -> c.name).toList());
        String selectSql = "SELECT " + columnList + " FROM " + quotedH2Table;

        String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
        String insertSql = "INSERT INTO " + quotedMariaTable + " (" +
                String.join(", ", columns.stream().map(c -> DbDialect.MARIADB.quote(c.name)).toList()) +
                ") VALUES (" + placeholders + ")";

        long total = countRows(table.name);
        long copied = 0;
        int pending = 0;

        try (Statement selectStmt = h2.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement insertStmt = maria.prepareStatement(insertSql)) {

            selectStmt.setFetchSize(fetchSize);
            try (ResultSet rs = selectStmt.executeQuery(selectSql)) {
                while (rs.next()) {
                    for (int i = 0; i < columns.size(); i++) {
                        insertStmt.setObject(i + 1, rs.getObject(i + 1));
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
        progress.finish(table.name, copied);
        return copied;
    }

    private long countRows(String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + DbDialect.H2.quote(tableName);
        try (Statement stmt = h2.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
