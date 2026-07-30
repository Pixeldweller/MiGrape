package com.pixeldweller.migrape.testsupport;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Der Vertrag zwischen den beiden Hibernate-Testreihen:
 * <ol>
 *   <li>{@code HibernateEntityFixtureTest} erzeugt mit Hibernate aus Entities eine H2-File-DB
 *       und legt sie unter {@link #fixtureFile()} in den Test-Resources ab.</li>
 *   <li>{@code HibernateFixtureMigrationTest} migriert genau diese Datei per JDBC nach MariaDB --
 *       ohne die Entity-Klassen zu kennen.</li>
 * </ol>
 *
 * Diese Klasse enthaelt daher bewusst <em>keine</em> JPA-/Hibernate-Importe: sie beschreibt nur
 * die Tabellennamen, Zeilenzahlen und einzelne Werte, die auf der Zielseite wieder auftauchen
 * muessen. Aendert sich das Entity-Modell, aendert sich hier der Vertrag -- und beide Testreihen
 * schlagen an derselben Stelle fehl.
 */
public final class HibernateFixtureContract {

    /** Basisname der H2-Datenbank (H2 haengt ".mv.db" an). */
    public static final String DB_BASE_NAME = "hibernate-shop";

    /** Ablageort der Fixture, relativ zum Projektverzeichnis. */
    public static final String FIXTURE_RELATIVE_PATH = "src/test/resources/fixtures/" + DB_BASE_NAME + ".mv.db";

    /** Alle Tabellen, die Hibernate aus dem Entity-Modell anlegt (H2 meldet Namen in Grossschrift). */
    public static final List<String> TABLES = List.of(
            "CAR", "DEPARTMENT", "EMPLOYEE", "EMPLOYEE_SKILL", "GROUP",
            "PROJECT", "PROJECT_MEMBER", "TIME_ENTRY", "TRUCK", "VEHICLE");

    public static final Map<String, Long> ROW_COUNTS = rowCounts();

    // ---- DEPARTMENT ----
    public static final String DEPT_ROOT_NAME = "Zentrale München";
    public static final String DEPT_CHILD_NAME = "Entwicklung & Betrieb";
    public static final BigDecimal DEPT_ROOT_BUDGET = new BigDecimal("1250000.50");

    // ---- EMPLOYEE 1: alle Spalten befuellt ----
    public static final String EMP1_FIRST_NAME = "Amélie";
    public static final String EMP1_LAST_NAME = "Dupont-Şahin";
    /** Spalte heisst RANK -- in MariaDB ab 10.2 ein reserviertes Wort. */
    public static final String EMP1_RANK = "Sénior";
    public static final String EMP1_EXTERNAL_ID = "8f14e45f-ceea-467a-9b0a-1c2d3e4f5a6b";
    public static final LocalDate EMP1_HIRE_DATE = LocalDate.of(2019, 4, 1);
    public static final LocalTime EMP1_SHIFT_START = LocalTime.of(7, 30);
    public static final BigDecimal EMP1_SALARY = new BigDecimal("6543.21");
    public static final double EMP1_BONUS_FACTOR = 1.075d;
    /** Liegt in H2 in einer FLOAT-Spalte -- die ist dort 8 Byte breit. */
    public static final float EMP1_SCORE = 4.25f;
    /** Liegt in H2 in einer REAL-Spalte, also echt 4 Byte. */
    public static final float EMP1_ACCURACY = 0.75f;
    public static final byte EMP1_LEVEL = 3;
    public static final short EMP1_SENIORITY = 12;
    public static final int EMP1_VACATION_DAYS = 30;
    public static final long EMP1_BADGE_NUMBER = 900000000123L;
    public static final char EMP1_GENDER = 'W';
    public static final long EMP1_WEEKLY_HOURS_NANOS = 40L * 3600 * 1_000_000_000L;
    /** Groesser als 65535 Byte: in einer MariaDB-TEXT-Spalte wuerde das abgeschnitten. */
    public static final String EMP1_NOTES =
            ("Notiz mit Umlauten äöüß, Anführungszeichen \"quote\" und Backslash \\. ").repeat(1000);
    public static final byte[] EMP1_PHOTO = bytes(4096);
    public static final byte[] EMP1_FINGERPRINT = bytes(64);
    public static final String EMP1_CITY = "München";
    public static final String EMP1_COUNTRY_CODE = "DE";
    public static final List<String> EMP1_SKILLS = List.of("Java", "SQL", "Migration");

    // ---- EMPLOYEE 2: alle nullbaren Spalten sind NULL ----
    public static final String EMP2_FIRST_NAME = "Nils";
    public static final String EMP2_LAST_NAME = "Nullwert";

    // ---- EMPLOYEE 3 ----
    public static final String EMP3_FIRST_NAME = "Kenji";
    public static final String EMP3_LAST_NAME = "渡辺";

    // ---- PROJECT ----
    public static final String PROJECT1_CODE = "PRJ-001";
    public static final String PROJECT1_TITLE = "Datenmigration";
    public static final String PROJECT2_CODE = "PRJ-002";
    public static final String PROJECT2_TITLE = "Archiv – Rückbau";

    // ---- TIME_ENTRY (zusammengesetzter Primaerschluessel aus drei Spalten) ----
    public static final LocalDate TIME_ENTRY1_DAY = LocalDate.of(2024, 11, 4);
    public static final BigDecimal TIME_ENTRY1_HOURS = new BigDecimal("7.50");

    // ---- GROUP (reservierter Tabellenname, Spalten KEY/VALUE ebenfalls reserviert) ----
    public static final String GROUP1_KEY = "default.locale";
    public static final String GROUP1_VALUE = "de-DE";
    public static final String GROUP2_KEY = "feature.flag";

    // ---- VEHICLE/CAR/TRUCK (JOINED-Vererbung: Kind-PK ist gleichzeitig Fremdschluessel) ----
    public static final String CAR_PLATE = "M-AB 123";
    public static final int CAR_SEATS = 5;
    public static final String TRUCK_PLATE = "M-XY 999";
    public static final BigDecimal TRUCK_PAYLOAD_KG = new BigDecimal("7500.00");

    private HibernateFixtureContract() {
    }

    private static Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("DEPARTMENT", 2L);
        counts.put("EMPLOYEE", 3L);
        counts.put("EMPLOYEE_SKILL", 4L);
        counts.put("PROJECT", 2L);
        counts.put("PROJECT_MEMBER", 3L);
        counts.put("TIME_ENTRY", 3L);
        counts.put("GROUP", 2L);
        counts.put("VEHICLE", 2L);
        counts.put("CAR", 1L);
        counts.put("TRUCK", 1L);
        return Map.copyOf(counts);
    }

    /** Deterministisches Byte-Muster -- bewusst mit 0x00 und 0xFF darin. */
    public static byte[] bytes(int length) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (i % 256);
        }
        return b;
    }

    /** Die von Testreihe 1 erzeugte und eingecheckte H2-Datenbank. */
    public static Path fixtureFile() {
        return projectDir().resolve(FIXTURE_RELATIVE_PATH);
    }

    /** JDBC-URL fuer eine H2-Datei (ohne die Endung .mv.db, die H2 selbst anhaengt). */
    public static String h2Url(Path mvDbFile) {
        String path = mvDbFile.toString();
        if (path.endsWith(".mv.db")) {
            path = path.substring(0, path.length() - ".mv.db".length());
        }
        return "jdbc:h2:file:" + path.replace('\\', '/');
    }

    /** Projektverzeichnis, damit die Tests unabhaengig vom Arbeitsverzeichnis von Maven bzw.
     *  der IDE dieselbe Datei treffen. */
    public static Path projectDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("pom.xml"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(
                    "Projektverzeichnis nicht gefunden (keine pom.xml oberhalb von "
                            + System.getProperty("user.dir") + ")");
        }
        return dir;
    }
}
