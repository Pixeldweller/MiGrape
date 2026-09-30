package com.pixeldweller.migrape.db;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;

/** Materialisiert LOB-Handles sofort. Die Handles sind an die Position des ResultSet gebunden,
 *  gelesen wuerden sie beim Batch-Insert aber erst bei executeBatch() -- also bis zu batchSize
 *  Zeilen spaeter. */
public final class Lobs {

    private Lobs() {
    }

    public static String readClob(Clob clob) throws SQLException {
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

    public static byte[] readBlob(Blob blob) throws SQLException {
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
}
