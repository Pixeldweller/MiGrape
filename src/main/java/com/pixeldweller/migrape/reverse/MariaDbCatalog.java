package com.pixeldweller.migrape.reverse;

import com.pixeldweller.migrape.db.DbDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tabellen, Sequenzen und Spalten der MariaDB-Quelle, gelesen aus INFORMATION_SCHEMA der
 *  aktuellen Datenbank. Bewusst exakte Vergleiche statt der LIKE-Patterns von DatabaseMetaData. */
final class MariaDbCatalog {

    private final Connection maria;
    final CaseInsensitiveNames tables;
    final CaseInsensitiveNames sequences;
    /** Naechster AUTO_INCREMENT-Wert je Tabelle, nur fuer Tabellen, die einen haben. */
    private final Map<String, Long> autoIncrement;

    private MariaDbCatalog(Connection maria, List<String> tables, List<String> sequences,
                           Map<String, Long> autoIncrement) {
        this.maria = maria;
        this.tables = new CaseInsensitiveNames(tables);
        this.sequences = new CaseInsensitiveNames(sequences);
        this.autoIncrement = autoIncrement;
    }

    static MariaDbCatalog read(Connection maria) throws SQLException {
        List<String> tables = new ArrayList<>();
        List<String> sequences = new ArrayList<>();
        Map<String, Long> autoIncrement = new HashMap<>();
        String sql = "SELECT TABLE_NAME, TABLE_TYPE, AUTO_INCREMENT FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME";
        try (Statement stmt = maria.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                String type = rs.getString("TABLE_TYPE");
                if ("SEQUENCE".equals(type)) {
                    sequences.add(name);
                } else if ("BASE TABLE".equals(type)) {
                    tables.add(name);
                    long next = rs.getLong("AUTO_INCREMENT");
                    if (!rs.wasNull()) {
                        autoIncrement.put(name, next);
                    }
                }
            }
        }
        return new MariaDbCatalog(maria, tables, sequences, autoIncrement);
    }

    /** Spaltennamen in Tabellenreihenfolge. */
    List<String> columns(String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = maria.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }

    Long autoIncrement(String table) {
        return autoIncrement.get(table);
    }

    /** Naechster Wert einer Sequenz, ohne einen zu verbrauchen: eine MariaDB-Sequenz ist als
     *  Tabelle lesbar. Hat der Server Werte vorab reserviert (CACHE), liegt next_not_cached_value
     *  hinter den reservierten -- es entsteht hoechstens eine Luecke, aber nie ein Doppel. */
    long nextSequenceValue(String sequence) throws SQLException {
        String sql = "SELECT next_not_cached_value FROM " + DbDialect.MARIADB.quote(sequence);
        try (Statement stmt = maria.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    long count(String table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + DbDialect.MARIADB.quote(table);
        try (Statement stmt = maria.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
