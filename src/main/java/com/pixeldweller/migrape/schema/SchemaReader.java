package com.pixeldweller.migrape.schema;

import com.pixeldweller.migrape.MigrationConfig;
import com.pixeldweller.migrape.util.Log;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Liest Tabellen, Spalten, Primary Keys, Foreign Keys, Unique-Constraints und Indizes
 *  aus den H2-Metadaten.
 *
 *  Drei Fallstricke, die hier bewusst behandelt werden:
 *  <ul>
 *    <li>{@code getTables}/{@code getColumns} erwarten <em>LIKE-Patterns</em>, keine Namen.
 *        Ein Unterstrich im Tabellennamen (BOOK_REVIEW) ist ein Wildcard und wuerde Spalten
 *        fremder Tabellen einsammeln. Namen werden daher escaped und zusaetzlich exakt geprueft.</li>
 *    <li>Es wird genau ein Schema migriert. Ohne Einschraenkung wuerden gleichnamige Tabellen
 *        aus verschiedenen Schemas in derselben Map kollidieren.</li>
 *    <li>{@code getImportedKeys} liefert eine Zeile je FK-<em>Spalte</em>; mehrspaltige
 *        Fremdschluessel muessen ueber KEY_SEQ zusammengefasst werden.</li>
 *  </ul>
 */
public final class SchemaReader {

    private final Connection h2;
    private final MigrationConfig config;
    private final String schema;
    private final String searchStringEscape;

    public SchemaReader(Connection h2, MigrationConfig config) throws SQLException {
        this.h2 = h2;
        this.config = config;
        this.schema = resolveSchema(h2, config);
        String escape = h2.getMetaData().getSearchStringEscape();
        this.searchStringEscape = escape == null ? "" : escape;
    }

    private static String resolveSchema(Connection h2, MigrationConfig config) throws SQLException {
        if (config.h2Schema != null) {
            return config.h2Schema;
        }
        String current = h2.getSchema();
        return current == null || current.isBlank() ? "PUBLIC" : current;
    }

    public String schema() {
        return schema;
    }

    public Map<String, TableDefinition> readSchema() throws SQLException {
        Map<String, TableDefinition> tables = new LinkedHashMap<>();
        DatabaseMetaData meta = h2.getMetaData();

        try (ResultSet rs = meta.getTables(null, likePattern(schema), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (!schema.equals(rs.getString("TABLE_SCHEM"))) {
                    continue;
                }
                String tableName = rs.getString("TABLE_NAME");
                if (config.isTableIncluded(tableName)) {
                    tables.put(tableName, new TableDefinition(tableName));
                }
            }
        }

        for (TableDefinition table : tables.values()) {
            readColumns(meta, table);
            readPrimaryKeys(meta, table);
            readForeignKeys(meta, table);
            readUniqueConstraints(table);
            readIndexes(meta, table);
        }

        warnAboutCheckConstraints(tables.keySet());
        return tables;
    }

    private void readColumns(DatabaseMetaData meta, TableDefinition table) throws SQLException {
        Map<String, ColumnExtras> extras = readColumnExtras(table.name);
        Map<String, List<String>> enumsByTypeId = readEnumValues(table.name);

        try (ResultSet rs = meta.getColumns(null, likePattern(schema), likePattern(table.name), "%")) {
            while (rs.next()) {
                // Trotz Escaping exakt nachpruefen: getColumns arbeitet mit Patterns.
                if (!table.name.equals(rs.getString("TABLE_NAME"))
                        || !schema.equals(rs.getString("TABLE_SCHEM"))) {
                    continue;
                }
                String name = rs.getString("COLUMN_NAME");
                int jdbcType = rs.getInt("DATA_TYPE");
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                int digits = rs.getInt("DECIMAL_DIGITS");
                boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                int ordinal = rs.getInt("ORDINAL_POSITION");
                boolean autoIncrement = "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));

                ColumnExtras extra = extras.get(name);
                List<String> enumValues = enumValuesFor(extra, enumsByTypeId, typeName);

                table.columns.put(name, new ColumnDefinition(name, jdbcType, typeName, size, digits,
                        nullable, autoIncrement, ordinal, enumValues,
                        extra == null ? null : extra.defaultExpression));
            }
        }
    }

    /** ENUM-Labels sind in H2 2.x an zwei Stellen verfuegbar: exakt in
     *  INFORMATION_SCHEMA.ENUM_VALUES (ueber DTD_IDENTIFIER mit der Spalte verknuepft) und
     *  als Teil von TYPE_NAME ("ENUM('A', 'B')"). Primaer wird ENUM_VALUES benutzt, weil dort
     *  keine Hochkommas geparst werden muessen. */
    private List<String> enumValuesFor(ColumnExtras extra, Map<String, List<String>> enumsByTypeId,
                                       String typeName) {
        if (extra != null && extra.typeIdentifier != null) {
            List<String> values = enumsByTypeId.get(extra.typeIdentifier);
            if (values != null && !values.isEmpty()) {
                return values;
            }
        }
        return parseEnumValues(typeName);
    }

    /** Liest DEFAULT-Ausdruck und Typ-Identifier (Verknuepfung zu ENUM_VALUES) je Spalte.
     *  Bewusst mit exaktem Vergleich auf TABLE_SCHEMA/TABLE_NAME statt LIKE. */
    private Map<String, ColumnExtras> readColumnExtras(String tableName) throws SQLException {
        Map<String, ColumnExtras> result = new HashMap<>();
        String sql = "SELECT COLUMN_NAME, COLUMN_DEFAULT, DTD_IDENTIFIER "
                + "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (PreparedStatement ps = h2.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("COLUMN_NAME"),
                            new ColumnExtras(rs.getString("COLUMN_DEFAULT"), rs.getString("DTD_IDENTIFIER")));
                }
            }
        }
        return result;
    }

    private Map<String, List<String>> readEnumValues(String tableName) {
        Map<String, List<String>> result = new HashMap<>();
        String sql = "SELECT ENUM_IDENTIFIER, VALUE_NAME FROM INFORMATION_SCHEMA.ENUM_VALUES "
                + "WHERE OBJECT_SCHEMA = ? AND OBJECT_NAME = ? AND OBJECT_TYPE = 'TABLE' "
                + "ORDER BY ENUM_IDENTIFIER, VALUE_ORDINAL";
        try (PreparedStatement ps = h2.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.computeIfAbsent(rs.getString("ENUM_IDENTIFIER"), k -> new ArrayList<>())
                            .add(rs.getString("VALUE_NAME"));
                }
            }
        } catch (SQLException e) {
            // Aeltere/andere H2-Versionen kennen ENUM_VALUES nicht -> TYPE_NAME-Fallback greift.
            Log.warn("INFORMATION_SCHEMA.ENUM_VALUES nicht lesbar (" + e.getMessage()
                    + "), ENUM-Labels werden aus TYPE_NAME gelesen");
        }
        return result;
    }

    /** Parst "ENUM('A', 'B''C')" -> [A, B'C]. Fallback, wenn ENUM_VALUES nicht verfuegbar ist. */
    static List<String> parseEnumValues(String typeName) {
        if (typeName == null) {
            return List.of();
        }
        String trimmed = typeName.trim();
        if (!trimmed.toUpperCase(Locale.ROOT).startsWith("ENUM")) {
            return List.of();
        }
        int open = trimmed.indexOf('(');
        int close = trimmed.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return List.of();
        }
        String body = trimmed.substring(open + 1, close);
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inLiteral = false;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\'') {
                if (inLiteral && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                    current.append('\'');
                    i++;
                } else if (inLiteral) {
                    values.add(current.toString());
                    current.setLength(0);
                    inLiteral = false;
                } else {
                    inLiteral = true;
                }
            } else if (inLiteral) {
                current.append(ch);
            }
        }
        return values;
    }

    private void readPrimaryKeys(DatabaseMetaData meta, TableDefinition table) throws SQLException {
        // KEY_SEQ bestimmt die Reihenfolge innerhalb des zusammengesetzten Keys
        Map<Short, String> bySeq = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, schema, table.name)) {
            while (rs.next()) {
                bySeq.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        table.primaryKeyColumns.addAll(bySeq.values());
    }

    /** Zeilen mit gleichem FK_NAME gehoeren zu einem mehrspaltigen Fremdschluessel. Einzeln
     *  angelegt waeren es n falsche Constraints mit identischem Namen. */
    private void readForeignKeys(DatabaseMetaData meta, TableDefinition table) throws SQLException {
        Map<String, PendingForeignKey> byName = new LinkedHashMap<>();
        try (ResultSet rs = meta.getImportedKeys(null, schema, table.name)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                String referencedTable = rs.getString("PKTABLE_NAME");
                short keySeq = rs.getShort("KEY_SEQ");
                String key = fkName != null ? fkName
                        : "FK_" + table.name + "_" + rs.getString("FKCOLUMN_NAME");

                PendingForeignKey pending = byName.get(key);
                if (pending == null) {
                    pending = new PendingForeignKey(fkName != null ? fkName : key, referencedTable);
                    byName.put(key, pending);
                }
                pending.fkColumns.put(keySeq, rs.getString("FKCOLUMN_NAME"));
                pending.referencedColumns.put(keySeq, rs.getString("PKCOLUMN_NAME"));
            }
        }
        for (PendingForeignKey pending : byName.values()) {
            table.foreignKeys.add(new TableDefinition.ForeignKey(pending.name, pending.columnList(),
                    pending.referencedTable, pending.referencedColumnList()));
        }
    }

    /** Unique-Constraints kommen aus INFORMATION_SCHEMA, weil getIndexInfo nur die von H2
     *  generierten Index-Namen ("UQ_MAIL_INDEX_5") kennt, nicht den Constraint-Namen. */
    private void readUniqueConstraints(TableDefinition table) {
        String sql = "SELECT k.CONSTRAINT_NAME, k.COLUMN_NAME, k.ORDINAL_POSITION "
                + "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k "
                + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t "
                + "  ON t.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA "
                + " AND t.CONSTRAINT_NAME = k.CONSTRAINT_NAME "
                + "WHERE k.TABLE_SCHEMA = ? AND k.TABLE_NAME = ? AND t.CONSTRAINT_TYPE = 'UNIQUE' "
                + "ORDER BY k.CONSTRAINT_NAME, k.ORDINAL_POSITION";
        Map<String, List<String>> byName = new LinkedHashMap<>();
        try (PreparedStatement ps = h2.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table.name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byName.computeIfAbsent(rs.getString("CONSTRAINT_NAME"), k -> new ArrayList<>())
                            .add(rs.getString("COLUMN_NAME"));
                }
            }
        } catch (SQLException e) {
            Log.warn("Unique-Constraints von " + table.name + " nicht lesbar (" + e.getMessage()
                    + "), sie werden nicht migriert");
            return;
        }
        byName.forEach((name, columns) ->
                table.uniqueConstraints.add(new TableDefinition.UniqueConstraint(name, columns)));
    }

    /** Nur die "echten" Sekundaerindizes: Unique-Indizes deckt {@link #readUniqueConstraints} ab,
     *  den PK-Index legt MariaDB mit dem PRIMARY KEY an, FK-Indizes ebenfalls automatisch. */
    private void readIndexes(DatabaseMetaData meta, TableDefinition table) throws SQLException {
        Map<String, Map<Short, String>> byName = new LinkedHashMap<>();
        try (ResultSet rs = meta.getIndexInfo(null, schema, table.name, false, false)) {
            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                if (!rs.getBoolean("NON_UNIQUE")) {
                    continue;
                }
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) {
                    continue;
                }
                byName.computeIfAbsent(indexName, k -> new TreeMap<>())
                        .put(rs.getShort("ORDINAL_POSITION"), columnName);
            }
        }

        Set<List<String>> covered = new LinkedHashSet<>();
        covered.add(List.copyOf(table.primaryKeyColumns));
        for (TableDefinition.ForeignKey fk : table.foreignKeys) {
            covered.add(fk.fkColumns());
        }
        for (TableDefinition.UniqueConstraint uc : table.uniqueConstraints) {
            covered.add(uc.columns());
        }

        byName.forEach((name, columnsBySeq) -> {
            List<String> columns = List.copyOf(columnsBySeq.values());
            if (!columns.isEmpty() && !covered.contains(columns)) {
                table.indexes.add(new TableDefinition.Index(name, columns));
            }
        });
    }

    /** CHECK-Constraints werden nicht uebersetzt (H2-Ausdruecke sind nicht allgemein nach
     *  MariaDB uebertragbar), aber protokolliert, damit sie nicht stillschweigend verloren gehen. */
    private void warnAboutCheckConstraints(Set<String> migratedTables) {
        String sql = "SELECT tc.TABLE_NAME, cc.CONSTRAINT_NAME, cc.CHECK_CLAUSE "
                + "FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc "
                + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                + "  ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA "
                + " AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME "
                + "WHERE tc.TABLE_SCHEMA = ? AND tc.CONSTRAINT_TYPE = 'CHECK'";
        try (PreparedStatement ps = h2.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    if (migratedTables.contains(tableName)) {
                        Log.warn("CHECK-Constraint '" + rs.getString("CONSTRAINT_NAME") + "' auf "
                                + tableName + " wird nicht migriert: " + rs.getString("CHECK_CLAUSE"));
                    }
                }
            }
        } catch (SQLException e) {
            Log.warn("CHECK-Constraints nicht lesbar (" + e.getMessage() + ")");
        }
    }

    /** Maskiert LIKE-Sonderzeichen, damit ein Name als exaktes Pattern wirkt. */
    private String likePattern(String literal) {
        if (searchStringEscape.isEmpty()) {
            return literal;
        }
        StringBuilder sb = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char ch = literal.charAt(i);
            if (ch == '_' || ch == '%' || searchStringEscape.indexOf(ch) >= 0) {
                sb.append(searchStringEscape);
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static final class ColumnExtras {
        private final String defaultExpression;
        private final String typeIdentifier;

        private ColumnExtras(String defaultExpression, String typeIdentifier) {
            this.defaultExpression = defaultExpression;
            this.typeIdentifier = typeIdentifier;
        }
    }

    private static final class PendingForeignKey {
        private final String name;
        private final String referencedTable;
        private final Map<Short, String> fkColumns = new TreeMap<>();
        private final Map<Short, String> referencedColumns = new TreeMap<>();

        private PendingForeignKey(String name, String referencedTable) {
            this.name = name;
            this.referencedTable = referencedTable;
        }

        private List<String> columnList() {
            return List.copyOf(fkColumns.values());
        }

        private List<String> referencedColumnList() {
            return List.copyOf(referencedColumns.values());
        }
    }
}
