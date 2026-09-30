package com.pixeldweller.migrape.schema;

import com.pixeldweller.migrape.db.DbDialect;
import com.pixeldweller.migrape.db.IdentifierCase;
import com.pixeldweller.migrape.util.Log;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Erzeugt das MariaDB-Schema aus den gelesenen H2-Tabellendefinitionen.
 *
 *  Reihenfolge: erst alle Tabellen (nur Spalten + PRIMARY KEY), dann Unique-Constraints und
 *  Indizes, dann Foreign Keys. So spielt die Erstellreihenfolge keine Rolle, und ein einzelnes
 *  fehlgeschlagenes Nebenobjekt reisst nicht die ganze Tabelle mit. */
public final class SchemaWriter {

    private static final Pattern NUMERIC_LITERAL = Pattern.compile("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");
    /** Praefixlaenge fuer TEXT/BLOB-Spalten in Indizes (MariaDB verlangt eine Laenge). */
    private static final int KEY_PREFIX_LENGTH = 255;

    /** Grenzen einer MariaDB-Sequenz; H2 erlaubt jeweils einen Wert mehr. */
    private static final long MARIADB_SEQUENCE_MAX = Long.MAX_VALUE - 1;
    private static final long MARIADB_SEQUENCE_MIN = Long.MIN_VALUE + 1;

    private final Connection maria;
    private final boolean dropExisting;
    private final IdentifierCase identifierCase;
    /** Gemappte DDL-Typen je Tabelle/Spalte -- verhindert doppelte Warnungen aus dem TypeMapper. */
    private final Map<String, Map<String, String>> ddlTypes = new HashMap<>();
    private int failedObjects;

    public SchemaWriter(Connection maria, boolean dropExisting, IdentifierCase identifierCase) {
        this.maria = maria;
        this.dropExisting = dropExisting;
        this.identifierCase = identifierCase;
    }

    /** Legt die Sequenzen mit ihrem aktuellen H2-Stand an. Fehler brechen nicht ab, sondern
     *  werden wie bei den uebrigen Nebenobjekten gezaehlt und gemeldet. */
    public void createSequences(List<SequenceDefinition> sequences) throws SQLException {
        int failedBefore = failedObjects;
        for (SequenceDefinition seq : sequences) {
            if (dropExisting) {
                executeOptional("DROP SEQUENCE IF EXISTS " + quote(seq.name()),
                        "(Entfernen) Sequenz " + seq.name());
            }
            Log.info("Erzeuge Sequenz " + seq.name() + " (naechster Wert " + seq.nextValue()
                    + ", Schrittweite " + seq.increment() + ")");
            executeOptional(createSequenceSql(seq), "Sequenz " + seq.name());
        }
        maria.commit();
        if (failedObjects > failedBefore) {
            Log.warn((failedObjects - failedBefore) + " Sequenz(en) konnten nicht angelegt werden -- "
                    + "siehe Warnungen oben.");
        }
    }

    private String createSequenceSql(SequenceDefinition seq) {
        StringBuilder sql = new StringBuilder("CREATE SEQUENCE ").append(quote(seq.name()))
                .append(" START WITH ").append(seq.nextValue())
                .append(" INCREMENT BY ").append(seq.increment());
        // Die H2-Standardgrenzen (Long.MIN/MAX) liegen je einen Wert ausserhalb dessen, was
        // MariaDB akzeptiert -- dann gilt dort ohnehin der eigene Standard.
        if (seq.minValue() >= MARIADB_SEQUENCE_MIN) {
            sql.append(" MINVALUE ").append(seq.minValue());
        }
        if (seq.maxValue() <= MARIADB_SEQUENCE_MAX) {
            sql.append(" MAXVALUE ").append(seq.maxValue());
        }
        sql.append(seq.cycle() ? " CYCLE" : " NOCYCLE");
        return sql.toString();
    }

    /** Quotet einen Bezeichner in der konfigurierten Schreibweise. Intern (Maps, Typ-Cache,
     *  Logausgaben) bleibt es beim H2-Namen; umgesetzt wird erst beim Erzeugen des SQL. */
    private String quote(String identifier) {
        return DbDialect.MARIADB.quote(identifierCase.apply(identifier));
    }

    public void createTables(List<String> orderedTableNames, Map<String, TableDefinition> tables)
            throws SQLException {
        setForeignKeyChecks(false);
        try {
            if (dropExisting) {
                // Abhaengige Tabellen zuerst entfernen
                for (int i = orderedTableNames.size() - 1; i >= 0; i--) {
                    dropTable(orderedTableNames.get(i));
                }
            }
            for (String name : orderedTableNames) {
                createTable(tables.get(name));
            }
            maria.commit();

            for (String name : orderedTableNames) {
                createIndexes(tables.get(name));
            }
            for (String name : orderedTableNames) {
                addForeignKeys(tables.get(name), tables);
            }
            maria.commit();
        } finally {
            setForeignKeyChecks(true);
            maria.commit();
        }

        if (failedObjects > 0) {
            Log.warn(failedObjects + " Schema-Objekt(e) konnten nicht angelegt werden -- siehe "
                    + "Warnungen oben. Das Zielschema ist unvollstaendig.");
        }
    }

    private void createTable(TableDefinition table) throws SQLException {
        Map<String, String> types = typesFor(table);
        List<ColumnDefinition> columns = table.orderedColumns();
        String autoIncrementColumn = resolveAutoIncrementColumn(table, columns);

        List<String> members = new ArrayList<>();
        for (ColumnDefinition col : columns) {
            String type = types.get(col.name);
            StringBuilder member = new StringBuilder("  ")
                    .append(quote(col.name)).append(" ").append(type);
            if (!col.nullable) {
                member.append(" NOT NULL");
            }
            if (col.name.equals(autoIncrementColumn)) {
                member.append(" AUTO_INCREMENT");
            } else {
                member.append(defaultClause(table, col, type));
            }
            members.add(member.toString());
        }

        if (!table.primaryKeyColumns.isEmpty()) {
            members.add("  PRIMARY KEY (" + keyColumnList(table, table.primaryKeyColumns) + ")");
        }

        // MariaDB verlangt, dass eine AUTO_INCREMENT-Spalte die erste Spalte eines Index ist.
        if (autoIncrementColumn != null && !isLeadingPrimaryKeyColumn(table, autoIncrementColumn)) {
            Log.warn("Tabelle " + table.name + ": AUTO_INCREMENT-Spalte '" + autoIncrementColumn
                    + "' ist nicht die erste Spalte des Primary Key -- es wird zusaetzlich ein "
                    + "UNIQUE-Index angelegt (MariaDB-Anforderung)");
            members.add("  UNIQUE KEY " + quote("AI_" + autoIncrementColumn)
                    + " (" + quote(autoIncrementColumn) + ")");
        }

        String sql = "CREATE TABLE " + quote(table.name) + " (\n"
                + String.join(",\n", members) + "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        Log.info("Erzeuge Tabelle " + table.name);
        try (Statement stmt = maria.createStatement()) {
            stmt.execute(sql);
        }
    }

    /** MariaDB erlaubt genau eine AUTO_INCREMENT-Spalte je Tabelle. */
    private String resolveAutoIncrementColumn(TableDefinition table, List<ColumnDefinition> columns) {
        String chosen = null;
        for (ColumnDefinition col : columns) {
            if (!col.autoIncrement) {
                continue;
            }
            if (chosen == null) {
                chosen = col.name;
            } else {
                Log.warn("Tabelle " + table.name + ": Spalte '" + col.name + "' ist in H2 ebenfalls "
                        + "eine Identity-Spalte, MariaDB erlaubt nur eine -- AUTO_INCREMENT entfaellt "
                        + "fuer diese Spalte (die Werte werden trotzdem kopiert)");
            }
        }
        return chosen;
    }

    private boolean isLeadingPrimaryKeyColumn(TableDefinition table, String column) {
        return !table.primaryKeyColumns.isEmpty() && table.primaryKeyColumns.get(0).equals(column);
    }

    /** Uebersetzt H2-DEFAULT-Ausdruecke, soweit sie sicher uebertragbar sind. Alles andere wird
     *  verworfen -- aber protokolliert, statt still zu verschwinden. */
    private String defaultClause(TableDefinition table, ColumnDefinition col, String mariaType) {
        String raw = col.defaultExpression;
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String expr = raw.trim();
        if (expr.equalsIgnoreCase("NULL")) {
            return "";
        }
        if (TypeMapper.requiresKeyPrefix(mariaType)) {
            Log.warn("Tabelle " + table.name + ", Spalte '" + col.name + "': DEFAULT " + expr
                    + " entfaellt, weil der Zieltyp " + TypeMapper.baseTypeName(mariaType)
                    + " in MariaDB keinen einfachen Standardwert erlaubt");
            return "";
        }
        if (expr.equalsIgnoreCase("TRUE")) {
            return " DEFAULT 1";
        }
        if (expr.equalsIgnoreCase("FALSE")) {
            return " DEFAULT 0";
        }
        if (NUMERIC_LITERAL.matcher(expr).matches()) {
            return " DEFAULT " + expr;
        }
        if (isStringLiteral(expr)) {
            // H2 kennt keine Backslash-Escapes, MariaDB schon -> Backslashes verdoppeln.
            return " DEFAULT " + expr.replace("\\", "\\\\");
        }
        String upper = expr.toUpperCase(Locale.ROOT);
        if (upper.equals("CURRENT_TIMESTAMP") || upper.equals("NOW()") || upper.startsWith("CURRENT_TIMESTAMP(")) {
            return " DEFAULT " + currentTimestamp(mariaType);
        }
        if (upper.equals("CURRENT_DATE") || upper.equals("CURRENT_TIME")) {
            // MariaDB erlaubt solche Funktionen nur als Ausdruck in Klammern.
            return " DEFAULT (" + upper + ")";
        }
        Log.warn("Tabelle " + table.name + ", Spalte '" + col.name + "': DEFAULT-Ausdruck '" + expr
                + "' wird nicht nach MariaDB uebersetzt und entfaellt");
        return "";
    }

    /** CURRENT_TIMESTAMP muss dieselbe Sekundenbruchteil-Praezision wie die Spalte haben. */
    private String currentTimestamp(String mariaType) {
        int open = mariaType.indexOf('(');
        int close = mariaType.indexOf(')');
        if (open > 0 && close > open) {
            return "CURRENT_TIMESTAMP(" + mariaType.substring(open + 1, close) + ")";
        }
        return "CURRENT_TIMESTAMP";
    }

    private static boolean isStringLiteral(String expr) {
        return expr.length() >= 2 && expr.startsWith("'") && expr.endsWith("'");
    }

    private void createIndexes(TableDefinition table) {
        for (TableDefinition.UniqueConstraint uc : table.uniqueConstraints) {
            String sql = "ALTER TABLE " + quote(table.name)
                    + " ADD CONSTRAINT " + quote(uc.name())
                    + " UNIQUE (" + keyColumnList(table, uc.columns()) + ")";
            executeOptional(sql, "Unique-Constraint '" + uc.name() + "' auf " + table.name);
        }
        for (TableDefinition.Index index : table.indexes) {
            String sql = "CREATE INDEX " + quote(index.name())
                    + " ON " + quote(table.name)
                    + " (" + keyColumnList(table, index.columns()) + ")";
            executeOptional(sql, "Index '" + index.name() + "' auf " + table.name);
        }
    }

    private void addForeignKeys(TableDefinition table, Map<String, TableDefinition> allTables) {
        for (TableDefinition.ForeignKey fk : table.foreignKeys) {
            TableDefinition target = allTables.get(fk.referencedTable());
            if (target == null) {
                Log.warn("Fremdschluessel '" + fk.fkName() + "' auf " + table.name
                        + " uebersprungen: Zieltabelle " + fk.referencedTable()
                        + " ist nicht Teil der Migration");
                continue;
            }
            String sql = "ALTER TABLE " + quote(table.name)
                    + " ADD CONSTRAINT " + quote(fk.fkName())
                    + " FOREIGN KEY (" + keyColumnList(table, fk.fkColumns()) + ")"
                    + " REFERENCES " + quote(fk.referencedTable())
                    + " (" + keyColumnList(target, fk.referencedColumns()) + ")";
            executeOptional(sql, "Fremdschluessel '" + fk.fkName() + "' auf " + table.name);
        }
    }

    /** Spaltenliste fuer Schluessel/Indizes. TEXT- und BLOB-Spalten brauchen in MariaDB eine
     *  Praefixlaenge, sonst schlaegt das Statement fehl. */
    private String keyColumnList(TableDefinition table, List<String> columns) {
        Map<String, String> types = typesFor(table);
        return columns.stream().map(column -> {
            String quoted = quote(column);
            String type = types.get(column);
            if (type != null && TypeMapper.requiresKeyPrefix(type)) {
                Log.warn("Tabelle " + table.name + ": Schluesselspalte '" + column + "' ist als "
                        + TypeMapper.baseTypeName(type) + " abgebildet und wird nur mit den ersten "
                        + KEY_PREFIX_LENGTH + " Zeichen indiziert");
                return quoted + "(" + KEY_PREFIX_LENGTH + ")";
            }
            return quoted;
        }).collect(Collectors.joining(", "));
    }

    private Map<String, String> typesFor(TableDefinition table) {
        return ddlTypes.computeIfAbsent(table.name, key -> {
            Map<String, String> mapped = new LinkedHashMap<>();
            for (ColumnDefinition col : table.orderedColumns()) {
                mapped.put(col.name, TypeMapper.toMariaDbType(col));
            }
            return mapped;
        });
    }

    private void dropTable(String tableName) throws SQLException {
        try (Statement stmt = maria.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + quote(tableName));
        }
    }

    private void setForeignKeyChecks(boolean enabled) throws SQLException {
        try (Statement stmt = maria.createStatement()) {
            stmt.execute("SET FOREIGN_KEY_CHECKS=" + (enabled ? "1" : "0"));
        }
    }

    /** Fuehrt ein Nebenobjekt-Statement aus; Fehler werden protokolliert, brechen aber nicht ab. */
    private void executeOptional(String sql, String description) {
        try (Statement stmt = maria.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            failedObjects++;
            Log.warn("Konnte " + description + " nicht anlegen: " + e.getMessage());
        }
    }
}
