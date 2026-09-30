package com.pixeldweller.migrape.migration;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.pixeldweller.migrape.MigrationConfig;
import com.pixeldweller.migrape.testsupport.RichSchemaFixture;
import com.pixeldweller.migrape.testsupport.TestConfigs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-End-Test: erzeugt eine H2-File-Datenbank mit einem bewusst vielfaeltigen Schema
 * (siehe {@link RichSchemaFixture}), startet lokal eine embedded MariaDB via MariaDB4j und
 * migriert die H2-Datenbank per {@link MigrationService} komplett rueber. Anschliessend wird
 * nicht nur die Zeilenanzahl verglichen, sondern stichprobenartig jede Spalte pro Typ auf
 * exakte Wert-Erhaltung geprueft -- plus die Schema-Objekte (ENUM-Typ, DEFAULT, Unique,
 * Index, zusammengesetzter Fremdschluessel).
 *
 * Benoetigt auf dem Klassenpfad ch.vorburger.mariaDB4j (siehe pom.xml, test-scope) inkl. der
 * zur Plattform passenden mariaDB4j-db-* Binaries.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class H2ToMariaDbMigrationTest {

    private static final String MARIA_DB_NAME = "migrape_test";

    @TempDir
    Path workDir;

    private DB embeddedMariaDb;
    private String mariaUrl;
    private String h2Url;
    private RichSchemaFixture.Ids ids;

    @BeforeAll
    void startEmbeddedMariaDb() throws Exception {
        DBConfigurationBuilder builder = DBConfigurationBuilder.newBuilder();
        builder.setPort(0); // freien Port automatisch waehlen, um lokale Konflikte zu vermeiden
        builder.setDataDir(new File(Files.createTempDirectory("migrape-mariadb-data").toString()));

        embeddedMariaDb = DB.newEmbeddedDB(builder.build());
        embeddedMariaDb.start();
        embeddedMariaDb.createDB(MARIA_DB_NAME);

        mariaUrl = builder.getURL(MARIA_DB_NAME);
    }

    @AfterAll
    void stopEmbeddedMariaDb() throws Exception {
        if (embeddedMariaDb != null) {
            embeddedMariaDb.stop();
        }
    }

    @BeforeEach
    void createH2SourceDatabase() throws SQLException {
        // Pro Test eine eigene H2-Datei, damit die Fixture mehrfach aufgebaut werden kann.
        Path h2File = workDir.resolve("source-" + UUID.randomUUID());
        h2Url = "jdbc:h2:file:" + h2File.toString().replace('\\', '/');

        try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "")) {
            RichSchemaFixture.createSchema(h2);
            ids = RichSchemaFixture.insertData(h2);
        }
    }

    @Test
    void migratesRichSchemaFromH2ToMariaDbWithFullFidelity() throws Exception {
        MigrationService service = migrate();

        List<VerificationResult> results = service.verify();
        assertEquals(7, results.size(), "Erwartet: PUBLISHER, AUTHOR, BOOK, BOOK_REVIEW, "
                + "REVIEW_VOTE, SETTINGS, CATEGORY -- war: " + tableNames(results));
        for (VerificationResult r : results) {
            assertTrue(r.ok(), r.table() + ": H2=" + r.sourceCount() + " MariaDB=" + r.targetCount()
                    + (r.problem() == null ? "" : " Problem: " + r.problem()));
            assertExpectedRowCount(r);
        }

        try (Connection maria = DriverManager.getConnection(mariaUrl, "root", "")) {
            assertPublisher(maria);
            assertAuthor1FullyPopulated(maria);
            assertAuthor2AllNullableFieldsAreNull(maria);
            assertBook1AllColumnTypes(maria);
            assertBook2EdgeCases(maria);
            assertBookReviewsCompositeKey(maria);
            assertReservedWordColumns(maria);
            assertSelfReferencingRowsAndUnboundedText(maria);
            assertSchemaObjects(maria);
        }
    }

    @Test
    @DisplayName("'resume' ueberspringt fertige Tabellen, 'data' kopiert sie neu")
    void resumeSkipsCompletedTablesWhileDataRecopiesThem() throws Exception {
        MigrationConfig config = TestConfigs.forMigration(h2Url, mariaUrl, 50);
        MigrationService service = new MigrationService(config);
        MigrationState state = new MigrationState(workDir.resolve("resume-" + UUID.randomUUID() + ".state"));

        service.migrateSchema(true);
        service.migrateData(state, false);
        assertEquals(2, countRows("SETTINGS"));

        // SETTINGS leeren: 'resume' darf die Tabelle nicht anfassen, weil sie als DONE gilt.
        try (Connection maria = DriverManager.getConnection(mariaUrl, "root", "");
             Statement stmt = maria.createStatement()) {
            stmt.execute("DELETE FROM SETTINGS");
        }

        service.migrateData(state, true);
        assertEquals(0, countRows("SETTINGS"), "'resume' haette SETTINGS ueberspringen muessen");

        service.migrateData(state, false);
        assertEquals(2, countRows("SETTINGS"), "'data' haette SETTINGS neu kopieren muessen");
    }

    private MigrationService migrate() throws Exception {
        Path configFile = workDir.resolve("config-" + UUID.randomUUID() + ".properties");
        Files.writeString(configFile, """
                h2.url=%s
                h2.user=sa
                h2.password=
                maria.url=%s
                maria.user=root
                maria.password=
                batch.size=50
                fetch.size=50
                """.formatted(h2Url, mariaUrl));

        MigrationConfig config = MigrationConfig.load(configFile);
        MigrationService service = new MigrationService(config);
        service.migrateSchema(true);
        service.migrateData(new MigrationState(workDir.resolve("state-" + UUID.randomUUID())), false);
        return service;
    }

    private static List<String> tableNames(List<VerificationResult> results) {
        List<String> names = new ArrayList<>();
        for (VerificationResult r : results) {
            names.add(r.table());
        }
        return names;
    }

    private void assertExpectedRowCount(VerificationResult r) {
        long expected = switch (r.table()) {
            case "PUBLISHER" -> 1L;
            case "AUTHOR" -> 2L;
            case "BOOK" -> 2L;
            case "BOOK_REVIEW" -> 3L;
            case "REVIEW_VOTE" -> 1L;
            case "SETTINGS" -> 2L;
            case "CATEGORY" -> 2L;
            default -> fail("Unerwartete Tabelle in Verifikation: " + r.table());
        };
        assertEquals(expected, r.targetCount(), "Zeilenanzahl MariaDB." + r.table());
    }

    private void assertPublisher(Connection maria) throws SQLException {
        try (PreparedStatement ps = maria.prepareStatement(
                "SELECT NAME, CONTACT_EMAIL, FOUNDED_DATE, ACTIVE, CREATED_AT FROM PUBLISHER WHERE ID = ?")) {
            ps.setLong(1, ids.publisherId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(RichSchemaFixture.PUBLISHER_NAME, rs.getString("NAME"));
                assertEquals(RichSchemaFixture.PUBLISHER_EMAIL, rs.getString("CONTACT_EMAIL"));
                assertEquals(RichSchemaFixture.PUBLISHER_FOUNDED, rs.getDate("FOUNDED_DATE").toLocalDate());
                assertTrue(rs.getBoolean("ACTIVE"));
                assertEquals(RichSchemaFixture.PUBLISHER_CREATED_AT_UTC,
                        rs.getTimestamp("CREATED_AT").toLocalDateTime(),
                        "TIMESTAMP WITH TIME ZONE muss als UTC-Zeitpunkt ankommen");
            }
        }
    }

    private void assertAuthor1FullyPopulated(Connection maria) throws SQLException {
        try (PreparedStatement ps = maria.prepareStatement(
                "SELECT FIRST_NAME, LAST_NAME, BIRTH_DATE, NATIONALITY, RATING, IS_ACTIVE, "
                        + "EXTERNAL_ID, BIOGRAPHY, PORTRAIT FROM AUTHOR WHERE ID = ?")) {
            ps.setLong(1, ids.author1Id());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(RichSchemaFixture.AUTHOR1_FIRST, rs.getString("FIRST_NAME"));
                assertEquals(RichSchemaFixture.AUTHOR1_LAST, rs.getString("LAST_NAME"));
                assertEquals(RichSchemaFixture.AUTHOR1_BIRTH, rs.getDate("BIRTH_DATE").toLocalDate());
                assertEquals(RichSchemaFixture.AUTHOR1_NATIONALITY, rs.getString("NATIONALITY"));
                // 9.87 ist in 4-Byte-FLOAT nicht exakt darstellbar -- die Toleranz muss so
                // klein sein, dass eine Abbildung auf FLOAT auffaellt.
                assertEquals(RichSchemaFixture.AUTHOR1_RATING, rs.getDouble("RATING"), 1e-12,
                        "H2 DOUBLE muss als MariaDB DOUBLE ankommen, nicht als FLOAT");
                assertTrue(rs.getBoolean("IS_ACTIVE"));
                assertEquals(RichSchemaFixture.AUTHOR1_EXTERNAL_ID.toString(),
                        rs.getString("EXTERNAL_ID").toLowerCase(Locale.ROOT));
                assertEquals(RichSchemaFixture.AUTHOR1_BIOGRAPHY, rs.getString("BIOGRAPHY"));
                assertArrayEquals(RichSchemaFixture.AUTHOR1_PORTRAIT, rs.getBytes("PORTRAIT"));
            }
        }
    }

    private void assertAuthor2AllNullableFieldsAreNull(Connection maria) throws SQLException {
        try (PreparedStatement ps = maria.prepareStatement(
                "SELECT FIRST_NAME, LAST_NAME, BIRTH_DATE, NATIONALITY, RATING, IS_ACTIVE, "
                        + "EXTERNAL_ID, BIOGRAPHY, PORTRAIT FROM AUTHOR WHERE ID = ?")) {
            ps.setLong(1, ids.author2Id());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(RichSchemaFixture.AUTHOR2_FIRST, rs.getString("FIRST_NAME"));
                assertEquals(RichSchemaFixture.AUTHOR2_LAST, rs.getString("LAST_NAME"));
                assertNull(rs.getDate("BIRTH_DATE"));
                assertNull(rs.getString("NATIONALITY"));
                rs.getDouble("RATING");
                assertTrue(rs.wasNull());
                assertNull(rs.getObject("IS_ACTIVE"));
                assertNull(rs.getString("EXTERNAL_ID"));
                assertNull(rs.getString("BIOGRAPHY"));
                assertNull(rs.getBytes("PORTRAIT"));
            }
        }
    }

    private void assertBook1AllColumnTypes(Connection maria) throws SQLException {
        try (PreparedStatement ps = maria.prepareStatement(
                "SELECT TITLE, ISBN, PRICE, WEIGHT_KG, PAGE_COUNT, STOCK, RATING_AVG, PUBLISHED_DATE, "
                        + "PUBLISHED_AT, LAST_MODIFIED, ON_SALE_TIME, DOORS_OPEN_TIME, STATUS, DESCRIPTION, "
                        + "COVER_IMAGE, AUTHOR_ID, PUBLISHER_ID, TAGS FROM BOOK WHERE ID = ?")) {
            ps.setLong(1, ids.book1Id());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(RichSchemaFixture.BOOK1_TITLE, rs.getString("TITLE"));
                assertEquals(RichSchemaFixture.BOOK1_ISBN, rs.getString("ISBN"));
                assertEquals(0, RichSchemaFixture.BOOK1_PRICE.compareTo(rs.getBigDecimal("PRICE")));
                assertEquals(0, RichSchemaFixture.BOOK1_WEIGHT.compareTo(rs.getBigDecimal("WEIGHT_KG")));
                assertEquals(RichSchemaFixture.BOOK1_PAGES, rs.getInt("PAGE_COUNT"));
                assertEquals(RichSchemaFixture.BOOK1_STOCK, rs.getShort("STOCK"));
                assertEquals(RichSchemaFixture.BOOK1_RATING_AVG, rs.getFloat("RATING_AVG"), 0.001f);
                assertEquals(RichSchemaFixture.BOOK1_PUBLISHED_DATE, rs.getDate("PUBLISHED_DATE").toLocalDate());
                assertEquals(RichSchemaFixture.BOOK1_PUBLISHED_AT, rs.getTimestamp("PUBLISHED_AT").toLocalDateTime());
                LocalDateTime lastModified = rs.getTimestamp("LAST_MODIFIED").toLocalDateTime();
                assertEquals(RichSchemaFixture.BOOK1_LAST_MODIFIED, lastModified,
                        "Mikrosekunden-Praezision muss durch DATETIME(6) erhalten bleiben");
                assertEquals(RichSchemaFixture.BOOK1_ON_SALE_TIME, rs.getTime("ON_SALE_TIME").toLocalTime());
                assertEquals(RichSchemaFixture.BOOK1_DOORS_OPEN_TIME,
                        rs.getObject("DOORS_OPEN_TIME", java.time.LocalTime.class),
                        "Millisekunden aus TIME(3) muessen erhalten bleiben");
                assertEquals(RichSchemaFixture.BOOK1_STATUS, rs.getString("STATUS"));
                assertEquals(RichSchemaFixture.BOOK1_DESCRIPTION, rs.getString("DESCRIPTION"));
                assertArrayEquals(RichSchemaFixture.BOOK1_COVER, rs.getBytes("COVER_IMAGE"));
                assertEquals(ids.author1Id(), rs.getLong("AUTHOR_ID"));
                assertEquals(ids.publisherId(), rs.getLong("PUBLISHER_ID"));
                assertEquals(RichSchemaFixture.BOOK1_TAGS_AS_JSON, rs.getString("TAGS"),
                        "H2-ARRAY muss als JSON-Array ankommen");
            }
        }
    }

    private void assertBook2EdgeCases(Connection maria) throws SQLException {
        try (PreparedStatement ps = maria.prepareStatement(
                "SELECT TITLE, PRICE, PAGE_COUNT, STOCK, RATING_AVG, PUBLISHED_DATE, ON_SALE_TIME, "
                        + "DOORS_OPEN_TIME, STATUS, ISBN, WEIGHT_KG, PUBLISHER_ID, TAGS FROM BOOK WHERE ID = ?")) {
            ps.setLong(1, ids.book2Id());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(RichSchemaFixture.BOOK2_TITLE, rs.getString("TITLE"),
                        "Unicode-Titel (Umlaute, CJK) muss verlustfrei erhalten bleiben");
                assertEquals(0, RichSchemaFixture.BOOK2_PRICE.compareTo(rs.getBigDecimal("PRICE")));
                assertEquals(RichSchemaFixture.BOOK2_PAGES, rs.getInt("PAGE_COUNT"));
                assertEquals(RichSchemaFixture.BOOK2_STOCK, rs.getShort("STOCK"), "Negativer SMALLINT-Wert");
                rs.getFloat("RATING_AVG");
                assertTrue(rs.wasNull());
                assertNull(rs.getDate("PUBLISHED_DATE"));
                assertNull(rs.getTime("ON_SALE_TIME"));
                assertNull(rs.getTime("DOORS_OPEN_TIME"));
                assertEquals(RichSchemaFixture.BOOK2_STATUS, rs.getString("STATUS"));
                assertNull(rs.getString("ISBN"));
                assertNull(rs.getBigDecimal("WEIGHT_KG"));
                rs.getLong("PUBLISHER_ID");
                assertTrue(rs.wasNull(), "PUBLISHER_ID muss NULL bleiben (optionale Fremdschluessel-Referenz)");
                assertNull(rs.getString("TAGS"));
            }
        }
    }

    private void assertBookReviewsCompositeKey(Connection maria) throws SQLException {
        try (PreparedStatement ps = maria.prepareStatement(
                "SELECT SCORE, COMMENT_TEXT FROM BOOK_REVIEW WHERE BOOK_ID = ? AND REVIEWER = ?")) {
            ps.setLong(1, ids.book1Id());
            ps.setString(2, RichSchemaFixture.REVIEW1_REVIEWER);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Review 1 (zusammengesetzter PK) nicht gefunden");
                assertEquals(RichSchemaFixture.REVIEW1_SCORE, rs.getByte("SCORE"));
                assertFalse(rs.getString("COMMENT_TEXT").isBlank());
            }
        }

        try (PreparedStatement ps = maria.prepareStatement(
                "SELECT SCORE, COMMENT_TEXT FROM BOOK_REVIEW WHERE BOOK_ID = ? AND REVIEWER = ?")) {
            ps.setLong(1, ids.book1Id());
            ps.setString(2, RichSchemaFixture.REVIEW2_REVIEWER);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Review 2 (zusammengesetzter PK) nicht gefunden");
                rs.getByte("SCORE");
                assertTrue(rs.wasNull(), "SCORE sollte NULL sein");
                assertNull(rs.getString("COMMENT_TEXT"));
            }
        }

        try (PreparedStatement ps = maria.prepareStatement(
                "SELECT SCORE FROM BOOK_REVIEW WHERE BOOK_ID = ? AND REVIEWER = ?")) {
            ps.setLong(1, ids.book2Id());
            ps.setString(2, RichSchemaFixture.REVIEW3_REVIEWER);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Review 3 (referenziert BOOK2) nicht gefunden");
                assertEquals(0, rs.getByte("SCORE"));
            }
        }

        try (PreparedStatement ps = maria.prepareStatement(
                "SELECT HELPFUL FROM REVIEW_VOTE WHERE BOOK_ID = ? AND REVIEWER = ? AND VOTER = ?")) {
            ps.setLong(1, ids.book1Id());
            ps.setString(2, RichSchemaFixture.REVIEW1_REVIEWER);
            ps.setString(3, RichSchemaFixture.VOTE_VOTER);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "REVIEW_VOTE-Zeile nicht gefunden");
                assertTrue(rs.getBoolean("HELPFUL"));
            }
        }
    }

    /** KEY, VALUE und YEAR sind reservierte Woerter -- ohne Quoting bricht schon der SELECT. */
    private void assertReservedWordColumns(Connection maria) throws SQLException {
        try (PreparedStatement ps = maria.prepareStatement(
                "SELECT `VALUE`, `YEAR` FROM SETTINGS WHERE `KEY` = ?")) {
            ps.setString(1, RichSchemaFixture.SETTING_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "SETTINGS-Zeile nicht gefunden");
                assertEquals(RichSchemaFixture.SETTING_VALUE, rs.getString("VALUE"));
                assertEquals(RichSchemaFixture.SETTING_YEAR, rs.getInt("YEAR"));
            }
        }
    }

    private void assertSelfReferencingRowsAndUnboundedText(Connection maria) throws SQLException {
        try (PreparedStatement ps = maria.prepareStatement(
                "SELECT PARENT_ID, NAME, NOTES FROM CATEGORY WHERE ID = ?")) {
            ps.setInt(1, RichSchemaFixture.CATEGORY_CHILD_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Kind-Kategorie nicht gefunden");
                assertEquals(RichSchemaFixture.CATEGORY_ROOT_ID, rs.getInt("PARENT_ID"));
                assertEquals(RichSchemaFixture.CATEGORY_CHILD_NAME, rs.getString("NAME"));
            }

            ps.setInt(1, RichSchemaFixture.CATEGORY_ROOT_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Wurzel-Kategorie nicht gefunden");
                rs.getInt("PARENT_ID");
                assertTrue(rs.wasNull());
                // VARCHAR ohne Laengenangabe: in einer TEXT-Spalte waere dieser Wert zu lang.
                assertEquals(RichSchemaFixture.CATEGORY_ROOT_NOTES, rs.getString("NOTES"));
            }
        }
    }

    /** Das Zielschema muss auch die Nebenobjekte enthalten, nicht nur Spalten und Daten. */
    private void assertSchemaObjects(Connection maria) throws SQLException {
        assertEquals("enum('DRAFT','PUBLISHED','ARCHIVED')",
                scalar(maria, "SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'BOOK' AND COLUMN_NAME = 'STATUS'"),
                "STATUS muss als echter ENUM-Typ ankommen, nicht als LONGTEXT");

        assertEquals("char(36)",
                scalar(maria, "SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'AUTHOR' AND COLUMN_NAME = 'EXTERNAL_ID'"));

        assertEquals("longtext",
                scalar(maria, "SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'CATEGORY' AND COLUMN_NAME = 'NOTES'"),
                "VARCHAR ohne Laenge darf nicht als TEXT landen");

        assertEquals("double",
                scalar(maria, "SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'AUTHOR' AND COLUMN_NAME = 'RATING'"));

        assertEquals("1",
                scalar(maria, "SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'PUBLISHER' AND COLUMN_NAME = 'ACTIVE'"),
                "DEFAULT TRUE muss als DEFAULT 1 uebernommen werden");

        assertEquals("UNIQUE",
                scalar(maria, "SELECT CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'UQ_AUTHOR_EXTERNAL'"),
                "Unique-Constraint fehlt im Zielschema");

        // Standard ist maria.identifier.case=lower: Spalten- und Constraint-Namen kommen klein an.
        // (Die Tabellennamen legt die Windows-MariaDB der Tests ohnehin klein ab, daran liesse
        // sich die Umschreibung nicht erkennen -- an den Spaltennamen schon.)
        assertEquals("title",
                scalar(maria, "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'BOOK' "
                        + "AND INDEX_NAME = 'IDX_BOOK_TITLE'"),
                "Sekundaerindex fehlt im Zielschema");
        assertEquals("idx_book_title",
                scalar(maria, "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND INDEX_NAME = 'IDX_BOOK_TITLE'"));
        assertEquals(List.of(), columnsWithUpperCase(maria, "BOOK"),
                "Mit maria.identifier.case=lower darf keine Spalte gross geschrieben ankommen");

        // Zusammengesetzter Fremdschluessel: eine Constraint mit zwei Spalten, nicht zwei Constraints.
        List<String> fkColumns = new ArrayList<>();
        try (Statement stmt = maria.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'FK_VOTE_REVIEW' "
                             + "ORDER BY ORDINAL_POSITION")) {
            while (rs.next()) {
                fkColumns.add(rs.getString(1));
            }
        }
        assertEquals(List.of("book_id", "reviewer"), fkColumns,
                "Zusammengesetzter Fremdschluessel muss beide Spalten in richtiger Reihenfolge haben");

        // Selbstreferenzierender Fremdschluessel muss trotz Datenkopie existieren.
        assertEquals("FOREIGN KEY",
                scalar(maria, "SELECT CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'FK_CATEGORY_PARENT'"));

        // Die Sequenz setzt beim H2-Stand fort, sonst kollidieren neue IDs mit den migrierten.
        String sequence = "`" + RichSchemaFixture.SEQUENCE_NAME.toLowerCase(Locale.ROOT) + "`";
        assertEquals(String.valueOf(RichSchemaFixture.SEQUENCE_NEXT_VALUE),
                scalar(maria, "SELECT next_not_cached_value FROM " + sequence));
        assertEquals(String.valueOf(RichSchemaFixture.SEQUENCE_INCREMENT),
                scalar(maria, "SELECT increment FROM " + sequence));
        assertEquals(String.valueOf(RichSchemaFixture.SEQUENCE_NEXT_VALUE),
                scalar(maria, "SELECT NEXTVAL(" + sequence + ")"),
                "Der erste in MariaDB vergebene Wert muss der naechste aus H2 sein");
    }

    /** Spaltennamen der Tabelle, die Grossbuchstaben enthalten. */
    private static List<String> columnsWithUpperCase(Connection maria, String table) throws SQLException {
        List<String> upper = new ArrayList<>();
        try (PreparedStatement ps = maria.prepareStatement("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (!name.equals(name.toLowerCase(Locale.ROOT))) {
                        upper.add(name);
                    }
                }
            }
        }
        return upper;
    }

    private long countRows(String table) throws SQLException {
        try (Connection maria = DriverManager.getConnection(mariaUrl, "root", "");
             Statement stmt = maria.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String scalar(Connection maria, String sql) throws SQLException {
        try (Statement stmt = maria.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
