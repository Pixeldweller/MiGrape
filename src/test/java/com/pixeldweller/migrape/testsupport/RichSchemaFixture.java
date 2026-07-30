package com.pixeldweller.migrape.testsupport;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Baut ein absichtlich "reichhaltiges" H2-Test-Schema auf: Primitive Zahlentypen in
 * verschiedenen Groessen/Vorzeichen, Datums-/Zeittypen inkl. Sub-Sekunden-Praezision und
 * Zeitzone, Fremdschluessel (auch zusammengesetzt und selbstreferenzierend), ENUM, ein
 * DOMAIN-basierter User-Type, CLOB/BLOB/UUID/ARRAY-Spalten, Spalten mit Reserved-Word-Namen,
 * DEFAULT-Werte, Unique-Constraints und ein Sekundaerindex.
 *
 * Jede Konstruktion hier entspricht einem Fehler, der die Migration vorher zum Abbruch
 * gebracht oder still Daten verfaelscht hat.
 */
public final class RichSchemaFixture {

    private RichSchemaFixture() {
    }

    // ---- PUBLISHER ----
    public static final String PUBLISHER_NAME = "O'Reilly Media";
    public static final String PUBLISHER_EMAIL = "contact@oreilly.example";
    public static final LocalDate PUBLISHER_FOUNDED = LocalDate.of(1978, 1, 1);
    /** TIMESTAMP WITH TIME ZONE: +02:00, muss auf der Zielseite als UTC ankommen. */
    public static final OffsetDateTime PUBLISHER_CREATED_AT =
            OffsetDateTime.of(2020, 5, 1, 12, 0, 0, 0, ZoneOffset.ofHours(2));
    public static final LocalDateTime PUBLISHER_CREATED_AT_UTC =
            LocalDateTime.of(2020, 5, 1, 10, 0, 0);

    // ---- AUTHOR 1: vollstaendig befuellt, inkl. Unicode/Sonderzeichen ----
    public static final String AUTHOR1_FIRST = "Ada";
    public static final String AUTHOR1_LAST = "Lovelace";
    public static final LocalDate AUTHOR1_BIRTH = LocalDate.of(1815, 12, 10);
    public static final String AUTHOR1_NATIONALITY = "GB";
    public static final double AUTHOR1_RATING = 9.87d;
    public static final boolean AUTHOR1_ACTIVE = true;
    public static final UUID AUTHOR1_EXTERNAL_ID = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");
    public static final String AUTHOR1_BIOGRAPHY =
            ("Ada Lovelace war eine britische Mathematikerin. "
                    + "Straße, Müller, naïve café — Umlaute & Sonderzeichen: äöüÄÖÜß. ").repeat(200);
    public static final byte[] AUTHOR1_PORTRAIT = buildBytes(512);

    // ---- AUTHOR 2: bewusst voller NULL-Werte in allen nullable Spalten ----
    public static final String AUTHOR2_FIRST = "Unbekannt";
    public static final String AUTHOR2_LAST = "Autor:in";

    // ---- BOOK 1: normale Werte ueber alle Spaltentypen ----
    public static final String BOOK1_TITLE = "The Analytical Engine";
    public static final String BOOK1_ISBN = "9780123456789";
    public static final BigDecimal BOOK1_PRICE = new BigDecimal("49.99");
    public static final BigDecimal BOOK1_WEIGHT = new BigDecimal("1.250");
    public static final int BOOK1_PAGES = 350;
    public static final short BOOK1_STOCK = 12;
    public static final float BOOK1_RATING_AVG = 4.5f;
    public static final LocalDate BOOK1_PUBLISHED_DATE = LocalDate.of(2015, 6, 1);
    public static final LocalDateTime BOOK1_PUBLISHED_AT = LocalDateTime.of(2015, 6, 1, 10, 30, 0);
    // Mikrosekunden-genau, damit TIMESTAMP(6) <-> DATETIME(6) exakt (ohne Rundung) vergleichbar ist
    public static final LocalDateTime BOOK1_LAST_MODIFIED = LocalDateTime.of(2024, 3, 15, 8, 0, 0, 123_456_000);
    public static final LocalTime BOOK1_ON_SALE_TIME = LocalTime.of(9, 0, 0);
    /** TIME(3): H2 liefert dafuer java.sql.Time, dessen toLocalTime() die Millisekunden verwirft. */
    public static final LocalTime BOOK1_DOORS_OPEN_TIME = LocalTime.of(8, 30, 0, 123_000_000);
    public static final String BOOK1_STATUS = "PUBLISHED";
    public static final String BOOK1_DESCRIPTION = "Eine ausfuehrliche Beschreibung. ".repeat(500);
    public static final byte[] BOOK1_COVER = buildBytes(2048);
    /** H2-ARRAY, landet als JSON-Array in einer LONGTEXT-Spalte. */
    public static final Object[] BOOK1_TAGS = {3, 7, 11};
    public static final String BOOK1_TAGS_AS_JSON = "[3,7,11]";

    // ---- BOOK 2: Edge Cases - negative Zahlen, Nullwerte, Unicode-Titel, keine Publisher-Referenz ----
    public static final String BOOK2_TITLE = "Über Grenzen™ — 你好世界";
    public static final BigDecimal BOOK2_PRICE = new BigDecimal("0.00");
    public static final int BOOK2_PAGES = 0;
    public static final short BOOK2_STOCK = -5;
    public static final String BOOK2_STATUS = "DRAFT";

    // ---- BOOK_REVIEW: zusammengesetzter Primary Key (BOOK_ID, REVIEWER) ----
    public static final String REVIEW1_REVIEWER = "Grace H.";
    public static final byte REVIEW1_SCORE = 9;
    public static final String REVIEW2_REVIEWER = "Alan T.";
    public static final String REVIEW3_REVIEWER = "Katherine J.";

    // ---- REVIEW_VOTE: zusammengesetzter Fremdschluessel auf BOOK_REVIEW ----
    public static final String VOTE_VOTER = "Margaret H.";

    // ---- SETTINGS: Spaltennamen, die in SQL reservierte Woerter sind ----
    public static final String SETTING_KEY = "default.locale";
    public static final String SETTING_VALUE = "de-DE";
    public static final int SETTING_YEAR = 2024;

    // ---- CATEGORY: selbstreferenzierender Fremdschluessel + VARCHAR ohne Laengenangabe ----
    public static final int CATEGORY_ROOT_ID = 2;
    public static final int CATEGORY_CHILD_ID = 1;
    public static final String CATEGORY_ROOT_NAME = "Wurzel";
    public static final String CATEGORY_CHILD_NAME = "Kind";
    /** Laenger als 65535 Byte -- in einer TEXT-Spalte wuerde das abgewiesen/abgeschnitten. */
    public static final String CATEGORY_ROOT_NOTES = "Sehr lange Notiz mit Umlauten äöü. ".repeat(3000);

    public record Ids(long publisherId, long author1Id, long author2Id, long book1Id, long book2Id) {
    }

    public static void createSchema(Connection h2) throws SQLException {
        String[] ddl = {
                "CREATE DOMAIN IF NOT EXISTS EMAIL_TYPE AS VARCHAR(255)",

                """
                CREATE TABLE PUBLISHER (
                    ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    NAME VARCHAR(200) NOT NULL,
                    CONTACT_EMAIL EMAIL_TYPE,
                    FOUNDED_DATE DATE,
                    ACTIVE BOOLEAN NOT NULL DEFAULT TRUE,
                    CREATED_AT TIMESTAMP WITH TIME ZONE
                )
                """,

                """
                CREATE TABLE AUTHOR (
                    ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    FIRST_NAME VARCHAR(100) NOT NULL,
                    LAST_NAME VARCHAR(100) NOT NULL,
                    BIRTH_DATE DATE,
                    NATIONALITY CHAR(2),
                    RATING DOUBLE,
                    IS_ACTIVE BOOLEAN,
                    EXTERNAL_ID UUID,
                    BIOGRAPHY CLOB,
                    PORTRAIT BLOB,
                    CONSTRAINT UQ_AUTHOR_EXTERNAL UNIQUE (EXTERNAL_ID)
                )
                """,

                """
                CREATE TABLE BOOK (
                    ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    AUTHOR_ID BIGINT NOT NULL,
                    PUBLISHER_ID BIGINT,
                    TITLE VARCHAR(300) NOT NULL,
                    ISBN CHAR(13),
                    PRICE DECIMAL(10,2),
                    WEIGHT_KG NUMERIC(6,3),
                    PAGE_COUNT INT,
                    STOCK SMALLINT,
                    RATING_AVG REAL,
                    PUBLISHED_DATE DATE,
                    PUBLISHED_AT TIMESTAMP,
                    LAST_MODIFIED TIMESTAMP(6),
                    ON_SALE_TIME TIME,
                    DOORS_OPEN_TIME TIME(3),
                    STATUS ENUM('DRAFT', 'PUBLISHED', 'ARCHIVED') NOT NULL,
                    DESCRIPTION CLOB,
                    COVER_IMAGE BLOB,
                    TAGS INT ARRAY,
                    FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHOR(ID),
                    FOREIGN KEY (PUBLISHER_ID) REFERENCES PUBLISHER(ID)
                )
                """,

                "CREATE INDEX IDX_BOOK_TITLE ON BOOK (TITLE)",

                """
                CREATE TABLE BOOK_REVIEW (
                    BOOK_ID BIGINT NOT NULL,
                    REVIEWER VARCHAR(100) NOT NULL,
                    REVIEW_DATE DATE NOT NULL,
                    SCORE TINYINT,
                    COMMENT_TEXT CLOB,
                    PRIMARY KEY (BOOK_ID, REVIEWER),
                    FOREIGN KEY (BOOK_ID) REFERENCES BOOK(ID)
                )
                """,

                // Zusammengesetzter Fremdschluessel: getImportedKeys liefert dafuer zwei Zeilen,
                // die zu EINEM Constraint gehoeren.
                """
                CREATE TABLE REVIEW_VOTE (
                    BOOK_ID BIGINT NOT NULL,
                    REVIEWER VARCHAR(100) NOT NULL,
                    VOTER VARCHAR(100) NOT NULL,
                    HELPFUL BOOLEAN NOT NULL,
                    PRIMARY KEY (BOOK_ID, REVIEWER, VOTER),
                    CONSTRAINT FK_VOTE_REVIEW FOREIGN KEY (BOOK_ID, REVIEWER)
                        REFERENCES BOOK_REVIEW (BOOK_ID, REVIEWER)
                )
                """,

                // Spaltennamen, die ohne Quoting die SELECT-Abfrage zerlegen.
                """
                CREATE TABLE SETTINGS (
                    "KEY" VARCHAR(50) PRIMARY KEY,
                    "VALUE" VARCHAR(200),
                    "YEAR" INT
                )
                """,

                // Selbstreferenz: die Kindzeile hat die kleinere ID und wird daher vor der
                // Elternzeile kopiert -- mit aktiven FK-Pruefungen wuerde das fehlschlagen.
                """
                CREATE TABLE CATEGORY (
                    ID INT PRIMARY KEY,
                    PARENT_ID INT,
                    NAME VARCHAR(100) NOT NULL,
                    NOTES VARCHAR,
                    CONSTRAINT FK_CATEGORY_PARENT FOREIGN KEY (PARENT_ID) REFERENCES CATEGORY (ID)
                )
                """
        };

        try (Statement stmt = h2.createStatement()) {
            for (String sql : ddl) {
                stmt.execute(sql);
            }
        }
    }

    public static Ids insertData(Connection h2) throws SQLException {
        long publisherId = insertPublisher(h2);
        long author1Id = insertAuthor1(h2);
        long author2Id = insertAuthor2(h2);
        long book1Id = insertBook1(h2, author1Id, publisherId);
        long book2Id = insertBook2(h2, author2Id);
        insertReviews(h2, book1Id, book2Id);
        insertReviewVote(h2, book1Id);
        insertSettings(h2);
        insertCategories(h2);
        return new Ids(publisherId, author1Id, author2Id, book1Id, book2Id);
    }

    private static long insertPublisher(Connection h2) throws SQLException {
        String sql = "INSERT INTO PUBLISHER (NAME, CONTACT_EMAIL, FOUNDED_DATE, ACTIVE, CREATED_AT) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = h2.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, PUBLISHER_NAME);
            ps.setString(2, PUBLISHER_EMAIL);
            ps.setObject(3, PUBLISHER_FOUNDED);
            ps.setBoolean(4, true);
            ps.setObject(5, PUBLISHER_CREATED_AT);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private static long insertAuthor1(Connection h2) throws SQLException {
        String sql = "INSERT INTO AUTHOR (FIRST_NAME, LAST_NAME, BIRTH_DATE, NATIONALITY, RATING, "
                + "IS_ACTIVE, EXTERNAL_ID, BIOGRAPHY, PORTRAIT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = h2.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, AUTHOR1_FIRST);
            ps.setString(2, AUTHOR1_LAST);
            // Bewusst java.time: java.sql.Date ist ein Epoch-Millis-Zeitpunkt, den H2 ueber UTC
            // interpretiert -- bei 1815 verschiebt der damalige Berliner LMT-Offset (+00:53:28)
            // das Datum um einen Tag nach hinten, noch bevor die Migration laeuft.
            ps.setObject(3, AUTHOR1_BIRTH);
            ps.setString(4, AUTHOR1_NATIONALITY);
            ps.setDouble(5, AUTHOR1_RATING);
            ps.setBoolean(6, AUTHOR1_ACTIVE);
            ps.setObject(7, AUTHOR1_EXTERNAL_ID);
            ps.setString(8, AUTHOR1_BIOGRAPHY);
            ps.setBytes(9, AUTHOR1_PORTRAIT);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private static long insertAuthor2(Connection h2) throws SQLException {
        String sql = "INSERT INTO AUTHOR (FIRST_NAME, LAST_NAME, BIRTH_DATE, NATIONALITY, RATING, "
                + "IS_ACTIVE, EXTERNAL_ID, BIOGRAPHY, PORTRAIT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = h2.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, AUTHOR2_FIRST);
            ps.setString(2, AUTHOR2_LAST);
            ps.setNull(3, Types.DATE);
            ps.setNull(4, Types.CHAR);
            ps.setNull(5, Types.DOUBLE);
            ps.setNull(6, Types.BOOLEAN);
            ps.setNull(7, Types.OTHER);
            ps.setNull(8, Types.CLOB);
            ps.setNull(9, Types.BLOB);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private static long insertBook1(Connection h2, long authorId, long publisherId) throws SQLException {
        String sql = "INSERT INTO BOOK (AUTHOR_ID, PUBLISHER_ID, TITLE, ISBN, PRICE, WEIGHT_KG, PAGE_COUNT, "
                + "STOCK, RATING_AVG, PUBLISHED_DATE, PUBLISHED_AT, LAST_MODIFIED, ON_SALE_TIME, "
                + "DOORS_OPEN_TIME, STATUS, DESCRIPTION, COVER_IMAGE, TAGS) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = h2.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, authorId);
            ps.setLong(2, publisherId);
            ps.setString(3, BOOK1_TITLE);
            ps.setString(4, BOOK1_ISBN);
            ps.setBigDecimal(5, BOOK1_PRICE);
            ps.setBigDecimal(6, BOOK1_WEIGHT);
            ps.setInt(7, BOOK1_PAGES);
            ps.setShort(8, BOOK1_STOCK);
            ps.setFloat(9, BOOK1_RATING_AVG);
            ps.setObject(10, BOOK1_PUBLISHED_DATE);
            ps.setObject(11, BOOK1_PUBLISHED_AT);
            ps.setObject(12, BOOK1_LAST_MODIFIED);
            ps.setObject(13, BOOK1_ON_SALE_TIME);
            ps.setObject(14, BOOK1_DOORS_OPEN_TIME);
            ps.setString(15, BOOK1_STATUS);
            ps.setString(16, BOOK1_DESCRIPTION);
            ps.setBytes(17, BOOK1_COVER);
            Array tags = h2.createArrayOf("INTEGER", BOOK1_TAGS);
            ps.setArray(18, tags);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private static long insertBook2(Connection h2, long authorId) throws SQLException {
        String sql = "INSERT INTO BOOK (AUTHOR_ID, PUBLISHER_ID, TITLE, ISBN, PRICE, WEIGHT_KG, PAGE_COUNT, "
                + "STOCK, RATING_AVG, PUBLISHED_DATE, PUBLISHED_AT, LAST_MODIFIED, ON_SALE_TIME, "
                + "DOORS_OPEN_TIME, STATUS, DESCRIPTION, COVER_IMAGE, TAGS) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = h2.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, authorId);
            ps.setNull(2, Types.BIGINT);
            ps.setString(3, BOOK2_TITLE);
            ps.setNull(4, Types.CHAR);
            ps.setBigDecimal(5, BOOK2_PRICE);
            ps.setNull(6, Types.NUMERIC);
            ps.setInt(7, BOOK2_PAGES);
            ps.setShort(8, BOOK2_STOCK);
            ps.setNull(9, Types.REAL);
            ps.setNull(10, Types.DATE);
            ps.setNull(11, Types.TIMESTAMP);
            ps.setObject(12, LocalDateTime.now().withNano(0));
            ps.setNull(13, Types.TIME);
            ps.setNull(14, Types.TIME);
            ps.setString(15, BOOK2_STATUS);
            ps.setNull(16, Types.CLOB);
            ps.setNull(17, Types.BLOB);
            ps.setNull(18, Types.ARRAY);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private static void insertReviews(Connection h2, long book1Id, long book2Id) throws SQLException {
        String sql = "INSERT INTO BOOK_REVIEW (BOOK_ID, REVIEWER, REVIEW_DATE, SCORE, COMMENT_TEXT) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = h2.prepareStatement(sql)) {
            ps.setLong(1, book1Id);
            ps.setString(2, REVIEW1_REVIEWER);
            ps.setObject(3, LocalDate.of(2020, 1, 15));
            ps.setByte(4, REVIEW1_SCORE);
            ps.setString(5, "Bahnbrechend fuer ihre Zeit.");
            ps.addBatch();

            ps.setLong(1, book1Id);
            ps.setString(2, REVIEW2_REVIEWER);
            ps.setObject(3, LocalDate.of(2020, 2, 20));
            ps.setNull(4, Types.TINYINT);
            ps.setNull(5, Types.CLOB);
            ps.addBatch();

            ps.setLong(1, book2Id);
            ps.setString(2, REVIEW3_REVIEWER);
            ps.setObject(3, LocalDate.of(2021, 5, 5));
            ps.setByte(4, (byte) 0);
            ps.setString(5, "Noch im Entwurf.");
            ps.addBatch();

            ps.executeBatch();
        }
    }

    private static void insertReviewVote(Connection h2, long book1Id) throws SQLException {
        String sql = "INSERT INTO REVIEW_VOTE (BOOK_ID, REVIEWER, VOTER, HELPFUL) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = h2.prepareStatement(sql)) {
            ps.setLong(1, book1Id);
            ps.setString(2, REVIEW1_REVIEWER);
            ps.setString(3, VOTE_VOTER);
            ps.setBoolean(4, true);
            ps.executeUpdate();
        }
    }

    private static void insertSettings(Connection h2) throws SQLException {
        String sql = "INSERT INTO SETTINGS (\"KEY\", \"VALUE\", \"YEAR\") VALUES (?, ?, ?)";
        try (PreparedStatement ps = h2.prepareStatement(sql)) {
            ps.setString(1, SETTING_KEY);
            ps.setString(2, SETTING_VALUE);
            ps.setInt(3, SETTING_YEAR);
            ps.addBatch();

            ps.setString(1, "feature.flag");
            ps.setString(2, "aus");
            ps.setNull(3, Types.INTEGER);
            ps.addBatch();

            ps.executeBatch();
        }
    }

    private static void insertCategories(Connection h2) throws SQLException {
        String sql = "INSERT INTO CATEGORY (ID, PARENT_ID, NAME, NOTES) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = h2.prepareStatement(sql)) {
            // Elternzeile zuerst einfuegen (H2 prueft den FK sofort), sie hat aber die
            // groessere ID und wird daher beim Kopieren nach der Kindzeile gelesen.
            ps.setInt(1, CATEGORY_ROOT_ID);
            ps.setNull(2, Types.INTEGER);
            ps.setString(3, CATEGORY_ROOT_NAME);
            ps.setString(4, CATEGORY_ROOT_NOTES);
            ps.executeUpdate();

            ps.setInt(1, CATEGORY_CHILD_ID);
            ps.setInt(2, CATEGORY_ROOT_ID);
            ps.setString(3, CATEGORY_CHILD_NAME);
            ps.setNull(4, Types.VARCHAR);
            ps.executeUpdate();
        }
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }

    private static byte[] buildBytes(int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) (i % 256);
        }
        return b;
    }
}
