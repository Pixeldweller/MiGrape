package com.pixeldweller.migrape.schema;

import com.pixeldweller.migrape.db.DbDialect;
import com.pixeldweller.migrape.util.Log;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/** Erzeugt CREATE TABLE Statements auf MariaDB aus den gelesenen H2-Tabellendefinitionen.
 *  Foreign Keys werden erst in einem zweiten Durchlauf angelegt, damit die Erstellreihenfolge
 *  der Tabellen keine Rolle spielt. */
public final class SchemaWriter {

    private final Connection maria;
    private final boolean dropExisting;

    public SchemaWriter(Connection maria, boolean dropExisting) {
        this.maria = maria;
        this.dropExisting = dropExisting;
    }

    public void createTables(List<String> orderedTableNames, Map<String, TableDefinition> tables) throws SQLException {
        try (Statement stmt = maria.createStatement()) {
            stmt.execute("SET FOREIGN_KEY_CHECKS=0");
        }

        for (String name : orderedTableNames) {
            createTable(tables.get(name));
        }
        maria.commit();

        for (String name : orderedTableNames) {
            addForeignKeys(tables.get(name), tables);
        }
        maria.commit();

        try (Statement stmt = maria.createStatement()) {
            stmt.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        maria.commit();
    }

    private void createTable(TableDefinition table) throws SQLException {
        StringBuilder sql = new StringBuilder();
        String quotedName = DbDialect.MARIADB.quote(table.name);

        if (dropExisting) {
            try (Statement stmt = maria.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + quotedName);
            }
        }

        sql.append("CREATE TABLE IF NOT EXISTS ").append(quotedName).append(" (\n");

        List<ColumnDefinition> columns = table.orderedColumns();
        for (int i = 0; i < columns.size(); i++) {
            ColumnDefinition col = columns.get(i);
            sql.append("  ").append(DbDialect.MARIADB.quote(col.name))
                    .append(" ").append(TypeMapper.toMariaDbType(col));

            if (!col.nullable) {
                sql.append(" NOT NULL");
            }
            if (col.autoIncrement) {
                sql.append(" AUTO_INCREMENT");
            }
            if (i < columns.size() - 1 || !table.primaryKeyColumns.isEmpty()) {
                sql.append(",");
            }
            sql.append("\n");
        }

        if (!table.primaryKeyColumns.isEmpty()) {
            sql.append("  PRIMARY KEY (");
            sql.append(String.join(", ", table.primaryKeyColumns.stream()
                    .map(DbDialect.MARIADB::quote).toList()));
            sql.append(")\n");
        }

        sql.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        Log.info("Erzeuge Tabelle " + table.name);
        try (Statement stmt = maria.createStatement()) {
            stmt.execute(sql.toString());
        }
    }

    private void addForeignKeys(TableDefinition table, Map<String, TableDefinition> allTables) {
        for (TableDefinition.ForeignKey fk : table.foreignKeys) {
            if (!allTables.containsKey(fk.referencedTable())) {
                continue; // Ziel-Tabelle nicht Teil der Migration -> FK ueberspringen
            }
            String sql = "ALTER TABLE " + DbDialect.MARIADB.quote(table.name)
                    + " ADD CONSTRAINT " + DbDialect.MARIADB.quote(fk.fkName())
                    + " FOREIGN KEY (" + DbDialect.MARIADB.quote(fk.fkColumn()) + ")"
                    + " REFERENCES " + DbDialect.MARIADB.quote(fk.referencedTable())
                    + " (" + DbDialect.MARIADB.quote(fk.referencedColumn()) + ")";
            try (Statement stmt = maria.createStatement()) {
                stmt.execute(sql);
            } catch (SQLException e) {
                Log.warn("Konnte Fremdschluessel '" + fk.fkName() + "' auf " + table.name + " nicht anlegen: " + e.getMessage());
            }
        }
    }
}
