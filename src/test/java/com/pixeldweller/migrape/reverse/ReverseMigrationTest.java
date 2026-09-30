package com.pixeldweller.migrape.reverse;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.pixeldweller.migrape.MigrationConfig;
import com.pixeldweller.migrape.migration.MigrationService;
import com.pixeldweller.migrape.migration.MigrationState;
import com.pixeldweller.migrape.migration.VerificationResult;
import com.pixeldweller.migrape.testsupport.HibernateFixtureContract;
import com.pixeldweller.migrape.testsupport.JdbcSnapshot;
import com.pixeldweller.migrape.testsupport.RichSchemaFixture;
import com.pixeldweller.migrape.testsupport.TempFiles;
import com.pixeldweller.migrape.testsupport.TestConfigs;
import com.pixeldweller.migrape.testsupport.hibernate.Department;
import com.pixeldweller.migrape.testsupport.hibernate.Employee;
import com.pixeldweller.migrape.testsupport.hibernate.Enums;
import com.pixeldweller.migrape.testsupport.hibernate.HibernateFixture;
import com.pixeldweller.migrape.testsupport.hibernate.WorkGroup;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Rueckrichtung MariaDB -> H2, geprueft als Rundreise: H2-Original -> MariaDB (Hinrichtung,
 * Standard-Schreibweise lower) -> frisches H2-Schema -> Vergleich mit dem Original, Zelle fuer
 * Zelle. Das Zielschema entsteht dabei so wie beim Kunden: bei der Hibernate-Fixture aus dem
 * Entity-Modell, bei der RichSchemaFixture aus demselben DDL wie das Original.
 *
 * Einziger bekannter Unterschied: TIMESTAMP WITH TIME ZONE kommt mit Offset +00:00 zurueck.
 * JdbcSnapshot vergleicht solche Werte als UTC-Zeitpunkt, der Zeitpunkt selbst muss also stimmen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReverseMigrationTest {

    private Path workDir;
    private DB embeddedMariaDb;
    private DBConfigurationBuilder mariaConfig;

    @BeforeAll
    void startEmbeddedMariaDb() throws Exception {
        workDir = TempFiles.createDirectory("migrape-reverse");
        mariaConfig = DBConfigurationBuilder.newBuilder();
        mariaConfig.setPort(0);
        mariaConfig.setDataDir(new File(Files.createTempDirectory("migrape-reverse-mariadb").toString()));
        embeddedMariaDb = DB.newEmbeddedDB(mariaConfig.build());
        embeddedMariaDb.start();
    }

    @AfterAll
    void stopEmbeddedMariaDb() throws Exception {
        if (embeddedMariaDb != null) {
            embeddedMariaDb.stop();
        }
        TempFiles.deleteRecursively(workDir);
    }

    @Test
    @DisplayName("Hibernate-Fixture: H2 -> MariaDB -> von Hibernate angelegtes H2 ist wieder identisch")
    void hibernateFixtureSurvivesRoundTrip() throws Exception {
        Path fixture = HibernateFixtureContract.fixtureFile();
        if (!Files.isRegularFile(fixture)) {
            fail("Fixture-Datenbank fehlt: " + fixture);
        }
        Path original = workDir.resolve("original-" + HibernateFixtureContract.DB_BASE_NAME + ".mv.db");
        Files.copy(fixture, original, StandardCopyOption.REPLACE_EXISTING);
        String originalUrl = HibernateFixtureContract.h2Url(original);
        String mariaUrl = createMariaDb("reverse_hibernate");
        migrateForward(originalUrl, mariaUrl);

        // Zielschema aus dem Entity-Modell, ohne Daten -- so wie beim Kunden mit ddl-auto=create.
        String targetUrl = HibernateFixtureContract.h2Url(workDir.resolve("roundtrip-hibernate.mv.db"));
        HibernateFixture.openSessionFactory(targetUrl, true).close();

        List<VerificationResult> results = reverse(targetUrl, mariaUrl);
        assertEquals(HibernateFixtureContract.TABLES.size(), results.size());
        assertAllOk(results);
        assertSameContent(originalUrl, targetUrl);

        try (SessionFactory sessionFactory = HibernateFixture.openSessionFactory(targetUrl, false)) {
            sessionFactory.inTransaction(session -> {
                Employee emp = session.createQuery("from Employee where lastName = :name", Employee.class)
                        .setParameter("name", HibernateFixtureContract.EMP1_LAST_NAME).getSingleResult();
                assertEquals(UUID.fromString(HibernateFixtureContract.EMP1_EXTERNAL_ID), emp.externalId,
                        "UUID war in MariaDB CHAR(36) und muss wieder als UUID ankommen");
                assertTrue(emp.active, "BOOLEAN war in MariaDB TINYINT(1)");
            });

            Long maxEmployeeId = sessionFactory.fromTransaction(session -> session
                    .createQuery("select max(e.id) from Employee e", Long.class).getSingleResult());
            Long maxGroupId = sessionFactory.fromTransaction(session -> session
                    .createQuery("select max(g.id) from WorkGroup g", Long.class).getSingleResult());

            Long newEmployeeId = sessionFactory.fromTransaction(session -> {
                Department department = session
                        .createQuery("from Department where parent is null", Department.class)
                        .getSingleResult();
                Employee neu = new Employee("Zurueck", "InH2", department, Enums.EmploymentType.PART_TIME, true);
                session.persist(neu);
                session.flush();
                return neu.id;
            });
            Long newGroupId = sessionFactory.fromTransaction(session -> {
                WorkGroup group = new WorkGroup("zurueck.in.h2", null, null);
                session.persist(group);
                session.flush();
                return group.id;
            });

            assertTrue(newEmployeeId > maxEmployeeId, "Identity muss nach " + maxEmployeeId
                    + " weiterzaehlen, lieferte " + newEmployeeId);
            assertTrue(newGroupId > maxGroupId, "GROUP_SEQ muss nach " + maxGroupId
                    + " weiterzaehlen, lieferte " + newGroupId);
        }
    }

    @Test
    @DisplayName("RichSchemaFixture: ARRAY, UUID, ENUM, LOBs, TIME(3), Zeitzonen und Sequenz kommen zurueck")
    void richSchemaSurvivesRoundTrip() throws Exception {
        String originalUrl = h2FileUrl("rich-original");
        try (Connection h2 = DriverManager.getConnection(originalUrl, "sa", "")) {
            RichSchemaFixture.createSchema(h2);
            RichSchemaFixture.insertData(h2);
        }
        String mariaUrl = createMariaDb("reverse_rich");
        migrateForward(originalUrl, mariaUrl);

        String targetUrl = h2FileUrl("rich-roundtrip");
        try (Connection h2 = DriverManager.getConnection(targetUrl, "sa", "")) {
            RichSchemaFixture.createSchema(h2);
        }

        List<VerificationResult> results = reverse(targetUrl, mariaUrl);
        assertEquals(7, results.size());
        assertAllOk(results);
        assertSameContent(originalUrl, targetUrl);

        try (Connection h2 = DriverManager.getConnection(targetUrl, "sa", "");
             Statement stmt = h2.createStatement()) {
            assertEquals(RichSchemaFixture.SEQUENCE_NEXT_VALUE, longValue(stmt,
                    "SELECT BASE_VALUE FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_NAME = '"
                            + RichSchemaFixture.SEQUENCE_NAME + "'"),
                    "Die Sequenz muss beim MariaDB-Stand stehen, nicht wieder bei 1");

            long maxAuthorId = longValue(stmt, "SELECT MAX(ID) FROM AUTHOR");
            stmt.execute("INSERT INTO AUTHOR (FIRST_NAME, LAST_NAME) VALUES ('Neu', 'In H2')");
            assertEquals(maxAuthorId + 1, longValue(stmt, "SELECT MAX(ID) FROM AUTHOR"),
                    "Identity muss hinter den kopierten IDs weiterzaehlen");
        }
    }

    @Test
    @DisplayName("Ohne H2-Schema bricht die Rueckrichtung ab, statt ins Leere zu kopieren")
    void refusesTargetWithoutSchema() throws Exception {
        String mariaUrl = createMariaDb("reverse_empty");
        String emptyUrl = h2FileUrl("empty");
        DriverManager.getConnection(emptyUrl, "sa", "").close();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> reverse(emptyUrl, mariaUrl));
        assertTrue(e.getMessage().contains("keine Tabellen"), e.getMessage());
    }

    @Test
    @DisplayName("Ein falscher H2-Pfad legt keine neue, leere Datenbank an")
    void doesNotCreateMissingH2File() throws Exception {
        String mariaUrl = createMariaDb("reverse_missing");
        Path missing = workDir.resolve("gibt-es-nicht");

        assertThrows(SQLException.class, () -> reverse(HibernateFixtureContract.h2Url(missing), mariaUrl));
        assertFalse(Files.exists(workDir.resolve("gibt-es-nicht.mv.db")), "H2 hat die Datei angelegt");
    }

    // ---- Helfer ----

    private String createMariaDb(String name) throws Exception {
        embeddedMariaDb.createDB(name);
        return mariaConfig.getURL(name);
    }

    private String h2FileUrl(String baseName) {
        return "jdbc:h2:file:" + workDir.resolve(baseName + "-" + UUID.randomUUID()).toString().replace('\\', '/');
    }

    private void migrateForward(String h2Url, String mariaUrl) throws Exception {
        MigrationService forward = new MigrationService(TestConfigs.forMigration(h2Url, mariaUrl, 50));
        forward.migrateSchema(true);
        forward.migrateData(new MigrationState(workDir.resolve(UUID.randomUUID() + ".state")), false);
    }

    private List<VerificationResult> reverse(String h2Url, String mariaUrl) throws Exception {
        MigrationConfig config = TestConfigs.forMigration(h2Url, mariaUrl, 50);
        return new ReverseMigrationService(config).run();
    }

    private static void assertAllOk(List<VerificationResult> results) {
        for (VerificationResult r : results) {
            assertTrue(r.ok(), r.table() + ": MariaDB=" + r.sourceCount() + " H2=" + r.targetCount()
                    + (r.problem() == null ? "" : " Problem: " + r.problem()));
        }
    }

    /** Beide H2-Datenbanken muessen dieselben Tabellen, Spalten und Werte enthalten. */
    private static void assertSameContent(String originalUrl, String roundTripUrl) throws Exception {
        try (Connection original = DriverManager.getConnection(originalUrl, "sa", "");
             Connection roundTrip = DriverManager.getConnection(roundTripUrl, "sa", "")) {
            JdbcSnapshot expected = JdbcSnapshot.ofH2(original);
            JdbcSnapshot actual = JdbcSnapshot.ofH2(roundTrip);

            assertEquals(expected.tableNames(), actual.tableNames());
            for (String table : expected.tableNames()) {
                List<String> columns = expected.columnNames(table);
                assertEquals(columns, actual.columnNames(table), "Spalten von " + table);

                List<List<Object>> expectedRows = expected.rows(table, columns);
                List<List<Object>> actualRows = actual.rows(table, columns);
                assertEquals(expectedRows.size(), actualRows.size(), "Zeilenanzahl in " + table);
                for (int i = 0; i < expectedRows.size(); i++) {
                    for (int c = 0; c < columns.size(); c++) {
                        assertEquals(expectedRows.get(i).get(c), actualRows.get(i).get(c),
                                table + "." + columns.get(c) + " in Zeile " + (i + 1));
                    }
                }
            }
        }
    }

    private static long longValue(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next(), "Kein Ergebnis fuer " + sql);
            return rs.getLong(1);
        }
    }
}
