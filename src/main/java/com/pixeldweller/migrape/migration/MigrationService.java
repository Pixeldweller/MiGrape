package com.pixeldweller.migrape.migration;

import com.pixeldweller.migrape.MigrationConfig;
import com.pixeldweller.migrape.db.ConnectionFactory;
import com.pixeldweller.migrape.db.DbDialect;
import com.pixeldweller.migrape.schema.SchemaReader;
import com.pixeldweller.migrape.schema.SchemaWriter;
import com.pixeldweller.migrape.schema.SequenceDefinition;
import com.pixeldweller.migrape.schema.TableDefinition;
import com.pixeldweller.migrape.schema.TableOrderResolver;
import com.pixeldweller.migrape.util.Log;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MigrationService {

    private final MigrationConfig config;
    private final ConnectionFactory connectionFactory;

    public MigrationService(MigrationConfig config) {
        this.config = config;
        this.connectionFactory = new ConnectionFactory(config);
    }

    /** Legt nur das Schema (Tabellen, Schluessel, Indizes) auf MariaDB an, ohne Daten zu kopieren. */
    public void migrateSchema(boolean dropExisting) throws SQLException {
        try (Connection h2 = connectionFactory.openH2();
             Connection maria = connectionFactory.openMaria()) {

            SchemaReader reader = new SchemaReader(h2, config);
            Map<String, TableDefinition> tables = reader.readSchema();
            List<SequenceDefinition> sequences = reader.readSequences();
            List<String> order = TableOrderResolver.resolve(tables);

            Log.info("Erstelle Schema fuer " + tables.size() + " Tabelle(n) und " + sequences.size()
                    + " Sequenz(en) aus Schema " + reader.schema() + " in Reihenfolge: " + order
                    + " (Bezeichner: " + config.mariaIdentifierCase.name().toLowerCase(Locale.ROOT) + ")");
            SchemaWriter writer = new SchemaWriter(maria, dropExisting, config.mariaIdentifierCase);
            writer.createTables(order, tables);
            writer.createSequences(sequences);
            Log.info("Schema-Migration abgeschlossen.");
        }
    }

    /** Kopiert die Daten aller Tabellen. Das Schema muss bereits existieren.
     *
     *  @param resume wenn true, werden in der Status-Datei als DONE markierte Tabellen
     *                uebersprungen; wenn false, werden alle Tabellen neu kopiert. */
    public void migrateData(MigrationState state, boolean resume) throws SQLException {
        try (Connection h2 = connectionFactory.openH2();
             Connection maria = connectionFactory.openMaria()) {

            Map<String, TableDefinition> tables = new SchemaReader(h2, config).readSchema();
            List<String> order = TableOrderResolver.resolve(tables);

            BatchInserter inserter = new BatchInserter(h2, maria, config.fetchSize, config.batchSize,
                    config.mariaIdentifierCase);
            ProgressPrinter progress = new ProgressPrinter();

            // Fuer den gesamten Datenlauf abschalten: selbstreferenzierende und zyklische
            // Fremdschluessel lassen sich sonst nicht befuellen, weil H2 keine Zeilenreihenfolge
            // garantiert, in der Elternzeilen vor Kindzeilen kommen.
            setForeignKeyChecks(maria, false);
            try {
                for (String tableName : order) {
                    if (resume && state.isDone(tableName)) {
                        Log.info("Ueberspringe " + tableName + " (bereits migriert laut Status-Datei)");
                        continue;
                    }
                    Log.info("Kopiere Tabelle " + tableName);
                    truncateTarget(maria, tableName);
                    long copied = inserter.copy(tables.get(tableName), progress);
                    Log.info(tableName + ": " + copied + " Zeilen kopiert");
                    state.markDone(tableName);
                }
            } finally {
                setForeignKeyChecks(maria, true);
                maria.commit();
            }
        }
    }

    private void truncateTarget(Connection maria, String tableName) throws SQLException {
        try (Statement stmt = maria.createStatement()) {
            stmt.execute("TRUNCATE TABLE " + targetName(tableName));
        }
        maria.commit();
    }

    private void setForeignKeyChecks(Connection maria, boolean enabled) throws SQLException {
        try (Statement stmt = maria.createStatement()) {
            stmt.execute("SET FOREIGN_KEY_CHECKS=" + (enabled ? "1" : "0"));
        }
    }

    /** Vergleicht Zeilenzahlen je Tabelle zwischen H2 und MariaDB. Ist eine Tabelle auf der
     *  Zielseite nicht lesbar, wird das als Abweichung gemeldet statt die ganze Pruefung
     *  abzubrechen. */
    public List<VerificationResult> verify() throws SQLException {
        List<VerificationResult> results = new ArrayList<>();
        try (Connection h2 = connectionFactory.openH2();
             Connection maria = connectionFactory.openMaria()) {

            Map<String, TableDefinition> tables = new SchemaReader(h2, config).readSchema();
            for (String tableName : tables.keySet()) {
                try {
                    long sourceCount = count(h2, DbDialect.H2.quote(tableName));
                    long targetCount = count(maria, targetName(tableName));
                    results.add(new VerificationResult(tableName, sourceCount, targetCount));
                } catch (SQLException e) {
                    results.add(VerificationResult.failed(tableName, e.getMessage()));
                    // Nach einem Fehler kann die MariaDB-Transaktion als abgebrochen gelten.
                    maria.rollback();
                }
            }
        }
        return results;
    }

    /** Gequoteter Tabellenname in MariaDB, in der konfigurierten Schreibweise. */
    private String targetName(String tableName) {
        return DbDialect.MARIADB.quote(config.mariaIdentifierCase.apply(tableName));
    }

    private long count(Connection conn, String quotedTableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + quotedTableName;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public static Path defaultStateFile() {
        return Path.of("migration.state");
    }
}
