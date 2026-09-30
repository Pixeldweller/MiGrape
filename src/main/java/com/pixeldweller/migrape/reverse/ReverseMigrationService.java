package com.pixeldweller.migrape.reverse;

import com.pixeldweller.migrape.MigrationConfig;
import com.pixeldweller.migrape.db.ConnectionFactory;
import com.pixeldweller.migrape.db.DbDialect;
import com.pixeldweller.migrape.migration.ProgressPrinter;
import com.pixeldweller.migrape.migration.VerificationResult;
import com.pixeldweller.migrape.schema.ColumnDefinition;
import com.pixeldweller.migrape.schema.SchemaReader;
import com.pixeldweller.migrape.schema.SequenceDefinition;
import com.pixeldweller.migrape.schema.TableDefinition;
import com.pixeldweller.migrape.schema.TableOrderResolver;
import com.pixeldweller.migrape.util.Log;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Rueckrichtung: kopiert die Daten aus MariaDB in eine <em>bestehende</em> H2-Datenbank.
 *
 *  Das Schema legt der Migrator hier bewusst nicht an. Es soll aus dem Entity-Modell der
 *  Anwendung stammen (z.B. einmal mit {@code ddl-auto=create} gegen eine leere H2-Datei
 *  gestartet): nur dann hat H2 wieder die Typen, die Hibernate erwartet -- UUID statt CHAR(36),
 *  BOOLEAN statt TINYINT(1), ARRAY statt JSON-Text. Diese Informationen stehen in MariaDB nicht
 *  mehr. Der Migrator fuellt das Schema, setzt Identity-Spalten und Sequenzen auf den Stand von
 *  MariaDB und vergleicht zum Schluss die Zeilenzahlen.
 *
 *  Die betroffenen H2-Tabellen werden vorher geleert. */
public final class ReverseMigrationService {

    private final MigrationConfig config;
    private final ConnectionFactory connectionFactory;

    public ReverseMigrationService(MigrationConfig config) {
        this.config = config;
        this.connectionFactory = new ConnectionFactory(config);
    }

    /** @return Zeilenzahl-Vergleich je H2-Tabelle; Quelle ist MariaDB, Ziel H2. */
    public List<VerificationResult> run() throws SQLException {
        try (Connection h2 = connectionFactory.openExistingH2();
             Connection maria = connectionFactory.openMaria()) {
            h2.setAutoCommit(false);

            SchemaReader reader = new SchemaReader(h2, config);
            Map<String, TableDefinition> tables = reader.readTables();
            if (tables.isEmpty()) {
                throw new IllegalStateException("Die H2-Datenbank " + config.h2Url + " (Schema "
                        + reader.schema() + ") enthaelt keine Tabellen. Das Schema muss vorher "
                        + "existieren -- z.B. die Anwendung einmal mit ddl-auto=create gegen die "
                        + "leere H2-Datei starten.");
            }
            List<SequenceDefinition> sequences = reader.readSequences();
            List<String> order = TableOrderResolver.resolve(tables);

            // Alle Lesezugriffe auf MariaDB laufen in einer Transaktion (autoCommit=false):
            // InnoDB liefert damit einen konsistenten Stand ueber alle Tabellen.
            MariaDbCatalog catalog = MariaDbCatalog.read(maria);
            ReversePlan plan = ReversePlan.build(order, tables, sequences, catalog, config.tableFilter != null);

            Log.info("Kopiere " + plan.tables.size() + " Tabelle(n) und " + plan.sequences.size()
                    + " Sequenz(en) von MariaDB nach H2 (Schema " + reader.schema() + ")");
            copyAll(h2, maria, catalog, plan);
            List<VerificationResult> results = verify(h2, catalog, plan);
            maria.rollback();
            return results;
        }
    }

    private void copyAll(Connection h2, Connection maria, MariaDbCatalog catalog, ReversePlan plan)
            throws SQLException {
        ProgressPrinter progress = new ProgressPrinter();
        // Wie FOREIGN_KEY_CHECKS=0 in der Hinrichtung: selbstreferenzierende und zyklische
        // Fremdschluessel lassen sich sonst nicht in beliebiger Zeilenreihenfolge befuellen.
        setReferentialIntegrity(h2, false);
        try {
            for (ReversePlan.TableMapping table : plan.tables) {
                Log.info("Kopiere Tabelle " + table.h2Table().name + " (MariaDB: " + table.mariaTable() + ")");
                truncate(h2, table.h2Table().name);
                long copied = copy(h2, maria, catalog, table, progress);
                Log.info(table.h2Table().name + ": " + copied + " Zeilen kopiert");
                restartIdentities(h2, catalog, table);
            }
            for (ReversePlan.SequenceMapping seq : plan.sequences) {
                restartSequence(h2, catalog, seq);
            }
        } catch (SQLException | RuntimeException e) {
            h2.rollback();
            throw e;
        } finally {
            setReferentialIntegrity(h2, true);
            h2.commit();
        }
    }

    private long copy(Connection h2, Connection maria, MariaDbCatalog catalog,
                      ReversePlan.TableMapping table, ProgressPrinter progress) throws SQLException {
        List<ReversePlan.ColumnMapping> columns = table.columns();
        String selectSql = "SELECT "
                + columns.stream().map(c -> DbDialect.MARIADB.quote(c.mariaColumn())).collect(Collectors.joining(", "))
                + " FROM " + DbDialect.MARIADB.quote(table.mariaTable());
        String insertSql = "INSERT INTO " + DbDialect.H2.quote(table.h2Table().name) + " ("
                + columns.stream().map(c -> DbDialect.H2.quote(c.h2Column().name)).collect(Collectors.joining(", "))
                + ") VALUES (" + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";

        String name = table.h2Table().name;
        long total = catalog.count(table.mariaTable());
        long copied = 0;
        int pending = 0;

        try (Statement selectStmt = maria.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement insertStmt = h2.prepareStatement(insertSql)) {
            selectStmt.setFetchSize(config.fetchSize);
            try (ResultSet rs = selectStmt.executeQuery(selectSql)) {
                while (rs.next()) {
                    for (int i = 0; i < columns.size(); i++) {
                        insertStmt.setObject(i + 1, ReverseValues.read(rs, i + 1, columns.get(i).h2Column()));
                    }
                    insertStmt.addBatch();
                    pending++;
                    copied++;

                    if (pending >= config.batchSize) {
                        insertStmt.executeBatch();
                        h2.commit();
                        pending = 0;
                        progress.update(name, copied, total);
                    }
                }
                if (pending > 0) {
                    insertStmt.executeBatch();
                    h2.commit();
                }
            }
        }
        progress.update(name, copied, total);
        progress.finish();
        return copied;
    }

    /** H2 zieht den Zaehler einer Identity-Spalte nicht nach, wenn Zeilen mit expliziter ID
     *  eingefuegt werden (MariaDB tut das bei AUTO_INCREMENT). Ohne diesen Schritt bekaeme die
     *  erste neue Zeile wieder die ID 1 -- und spaeter eine, die es schon gibt.
     *  Weitergezaehlt wird ab dem hoeheren Wert aus MAX(id)+1 und dem AUTO_INCREMENT-Stand von
     *  MariaDB, damit auch IDs geloeschter Zeilen nicht erneut vergeben werden. */
    private void restartIdentities(Connection h2, MariaDbCatalog catalog, ReversePlan.TableMapping table)
            throws SQLException {
        String tableName = DbDialect.H2.quote(table.h2Table().name);
        for (ColumnDefinition col : table.h2Table().orderedColumns()) {
            if (!col.autoIncrement) {
                continue;
            }
            String column = DbDialect.H2.quote(col.name);
            Long next = catalog.autoIncrement(table.mariaTable());
            try (Statement stmt = h2.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT MAX(" + column + ") FROM " + tableName)) {
                rs.next();
                long max = rs.getLong(1);
                if (!rs.wasNull() && (next == null || max + 1 > next)) {
                    next = max + 1;
                }
            }
            if (next == null) {
                continue;
            }
            try (Statement stmt = h2.createStatement()) {
                stmt.execute("ALTER TABLE " + tableName + " ALTER COLUMN " + column + " RESTART WITH " + next);
            }
            Log.info(table.h2Table().name + "." + col.name + ": Identity zaehlt ab " + next + " weiter");
        }
    }

    private void restartSequence(Connection h2, MariaDbCatalog catalog, ReversePlan.SequenceMapping seq)
            throws SQLException {
        long next = catalog.nextSequenceValue(seq.mariaSequence());
        try (Statement stmt = h2.createStatement()) {
            stmt.execute("ALTER SEQUENCE " + DbDialect.H2.quote(seq.h2Sequence().name()) + " RESTART WITH " + next);
        }
        Log.info("Sequenz " + seq.h2Sequence().name() + ": naechster Wert " + next
                + " (vorher in H2: " + seq.h2Sequence().nextValue() + ")");
    }

    private List<VerificationResult> verify(Connection h2, MariaDbCatalog catalog, ReversePlan plan)
            throws SQLException {
        List<VerificationResult> results = new ArrayList<>();
        for (ReversePlan.TableMapping table : plan.tables) {
            long mariaCount = catalog.count(table.mariaTable());
            long h2Count;
            try (Statement stmt = h2.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + DbDialect.H2.quote(table.h2Table().name))) {
                rs.next();
                h2Count = rs.getLong(1);
            }
            results.add(new VerificationResult(table.h2Table().name, mariaCount, h2Count));
        }
        for (String missing : plan.missingTables) {
            results.add(VerificationResult.failed(missing, "Tabelle fehlt in MariaDB"));
        }
        return results;
    }

    private static void truncate(Connection h2, String table) throws SQLException {
        try (Statement stmt = h2.createStatement()) {
            stmt.execute("TRUNCATE TABLE " + DbDialect.H2.quote(table));
        }
        h2.commit();
    }

    private static void setReferentialIntegrity(Connection h2, boolean enabled) throws SQLException {
        try (Statement stmt = h2.createStatement()) {
            stmt.execute("SET REFERENTIAL_INTEGRITY " + (enabled ? "TRUE" : "FALSE"));
        }
    }
}
