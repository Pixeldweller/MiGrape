package com.pixeldweller.migrape.migration;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.pixeldweller.migrape.MigrationConfig;
import com.pixeldweller.migrape.testsupport.HibernateFixtureContract;
import com.pixeldweller.migrape.testsupport.JdbcSnapshot;
import com.pixeldweller.migrape.testsupport.TempFiles;
import com.pixeldweller.migrape.testsupport.TestConfigs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Testreihe 2 von 2: <em>ohne</em> Hibernate.
 *
 * Diese Klasse kennt das Entity-Modell aus Testreihe 1 nicht -- weder Klassen noch Mappings.
 * Sie nimmt nur die eingecheckte H2-Datei aus {@code src/test/resources/fixtures} und macht
 * genau das, was das Werkzeug auch beim Kunden tut: per JDBC lesen, Schema in MariaDB anlegen,
 * Daten kopieren, verifizieren.
 *
 * Die Erwartungen entstehen deshalb ueberwiegend aus dem Vergleich <em>Quelle gegen Ziel</em>
 * (siehe {@link JdbcSnapshot}) statt aus einer abgeschriebenen Liste: Tabellen, Spalten,
 * Schluessel und schliesslich jeder einzelne Zellwert muessen auf beiden Seiten uebereinstimmen.
 * Nur dort, wo eine Abweichung beabsichtigt ist (UUID wird CHAR(36), TIMESTAMP WITH TIME ZONE
 * wird DATETIME, CHECK-Constraints entfallen), steht eine explizite Erwartung.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HibernateFixtureMigrationTest {

    private static final String MARIA_DB_NAME = "migrape_hibernate_fixture";

    private Path workDir;

    private DB embeddedMariaDb;
    private String mariaUrl;
    private String h2Url;

    @BeforeAll
    void migrateFixtureIntoMariaDb() throws Exception {
        // Kein @TempDir: JUnit befuellt ein Instanzfeld erst nach @BeforeAll.
        workDir = TempFiles.createDirectory("migrape-hibernate-migration");

        Path fixture = HibernateFixtureContract.fixtureFile();
        if (!Files.isRegularFile(fixture)) {
            fail("Fixture-Datenbank fehlt: " + fixture + System.lineSeparator()
                    + "Sie wird von HibernateEntityFixtureTest erzeugt -- diesen Test einmal "
                    + "ausfuehren und die Datei einchecken.");
        }
        // Auf einer Kopie arbeiten: H2 schreibt beim Oeffnen in die Datei, die eingecheckte
        // Fixture soll davon unberuehrt bleiben.
        Path workingCopy = workDir.resolve(HibernateFixtureContract.DB_BASE_NAME + ".mv.db");
        Files.copy(fixture, workingCopy, StandardCopyOption.REPLACE_EXISTING);
        h2Url = HibernateFixtureContract.h2Url(workingCopy);

        DBConfigurationBuilder builder = DBConfigurationBuilder.newBuilder();
        builder.setPort(0);
        builder.setDataDir(new File(Files.createTempDirectory("migrape-hibernate-mariadb").toString()));
        embeddedMariaDb = DB.newEmbeddedDB(builder.build());
        embeddedMariaDb.start();
        embeddedMariaDb.createDB(MARIA_DB_NAME);
        mariaUrl = builder.getURL(MARIA_DB_NAME);

        MigrationConfig config = TestConfigs.forMigration(h2Url, mariaUrl, 100);
        MigrationService service = new MigrationService(config);
        service.migrateSchema(true);
        service.migrateData(new MigrationState(workDir.resolve("hibernate-fixture.state")), false);
    }

    @AfterAll
    void stopEmbeddedMariaDb() throws Exception {
        if (embeddedMariaDb != null) {
            embeddedMariaDb.stop();
        }
        TempFiles.deleteRecursively(workDir);
    }

    // ---- Verifikation des Werkzeugs selbst ----

    @Test
    @DisplayName("verify() meldet alle Tabellen der Fixture als uebereinstimmend")
    void verificationReportsAllTablesInSync() throws Exception {
        MigrationConfig config = TestConfigs.forMigration(h2Url, mariaUrl, 100);
        List<VerificationResult> results = new MigrationService(config).verify();

        assertEquals(HibernateFixtureContract.TABLES.size(), results.size(),
                "Anzahl verifizierter Tabellen");
        for (VerificationResult result : results) {
            assertTrue(result.ok(), result.table() + ": H2=" + result.sourceCount()
                    + " MariaDB=" + result.targetCount()
                    + (result.problem() == null ? "" : " Problem: " + result.problem()));
            Long expected = HibernateFixtureContract.ROW_COUNTS.get(result.table());
            assertNotNull(expected, "Unerwartete Tabelle in der Verifikation: " + result.table());
            assertEquals(expected.longValue(), result.targetCount(), "Zeilenanzahl " + result.table());
        }
    }

    // ---- Struktur: Ziel gegen Quelle ----

    @Test
    @DisplayName("Tabellen und Spalten kommen vollstaendig und in gleicher Reihenfolge an")
    void tablesAndColumnsMatchSource() throws Exception {
        withBothDatabases((h2, maria) -> {
            assertEquals(HibernateFixtureContract.TABLES, h2.tableNames(),
                    "Die Fixture enthaelt nicht die erwarteten Tabellen");
            assertEquals(h2.tableNames(), maria.tableNames());

            for (String table : h2.tableNames()) {
                assertEquals(h2.columnNames(table), maria.columnNames(table),
                        "Spalten von " + table);
            }
        });
    }

    @Test
    @DisplayName("Primaer- und Fremdschluessel kommen mit Namen, Spalten und Reihenfolge an")
    void keysMatchSource() throws Exception {
        withBothDatabases((h2, maria) -> {
            for (String table : h2.tableNames()) {
                assertEquals(h2.primaryKeyColumns(table), maria.primaryKeyColumns(table),
                        "Primaerschluessel von " + table);
                assertEquals(h2.foreignKeys(table), maria.foreignKeys(table),
                        "Fremdschluessel von " + table);
            }

            // Stichproben auf die interessanten Faelle, damit ein leeres Ergebnis auf beiden
            // Seiten nicht als "gleich" durchgeht. Die Spaltenreihenfolge im Schluessel deckt
            // schon der Vergleich Quelle/Ziel oben ab.
            assertEquals(Set.of("EMPLOYEE_ID", "PROJECT_CODE", "WORK_DAY"),
                    Set.copyOf(maria.primaryKeyColumns("TIME_ENTRY")),
                    "zusammengesetzter Primaerschluessel");
            assertTrue(maria.primaryKeyColumns("EMPLOYEE_SKILL").isEmpty(),
                    "Tabelle ohne Primaerschluessel darf auch im Ziel keinen bekommen");
            assertTrue(maria.foreignKeys("EMPLOYEE").stream()
                            .anyMatch(fk -> fk.startsWith("FK_EMPLOYEE_MANAGER")),
                    "selbstreferenzierender Fremdschluessel fehlt: " + maria.foreignKeys("EMPLOYEE"));
            assertTrue(maria.foreignKeys("CAR").stream()
                            .anyMatch(fk -> fk.startsWith("FK_CAR_VEHICLE: [VEHICLE_ID] -> VEHICLE")),
                    "JOINED-Vererbung: " + maria.foreignKeys("CAR"));
        });
    }

    @Test
    @DisplayName("Unique-Constraints und Sekundaerindizes werden mit ihren Namen uebernommen")
    void uniqueConstraintsAndIndexesArrive() throws Exception {
        try (Connection maria = openMaria()) {
            assertEquals("UNIQUE", scalar(maria,
                    "SELECT CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'UQ_DEPARTMENT_NAME'"));
            assertEquals("UNIQUE", scalar(maria,
                    "SELECT CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'UQ_EMPLOYEE_EXTERNAL_ID'"));

            assertEquals(List.of("REGION"), indexColumns(maria, "IDX_DEPARTMENT_REGION"));
            assertEquals(List.of("LAST_NAME"), indexColumns(maria, "IDX_EMPLOYEE_LAST_NAME"));
            assertEquals(List.of("DEPARTMENT_ID", "ACTIVE"), indexColumns(maria, "IDX_EMPLOYEE_DEPT_ACTIVE"),
                    "mehrspaltiger Index inkl. Spaltenreihenfolge");
        }
    }

    @Test
    @DisplayName("Die H2-Typen landen als die passenden MariaDB-Typen")
    void columnTypesAreMappedAsExpected() throws Exception {
        try (Connection maria = openMaria()) {
            assertEquals("char(36)", columnType(maria, "EMPLOYEE", "EXTERNAL_ID"),
                    "feste Breite bleibt feste Breite, kein VARCHAR");
            assertEquals("longtext", columnType(maria, "EMPLOYEE", "NOTES"),
                    "CLOB darf nicht als TEXT landen (65535-Byte-Grenze)");
            assertEquals("longblob", columnType(maria, "EMPLOYEE", "PHOTO"));
            assertEquals("varbinary(64)", columnType(maria, "EMPLOYEE", "FINGERPRINT"),
                    "kurzes byte[] bleibt VARBINARY und wird nicht zum BLOB aufgeblasen");
            assertEquals("char(2)", columnType(maria, "EMPLOYEE", "COUNTRY_CODE"));
            assertEquals("char(1)", columnType(maria, "EMPLOYEE", "GENDER"));
            // Die Anzeigebreite ist bei TINYINT(1) bedeutungstragend (so schreibt MariaDB
            // Wahrheitswerte), bei allen anderen Integer-Typen nicht -- dort wird deshalb nur
            // der Basistyp geprueft.
            assertEquals("tinyint(1)", columnType(maria, "EMPLOYEE", "ACTIVE"));
            assertEquals("tinyint", baseColumnType(maria, "EMPLOYEE", "SECURITY_LEVEL"),
                    "Ordinal-Enum bleibt eine Zahl");
            // Hibernate legt @Enumerated(STRING) als nativen H2-ENUM an. H2 meldet den als
            // Types.OTHER; nur weil der TypeMapper die Labels aus INFORMATION_SCHEMA.ENUM_VALUES
            // liest, kommt drueben wieder ein echter ENUM an statt eines LONGTEXT-Fallbacks.
            assertEquals("enum('CONTRACTOR','FULL_TIME','PART_TIME')",
                    columnType(maria, "EMPLOYEE", "EMPLOYMENT_TYPE"));
            assertEquals("decimal(12,2)", columnType(maria, "EMPLOYEE", "SALARY"));
            assertEquals("decimal(21,0)", columnType(maria, "EMPLOYEE", "WEEKLY_HOURS"),
                    "Duration liegt als Nanosekunden in einer NUMERIC(21)-Spalte");
            assertEquals("double", columnType(maria, "EMPLOYEE", "BONUS_FACTOR"),
                    "DOUBLE darf nicht auf 4-Byte-FLOAT verkuerzt werden");
            // Hibernates float(24) meldet H2 als Types.FLOAT (obwohl TYPE_NAME "REAL" ist).
            // Der Migrator entscheidet nach dem JDBC-Typ und nimmt DOUBLE: breiter als die
            // Quellspalte, aber garantiert ohne Genauigkeitsverlust.
            assertEquals("double", columnType(maria, "EMPLOYEE", "SCORE"));
            assertEquals("float", columnType(maria, "EMPLOYEE", "ACCURACY"),
                    "ein ausdrueckliches H2-REAL bleibt 4 Byte");
            assertEquals("datetime(6)", columnType(maria, "EMPLOYEE", "LAST_LOGIN"),
                    "Mikrosekunden muessen erhalten bleiben");
            assertEquals("datetime(6)", columnType(maria, "EMPLOYEE", "CONTRACT_SIGNED_AT"),
                    "TIMESTAMP WITH TIME ZONE wird DATETIME, der Wert wird nach UTC normalisiert");
            assertEquals("date", columnType(maria, "EMPLOYEE", "HIRE_DATE"));
            assertEquals("bigint", baseColumnType(maria, "EMPLOYEE", "BADGE_NUMBER"));
            assertEquals("int", baseColumnType(maria, "EMPLOYEE", "VACATION_DAYS"));
            assertEquals("smallint", baseColumnType(maria, "EMPLOYEE", "SENIORITY"));
            assertEquals("varchar(80)", columnType(maria, "EMPLOYEE", "FIRST_NAME"));
            assertEquals("varchar(12)", columnType(maria, "PROJECT", "CODE"),
                    "natuerlicher Primaerschluessel als Text");
            // Von Hibernate erzeugte Zeitstempel: LocalDateTime und Instant landen beide hier.
            assertEquals("datetime(6)", columnType(maria, "ANNOUNCEMENT", "CREATED_AT"));
            assertEquals("datetime(6)", columnType(maria, "ANNOUNCEMENT", "UPDATED_AT"));
        }
    }

    @Test
    @DisplayName("Identity-Spalten werden AUTO_INCREMENT, Sequenz-Spalten nicht")
    void identityColumnsBecomeAutoIncrement() throws Exception {
        try (Connection maria = openMaria()) {
            assertEquals("auto_increment", extra(maria, "EMPLOYEE", "ID"));
            assertEquals("auto_increment", extra(maria, "DEPARTMENT", "ID"));
            assertEquals("", extra(maria, "GROUP", "ID"),
                    "GROUP.ID kommt aus einer H2-Sequenz -- Sequenzen sind keine Tabellen und "
                            + "werden nicht migriert, die Spalte darf kein AUTO_INCREMENT bekommen");
            assertEquals("", extra(maria, "PROJECT", "CODE"));
        }
    }

    @Test
    @DisplayName("DEFAULT-Werte kommen mit, CHECK-Constraints bewusst nicht")
    void defaultsArriveAndChecksAreDropped() throws Exception {
        try (Connection maria = openMaria()) {
            assertEquals("1", columnDefault(maria, "DEPARTMENT", "ACTIVE"),
                    "DEFAULT TRUE muss als DEFAULT 1 ankommen");
            // MariaDB gibt COLUMN_DEFAULT seit 10.2 als Ausdruck zurueck -- Zeichenketten
            // deshalb inklusive Hochkommas.
            assertEquals("'EMEA'", columnDefault(maria, "DEPARTMENT", "REGION"));

            assertNull(scalar(maria, "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS "
                            + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'CK_TIME_ENTRY_HOURS'"),
                    "CHECK-Constraints werden bewusst nicht uebersetzt (siehe Warnung im Log)");
        }
    }

    // ---- Inhalt: jede Zelle gegen die Quelle ----

    @Test
    @DisplayName("Jeder einzelne Wert jeder Tabelle stimmt mit der Quelle ueberein")
    void everyValueMatchesSource() throws Exception {
        withBothDatabases((h2, maria) -> {
            for (String table : h2.tableNames()) {
                List<String> columns = h2.columnNames(table);
                List<List<Object>> sourceRows = h2.rows(table, columns);
                List<List<Object>> targetRows = maria.rows(table, columns);

                assertEquals(sourceRows.size(), targetRows.size(), "Zeilenanzahl in " + table);
                for (int i = 0; i < sourceRows.size(); i++) {
                    for (int c = 0; c < columns.size(); c++) {
                        assertEquals(sourceRows.get(i).get(c), targetRows.get(i).get(c),
                                table + "." + columns.get(c) + " in Zeile " + (i + 1));
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("Stichproben ueber alle Typen in der voll befuellten Zeile")
    void fullyPopulatedRowKeepsItsValues() throws Exception {
        try (Connection maria = openMaria();
             PreparedStatement ps = maria.prepareStatement(
                     "SELECT `RANK`, EXTERNAL_ID, HIRE_DATE, SHIFT_START, CONTRACT_SIGNED_AT, SALARY, "
                             + "WEEKLY_HOURS, BONUS_FACTOR, SCORE, ACCURACY, `LEVEL`, SENIORITY, VACATION_DAYS, "
                             + "BADGE_NUMBER, GENDER, ACTIVE, EMPLOYMENT_TYPE, SECURITY_LEVEL, NOTES, "
                             + "PHOTO, FINGERPRINT, CITY, COUNTRY_CODE "
                             + "FROM EMPLOYEE WHERE LAST_NAME = ?")) {
            ps.setString(1, HibernateFixtureContract.EMP1_LAST_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Mitarbeiter mit Unicode-Nachnamen nicht gefunden");
                assertEquals(HibernateFixtureContract.EMP1_RANK, rs.getString("RANK"),
                        "Spalte mit reserviertem Namen");
                assertEquals(HibernateFixtureContract.EMP1_EXTERNAL_ID,
                        rs.getString("EXTERNAL_ID").toLowerCase(Locale.ROOT));
                assertEquals(HibernateFixtureContract.EMP1_HIRE_DATE, rs.getDate("HIRE_DATE").toLocalDate());
                assertEquals(HibernateFixtureContract.EMP1_SHIFT_START, rs.getTime("SHIFT_START").toLocalTime());
                assertEquals(LocalDateTime.of(2019, 3, 25, 12, 30),
                        rs.getTimestamp("CONTRACT_SIGNED_AT").toLocalDateTime(),
                        "14:30+02:00 muss als 12:30 UTC ankommen");
                assertEquals(0, HibernateFixtureContract.EMP1_SALARY.compareTo(rs.getBigDecimal("SALARY")));
                assertEquals(0, BigDecimal.valueOf(HibernateFixtureContract.EMP1_WEEKLY_HOURS_NANOS)
                        .compareTo(rs.getBigDecimal("WEEKLY_HOURS")));
                assertEquals(HibernateFixtureContract.EMP1_BONUS_FACTOR, rs.getDouble("BONUS_FACTOR"), 1e-12);
                assertEquals(HibernateFixtureContract.EMP1_SCORE, rs.getFloat("SCORE"), 1e-6f);
                assertEquals(HibernateFixtureContract.EMP1_ACCURACY, rs.getFloat("ACCURACY"), 1e-6f);
                assertEquals(HibernateFixtureContract.EMP1_LEVEL, rs.getByte("LEVEL"));
                assertEquals(HibernateFixtureContract.EMP1_SENIORITY, rs.getShort("SENIORITY"));
                assertEquals(HibernateFixtureContract.EMP1_VACATION_DAYS, rs.getInt("VACATION_DAYS"));
                assertEquals(HibernateFixtureContract.EMP1_BADGE_NUMBER, rs.getLong("BADGE_NUMBER"));
                assertEquals(String.valueOf(HibernateFixtureContract.EMP1_GENDER), rs.getString("GENDER"));
                assertTrue(rs.getBoolean("ACTIVE"));
                assertEquals("FULL_TIME", rs.getString("EMPLOYMENT_TYPE"));
                assertEquals(2, rs.getInt("SECURITY_LEVEL"), "Ordinalwert von SECRET");
                assertEquals(HibernateFixtureContract.EMP1_NOTES, rs.getString("NOTES"),
                        "CLOB > 65535 Byte muss vollstaendig ankommen");
                assertArrayEquals(HibernateFixtureContract.EMP1_PHOTO, rs.getBytes("PHOTO"));
                assertArrayEquals(HibernateFixtureContract.EMP1_FINGERPRINT, rs.getBytes("FINGERPRINT"));
                assertEquals(HibernateFixtureContract.EMP1_CITY, rs.getString("CITY"));
                assertEquals(HibernateFixtureContract.EMP1_COUNTRY_CODE, rs.getString("COUNTRY_CODE"));
            }
        }
    }

    @Test
    @DisplayName("Die Zeile ohne Werte bleibt ueberall NULL")
    void nullRowStaysNull() throws Exception {
        try (Connection maria = openMaria();
             PreparedStatement ps = maria.prepareStatement(
                     "SELECT `RANK`, EXTERNAL_ID, HIRE_DATE, SALARY, NOTES, PHOTO, FINGERPRINT, "
                             + "SECURITY_LEVEL, WEEKLY_HOURS, CITY, MANAGER_ID "
                             + "FROM EMPLOYEE WHERE LAST_NAME = ?")) {
            ps.setString(1, HibernateFixtureContract.EMP2_LAST_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getString("RANK"));
                assertNull(rs.getString("EXTERNAL_ID"));
                assertNull(rs.getDate("HIRE_DATE"));
                assertNull(rs.getBigDecimal("SALARY"));
                assertNull(rs.getString("NOTES"));
                assertNull(rs.getBytes("PHOTO"));
                assertNull(rs.getBytes("FINGERPRINT"));
                assertNull(rs.getObject("SECURITY_LEVEL"));
                assertNull(rs.getBigDecimal("WEEKLY_HOURS"));
                assertNull(rs.getString("CITY"));
                rs.getLong("MANAGER_ID");
                assertFalse(rs.wasNull(), "MANAGER_ID zeigt auf die Selbstreferenz und ist gesetzt");
            }
        }
    }

    @Test
    @DisplayName("Die von Hibernate erzeugten Zeitstempel kommen als Werte mit, nicht als Defaults")
    void generatedTimestampsArriveAsData() throws Exception {
        try (Connection maria = openMaria()) {
            // Verglichen wird jeweils Zeile gegen Zeile innerhalb <em>einer</em> Spalte.
            // CREATED_AT und CREATED_AT/UPDATED_AT gegeneinander zu halten waere falsch: die
            // Fixture legt die beiden Spalten aus unterschiedlichen Java-Typen an, die in
            // verschiedenen Bezugssystemen in der Datenbank landen (lokale Zeit vs. UTC).
            LocalDateTime createdChanged = timestamp(maria, HibernateFixtureContract.ANNOUNCEMENT1_TITLE,
                    "CREATED_AT");
            LocalDateTime createdUntouched = timestamp(maria, HibernateFixtureContract.ANNOUNCEMENT2_TITLE,
                    "CREATED_AT");
            LocalDateTime updatedChanged = timestamp(maria, HibernateFixtureContract.ANNOUNCEMENT1_TITLE,
                    "UPDATED_AT");
            LocalDateTime updatedUntouched = timestamp(maria, HibernateFixtureContract.ANNOUNCEMENT2_TITLE,
                    "UPDATED_AT");

            // Beide Zeilen wurden zusammen eingefuegt, danach wurde nur die erste geaendert.
            assertTrue(updatedChanged.isAfter(updatedUntouched),
                    "UPDATED_AT der geaenderten Zeile (" + updatedChanged + ") muss nach dem der "
                            + "unveraenderten (" + updatedUntouched + ") liegen");
            assertTrue(Duration.between(createdUntouched, createdChanged).abs()
                            .compareTo(Duration.ofSeconds(1)) < 0,
                    "CREATED_AT stammt bei beiden Zeilen aus demselben INSERT, war aber "
                            + createdChanged + " vs. " + createdUntouched);

            // Die Spalten duerfen keinen automatischen Zeitstempel bekommen haben: MariaDB
            // wuerde die Werte sonst beim naechsten UPDATE ueberschreiben.
            assertEquals("", extra(maria, "ANNOUNCEMENT", "UPDATED_AT"),
                    "UPDATED_AT darf kein ON UPDATE CURRENT_TIMESTAMP tragen");
            assertNull(columnDefault(maria, "ANNOUNCEMENT", "CREATED_AT"),
                    "CREATED_AT darf keinen Standardwert bekommen haben");
        }
    }

    private static LocalDateTime timestamp(Connection maria, String title, String column)
            throws SQLException {
        try (PreparedStatement ps = maria.prepareStatement(
                "SELECT " + column + " FROM ANNOUNCEMENT WHERE TITLE = ?")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Ankuendigung '" + title + "' nicht gefunden");
                return rs.getTimestamp(1).toLocalDateTime();
            }
        }
    }

    @Test
    @DisplayName("Tabelle und Spalten mit reservierten Namen sind im Ziel lesbar")
    void reservedWordIdentifiersSurvive() throws Exception {
        try (Connection maria = openMaria();
             PreparedStatement ps = maria.prepareStatement(
                     "SELECT `VALUE`, DEPARTMENT_ID FROM `GROUP` WHERE `KEY` = ?")) {
            ps.setString(1, HibernateFixtureContract.GROUP1_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Zeile in Tabelle GROUP nicht gefunden");
                assertEquals(HibernateFixtureContract.GROUP1_VALUE, rs.getString("VALUE"));
                assertNotNull(rs.getObject("DEPARTMENT_ID"));
            }

            ps.setString(1, HibernateFixtureContract.GROUP2_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getString("VALUE"));
                assertNull(rs.getObject("DEPARTMENT_ID"));
            }
        }
    }

    @Test
    @DisplayName("Vererbung und zusammengesetzter Schluessel sind ueber Joins nutzbar")
    void joinedInheritanceAndCompositeKeyAreQueryable() throws Exception {
        try (Connection maria = openMaria()) {
            assertEquals(String.valueOf(HibernateFixtureContract.CAR_SEATS), scalar(maria,
                    "SELECT c.SEATS FROM CAR c JOIN VEHICLE v ON v.ID = c.VEHICLE_ID "
                            + "WHERE v.PLATE = '" + HibernateFixtureContract.CAR_PLATE + "'"));
            assertEquals(0, HibernateFixtureContract.TRUCK_PAYLOAD_KG.compareTo(new BigDecimal(scalar(maria,
                    "SELECT t.PAYLOAD_KG FROM TRUCK t JOIN VEHICLE v ON v.ID = t.VEHICLE_ID "
                            + "WHERE v.PLATE = '" + HibernateFixtureContract.TRUCK_PLATE + "'"))));

            try (PreparedStatement ps = maria.prepareStatement(
                    "SELECT t.HOURS, t.DESCRIPTION, t.BILLABLE FROM TIME_ENTRY t "
                            + "JOIN EMPLOYEE e ON e.ID = t.EMPLOYEE_ID "
                            + "WHERE e.LAST_NAME = ? AND t.PROJECT_CODE = ? AND t.WORK_DAY = ?")) {
                ps.setString(1, HibernateFixtureContract.EMP1_LAST_NAME);
                ps.setString(2, HibernateFixtureContract.PROJECT1_CODE);
                ps.setObject(3, HibernateFixtureContract.TIME_ENTRY1_DAY);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Zeile mit zusammengesetztem Schluessel nicht gefunden");
                    assertEquals(0, HibernateFixtureContract.TIME_ENTRY1_HOURS
                            .compareTo(rs.getBigDecimal("HOURS")));
                    assertTrue(rs.getBoolean("BILLABLE"));
                }
            }
        }
    }

    // ---- Helfer ----

    /** Fuehrt die Pruefung mit je einer offenen Verbindung auf Quelle und Ziel aus. */
    private void withBothDatabases(BothDatabases check) throws Exception {
        try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "");
             Connection maria = openMaria()) {
            check.run(JdbcSnapshot.ofH2(h2), JdbcSnapshot.ofMariaDb(maria));
        }
    }

    @FunctionalInterface
    private interface BothDatabases {
        void run(JdbcSnapshot h2, JdbcSnapshot maria) throws Exception;
    }

    private Connection openMaria() throws SQLException {
        return DriverManager.getConnection(mariaUrl, "root", "");
    }

    private static String columnType(Connection maria, String table, String column) throws SQLException {
        return informationSchemaColumn(maria, "COLUMN_TYPE", table, column);
    }

    /** Typ ohne Klammerzusatz. MariaDB haengt an Integer-Spalten eine Anzeigebreite an
     *  (int(11), bigint(20), tinyint(4)), die weder Wertebereich noch Speicherbedarf
     *  beeinflusst -- fuer diese Typen ist nur der Basisname aussagekraeftig. */
    private static String baseColumnType(Connection maria, String table, String column) throws SQLException {
        String type = columnType(maria, table, column);
        int paren = type.indexOf('(');
        return paren < 0 ? type : type.substring(0, paren);
    }

    private static String columnDefault(Connection maria, String table, String column) throws SQLException {
        return informationSchemaColumn(maria, "COLUMN_DEFAULT", table, column);
    }

    private static String extra(Connection maria, String table, String column) throws SQLException {
        return informationSchemaColumn(maria, "EXTRA", table, column);
    }

    private static String informationSchemaColumn(Connection maria, String select, String table,
                                                  String column) throws SQLException {
        String sql = "SELECT " + select + " FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = maria.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("Spalte " + table + "." + column + " fehlt im Ziel");
                }
                return rs.getString(1);
            }
        }
    }

    private static List<String> indexColumns(Connection maria, String indexName) throws SQLException {
        List<String> columns = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX";
        try (PreparedStatement ps = maria.prepareStatement(sql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }

    private static String scalar(Connection maria, String sql) throws SQLException {
        try (Statement stmt = maria.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
