package com.pixeldweller.migrape.hibernate;

import com.pixeldweller.migrape.testsupport.HibernateFixtureContract;
import com.pixeldweller.migrape.testsupport.TempFiles;
import com.pixeldweller.migrape.testsupport.hibernate.Department;
import com.pixeldweller.migrape.testsupport.hibernate.Employee;
import com.pixeldweller.migrape.testsupport.hibernate.Enums;
import com.pixeldweller.migrape.testsupport.hibernate.HibernateFixture;
import com.pixeldweller.migrape.testsupport.hibernate.Project;
import com.pixeldweller.migrape.testsupport.hibernate.TimeEntry;
import org.hibernate.PropertyValueException;
import org.hibernate.SessionFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

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
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testreihe 1 von 2: <em>mit</em> Hibernate.
 *
 * Hibernate erzeugt aus dem Entity-Modell (siehe testsupport/hibernate) eine H2-File-Datenbank
 * mit Schema und Daten. Diese Datei wird am Ende nach
 * {@code src/test/resources/fixtures/hibernate-shop.mv.db} kopiert und eingecheckt -- sie ist
 * die Eingabe fuer Testreihe 2 ({@code HibernateFixtureMigrationTest}), die das Entity-Modell
 * nicht kennt und nur noch per JDBC arbeitet.
 *
 * <b>Wichtig:</b> Ein Lauf dieser Klasse ueberschreibt die eingecheckte Fixture-Datei. Nach
 * Aenderungen am Entity-Modell muss die neue Datei mit committet werden.
 *
 * Geprueft wird hier alles, was nur mit Hibernate-Wissen pruefbar ist: welches H2-Schema aus
 * den Annotationen entsteht, dass die Daten durch Hibernate wieder unveraendert herauskommen,
 * und dass die deklarierten Constraints in der Datenbank auch wirklich greifen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HibernateEntityFixtureTest {

    private Path workDir;

    /** Von Hibernate erzeugte H2-Datei (noch nicht die eingecheckte Kopie). */
    private Path generatedDb;
    private String h2Url;

    @BeforeAll
    void generateDatabaseWithHibernate() {
        // Kein @TempDir: JUnit befuellt ein Instanzfeld erst nach @BeforeAll.
        workDir = TempFiles.createDirectory("migrape-hibernate-fixture");
        generatedDb = HibernateFixture.createFileDatabase(workDir);
        h2Url = HibernateFixtureContract.h2Url(generatedDb);
    }

    @AfterAll
    void removeWorkDir() {
        TempFiles.deleteRecursively(workDir);
    }

    // ---- Was Hibernate an Schema erzeugt ----

    @Test
    @DisplayName("Hibernate legt genau die erwarteten Tabellen mit den erwarteten Zeilen an")
    void createsExpectedTablesAndRows() throws Exception {
        try (Connection h2 = openH2()) {
            assertEquals(HibernateFixtureContract.TABLES, tableNames(h2));

            for (var entry : HibernateFixtureContract.ROW_COUNTS.entrySet()) {
                assertEquals(entry.getValue().longValue(), countRows(h2, entry.getKey()),
                        "Zeilenanzahl in " + entry.getKey());
            }
        }
    }

    @Test
    @DisplayName("Die Java-Typen der Entities landen als die erwarteten H2-Spaltentypen")
    void mapsJavaTypesToExpectedH2Types() throws Exception {
        try (Connection h2 = openH2()) {
            // UUID meldet H2 als BINARY mit TYPE_NAME "UUID" -- genau der Fall, den der
            // TypeMapper vor dem jdbcType-Switch abfangen muss.
            assertColumn(h2, "EMPLOYEE", "EXTERNAL_ID", Types.BINARY, "UUID");
            assertColumnType(h2, "EMPLOYEE", "NOTES", Types.CLOB);
            assertColumnType(h2, "EMPLOYEE", "PHOTO", Types.BLOB);
            assertColumnType(h2, "EMPLOYEE", "FINGERPRINT", Types.VARBINARY);
            assertEquals(64, columnSize(h2, "EMPLOYEE", "FINGERPRINT"));
            assertColumnType(h2, "EMPLOYEE", "COUNTRY_CODE", Types.CHAR);
            assertEquals(2, columnSize(h2, "EMPLOYEE", "COUNTRY_CODE"));
            assertColumnType(h2, "EMPLOYEE", "GENDER", Types.CHAR);
            assertColumnType(h2, "EMPLOYEE", "CONTRACT_SIGNED_AT", Types.TIMESTAMP_WITH_TIMEZONE);
            assertColumnType(h2, "EMPLOYEE", "LAST_LOGIN", Types.TIMESTAMP);
            assertColumnType(h2, "EMPLOYEE", "HIRE_DATE", Types.DATE);
            assertColumnType(h2, "EMPLOYEE", "SHIFT_START", Types.TIME);
            assertColumnType(h2, "EMPLOYEE", "ACTIVE", Types.BOOLEAN);
            assertColumnType(h2, "EMPLOYEE", "BADGE_NUMBER", Types.BIGINT);
            assertColumnType(h2, "EMPLOYEE", "VACATION_DAYS", Types.INTEGER);
            assertColumnType(h2, "EMPLOYEE", "SENIORITY", Types.SMALLINT);
            assertColumnType(h2, "EMPLOYEE", "LEVEL", Types.TINYINT);
            // Fuer ein Java-Float schreibt Hibernate "float(24)". H2 legt das als 4-Byte-REAL an
            // (TYPE_NAME "REAL"), meldet ueber die Metadaten aber DATA_TYPE=FLOAT -- die beiden
            // Angaben widersprechen sich also. Der TypeMapper entscheidet nach DATA_TYPE und
            // macht daraus DOUBLE: acht statt vier Byte, dafuer sicher ohne Genauigkeitsverlust.
            assertColumn(h2, "EMPLOYEE", "SCORE", Types.FLOAT, "REAL");
            // Nur ein ausdrueckliches REAL meldet H2 auch als Types.REAL -- daraus wird
            // MariaDB FLOAT (siehe H2MetadataTypeMappingTest, Spalte C_REAL).
            assertColumn(h2, "EMPLOYEE", "ACCURACY", Types.REAL, "REAL");
            // Auch fuer Java-Double schreibt Hibernate ein FLOAT (mit voller Praezision), nicht
            // "double precision" -- H2 meldet deshalb erneut DATA_TYPE=FLOAT. Der TypeMapper
            // bildet FLOAT wie DOUBLE ab, das Ziel bekommt also die richtige Breite.
            assertColumnType(h2, "EMPLOYEE", "BONUS_FACTOR", Types.FLOAT);
            assertDecimalColumn(h2, "EMPLOYEE", "SALARY", 12, 2);
            // Duration speichert Hibernate als Nanosekunden in einer NUMERIC-Spalte.
            assertDecimalColumn(h2, "EMPLOYEE", "WEEKLY_HOURS", 21, 0);
            // Enum als Text: Hibernate 6 nimmt dafuer den nativen ENUM-Typ von H2, kein VARCHAR.
            // H2 meldet ENUM als Types.OTHER -- genau der Fall, den der TypeMapper vor dem
            // jdbcType-Switch abfangen muss, sonst landet die Spalte im LONGTEXT-Fallback.
            ColumnMeta employmentType = columnMeta(h2, "EMPLOYEE", "EMPLOYMENT_TYPE");
            assertEquals(Types.OTHER, employmentType.jdbcType(), "EMPLOYMENT_TYPE: JDBC-Typ");
            assertTrue(employmentType.typeName().startsWith("ENUM("),
                    "EMPLOYMENT_TYPE sollte ein ENUM sein, war: " + employmentType.typeName());
            for (Enums.EmploymentType label : Enums.EmploymentType.values()) {
                assertTrue(employmentType.typeName().contains(label.name()),
                        "ENUM-Label " + label + " fehlt in " + employmentType.typeName());
            }
            // Enum als Ordinalwert wird dagegen eine kleine Zahl.
            assertColumnType(h2, "EMPLOYEE", "SECURITY_LEVEL", Types.TINYINT);
            assertColumnType(h2, "EMPLOYEE", "RANK", Types.VARCHAR);
        }
    }

    @Test
    @DisplayName("Reservierte Woerter als Tabellen- und Spaltennamen kommen unverfaelscht in H2 an")
    void keepsReservedWordIdentifiers() throws Exception {
        try (Connection h2 = openH2()) {
            assertTrue(tableNames(h2).contains("GROUP"), "Tabelle GROUP fehlt");
            Set<String> columns = columnNames(h2, "GROUP");
            assertTrue(columns.containsAll(List.of("ID", "KEY", "VALUE", "DEPARTMENT_ID")),
                    "Spalten der Tabelle GROUP: " + columns);
            assertTrue(columnNames(h2, "EMPLOYEE").contains("RANK"), "Spalte RANK fehlt");

            // Nur mit Quoting lesbar -- ohne waere das ein Syntaxfehler.
            try (Statement stmt = h2.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT \"VALUE\" FROM \"GROUP\" WHERE \"KEY\" = '"
                                 + HibernateFixtureContract.GROUP1_KEY + "'")) {
                assertTrue(rs.next());
                assertEquals(HibernateFixtureContract.GROUP1_VALUE, rs.getString(1));
            }
        }
    }

    @Test
    @DisplayName("Schluessel, Fremdschluessel, Unique-Constraints, Indizes und CHECK sind im Schema")
    void createsExpectedConstraintsAndIndexes() throws Exception {
        try (Connection h2 = openH2()) {
            // IDENTITY vs. SEQUENCE: nur EMPLOYEE/DEPARTMENT/VEHICLE haben eine Identity-Spalte.
            assertTrue(isAutoIncrement(h2, "EMPLOYEE", "ID"));
            assertFalse(isAutoIncrement(h2, "GROUP", "ID"),
                    "GROUP.ID kommt aus einer Sequenz, nicht aus einer Identity-Spalte");

            assertEquals(Set.of("EMPLOYEE_ID", "PROJECT_CODE", "WORK_DAY"),
                    primaryKeyColumns(h2, "TIME_ENTRY"), "zusammengesetzter Primaerschluessel");
            assertEquals(Set.of("VEHICLE_ID"), primaryKeyColumns(h2, "CAR"),
                    "JOINED-Vererbung: CAR.VEHICLE_ID ist Primaer- und Fremdschluessel");
            // Bag-Mapping (List ohne Ordnungsspalte): Hibernate darf keinen Primaerschluessel
            // vergeben, weil Duplikate erlaubt sind. Genau der Fall, den der Migrator koennen muss.
            assertTrue(primaryKeyColumns(h2, "EMPLOYEE_SKILL").isEmpty(),
                    "Element-Collection als Bag darf keinen Primaerschluessel haben, war: "
                            + primaryKeyColumns(h2, "EMPLOYEE_SKILL"));

            assertEquals(new TreeSet<>(List.of(
                            "FK_CAR_VEHICLE", "FK_DEPARTMENT_PARENT", "FK_EMPLOYEE_DEPARTMENT",
                            "FK_EMPLOYEE_MANAGER", "FK_GROUP_DEPARTMENT", "FK_MEMBER_EMPLOYEE",
                            "FK_MEMBER_PROJECT", "FK_PROJECT_DEPARTMENT", "FK_SKILL_EMPLOYEE",
                            "FK_TIME_ENTRY_EMPLOYEE", "FK_TIME_ENTRY_PROJECT", "FK_TRUCK_VEHICLE",
                            "FK_VEHICLE_DEPARTMENT")),
                    constraintNames(h2, "FOREIGN KEY"));

            assertTrue(constraintNames(h2, "UNIQUE")
                            .containsAll(List.of("UQ_DEPARTMENT_NAME", "UQ_EMPLOYEE_EXTERNAL_ID")),
                    "Unique-Constraints: " + constraintNames(h2, "UNIQUE"));

            assertTrue(indexNames(h2).containsAll(List.of(
                            "IDX_DEPARTMENT_REGION", "IDX_EMPLOYEE_LAST_NAME", "IDX_EMPLOYEE_DEPT_ACTIVE")),
                    "Indizes: " + indexNames(h2));

            assertTrue(constraintNames(h2, "CHECK").contains("CK_TIME_ENTRY_HOURS"),
                    "CHECK-Constraint aus der Annotation fehlt");
        }
    }

    // ---- Daten kommen durch Hibernate unveraendert wieder heraus ----

    @Test
    @DisplayName("Der Fixture-Datensatz laedt sich mit Hibernate unveraendert wieder")
    void dataRoundTripsThroughHibernate() {
        try (SessionFactory sessionFactory = HibernateFixture.openSessionFactory(h2Url, false)) {
            // Die Assertions laufen bewusst innerhalb der Session: skills und projects sind
            // Lazy-Collections und waeren ausserhalb nicht mehr ladbar.
            sessionFactory.inTransaction(session -> {
                Employee emp1 = session.createQuery("from Employee where lastName = :name", Employee.class)
                        .setParameter("name", HibernateFixtureContract.EMP1_LAST_NAME)
                        .getSingleResult();

                assertEquals(HibernateFixtureContract.EMP1_FIRST_NAME, emp1.firstName);
                assertEquals(HibernateFixtureContract.EMP1_RANK, emp1.rank);
                assertEquals(UUID.fromString(HibernateFixtureContract.EMP1_EXTERNAL_ID), emp1.externalId);
                assertEquals(HibernateFixtureContract.EMP1_HIRE_DATE, emp1.hireDate);
                assertEquals(0, HibernateFixtureContract.EMP1_SALARY.compareTo(emp1.salary));
                assertEquals(HibernateFixtureContract.EMP1_BONUS_FACTOR, emp1.bonusFactor, 1e-12);
                assertEquals(HibernateFixtureContract.EMP1_SCORE, emp1.score, 1e-6f);
                assertEquals(HibernateFixtureContract.EMP1_ACCURACY, emp1.accuracy, 1e-6f);
                assertEquals(HibernateFixtureContract.EMP1_LEVEL, emp1.level.byteValue());
                assertEquals(HibernateFixtureContract.EMP1_GENDER, emp1.gender.charValue());
                assertEquals(Enums.SecurityLevel.SECRET, emp1.securityLevel);
                assertEquals(Enums.EmploymentType.FULL_TIME, emp1.employmentType);
                assertEquals(HibernateFixtureContract.EMP1_NOTES, emp1.notes);
                assertArrayEquals(HibernateFixtureContract.EMP1_PHOTO, emp1.photo);
                assertArrayEquals(HibernateFixtureContract.EMP1_FINGERPRINT, emp1.fingerprint);
                assertEquals(HibernateFixtureContract.EMP1_CITY, emp1.address.city);
                assertEquals(HibernateFixtureContract.EMP1_COUNTRY_CODE, emp1.address.countryCode);
                assertEquals(HibernateFixtureContract.DEPT_CHILD_NAME, emp1.department.name);
                assertEquals(HibernateFixtureContract.DEPT_ROOT_NAME, emp1.department.parent.name);
                // Bag: die Reihenfolge beim Laden ist nicht zugesichert, nur der Inhalt.
                assertEquals(Set.copyOf(HibernateFixtureContract.EMP1_SKILLS), Set.copyOf(emp1.skills));
                assertEquals(2, emp1.projects.size());

                Employee emp2 = session.createQuery("from Employee where lastName = :name", Employee.class)
                        .setParameter("name", HibernateFixtureContract.EMP2_LAST_NAME)
                        .getSingleResult();
                assertNull(emp2.rank);
                assertNull(emp2.externalId);
                assertNull(emp2.salary);
                assertNull(emp2.notes);
                assertNull(emp2.photo);
                assertNull(emp2.securityLevel);
                assertNull(emp2.weeklyHours);
                assertFalse(emp2.active);
                assertNotNull(emp2.manager, "MANAGER_ID zeigt auf Mitarbeiter 1 (Selbstreferenz)");
                assertTrue(emp2.skills.isEmpty());
            });
        }
    }

    // ---- Greifen die deklarierten Constraints in der Datenbank wirklich? ----

    @Test
    @DisplayName("Unique-Constraint aus der Annotation wird von H2 durchgesetzt")
    void uniqueConstraintIsEnforced() {
        try (SessionFactory sessionFactory = freshInMemoryFactory()) {
            assertThrows(ConstraintViolationException.class, () ->
                    sessionFactory.inTransaction(session -> {
                        session.persist(new Department("Doppelt", Enums.Region.EMEA, null, true, null));
                        session.persist(new Department("Doppelt", Enums.Region.EMEA, null, true, null));
                        session.flush();
                    }));
        }
    }

    @Test
    @DisplayName("NOT NULL aus nullable=false wird durchgesetzt")
    void notNullConstraintIsEnforced() {
        try (SessionFactory sessionFactory = freshInMemoryFactory()) {
            assertThrows(PropertyValueException.class, () ->
                    sessionFactory.inTransaction(session -> {
                        session.persist(new Department(null, Enums.Region.EMEA, null, true, null));
                        session.flush();
                    }));
        }
    }

    @Test
    @DisplayName("Fremdschluessel verhindert das Loeschen einer noch referenzierten Abteilung")
    void foreignKeyConstraintIsEnforced() {
        try (SessionFactory sessionFactory = freshInMemoryFactory()) {
            Long departmentId = sessionFactory.fromTransaction(session -> {
                Department dept = new Department("Mit Mitarbeiter", Enums.Region.EMEA, null, true, null);
                session.persist(dept);
                session.persist(new Employee("Anna", "Angestellt", dept,
                        Enums.EmploymentType.FULL_TIME, true));
                session.flush();
                return dept.id;
            });

            assertThrows(ConstraintViolationException.class, () ->
                    sessionFactory.inTransaction(session -> {
                        session.remove(session.getReference(Department.class, departmentId));
                        session.flush();
                    }));
        }
    }

    @Test
    @DisplayName("CHECK-Constraint aus der Annotation wird durchgesetzt")
    void checkConstraintIsEnforced() {
        try (SessionFactory sessionFactory = freshInMemoryFactory()) {
            assertThrows(ConstraintViolationException.class, () ->
                    sessionFactory.inTransaction(session -> {
                        Department dept = new Department("Zeiten", Enums.Region.EMEA, null, true, null);
                        session.persist(dept);
                        Employee emp = new Employee("Tim", "Tracker", dept,
                                Enums.EmploymentType.FULL_TIME, true);
                        session.persist(emp);
                        Project project = new Project("PRJ-CHK", "Pruefung",
                                Enums.ProjectStatus.ACTIVE, null, null, dept);
                        session.persist(project);
                        session.flush();

                        // HOURS > 0 ist als CHECK deklariert.
                        session.persist(new TimeEntry(emp, project, LocalDate.of(2024, 1, 1),
                                BigDecimal.ZERO, "ungueltig", false));
                        session.flush();
                    }));
        }
    }

    // ---- Ergebnis in die Test-Resources uebernehmen ----

    @Test
    @DisplayName("Die erzeugte H2-Datei landet in src/test/resources (Eingabe fuer Testreihe 2)")
    void publishesFixtureIntoTestResources() throws Exception {
        Path target = HibernateFixtureContract.fixtureFile();
        Files.createDirectories(target.getParent());
        Files.copy(generatedDb, target, StandardCopyOption.REPLACE_EXISTING);

        assertTrue(Files.isRegularFile(target), "Fixture nicht geschrieben: " + target);
        assertTrue(Files.size(target) > 0, "Fixture ist leer: " + target);

        // Die Kopie muss fuer sich allein lesbar sein -- Testreihe 2 hat nichts anderes.
        try (Connection h2 = DriverManager.getConnection(
                HibernateFixtureContract.h2Url(target), "sa", "")) {
            assertEquals(HibernateFixtureContract.TABLES, tableNames(h2));
        }
    }

    // ---- Helfer ----

    private Connection openH2() throws SQLException {
        return DriverManager.getConnection(h2Url, "sa", "");
    }

    private SessionFactory freshInMemoryFactory() {
        // Eigene In-Memory-Datenbank je Test: die Fixture-Datei bleibt unangetastet.
        return HibernateFixture.openSessionFactory(
                "jdbc:h2:mem:hibfix-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", true);
    }

    private static List<String> tableNames(Connection h2) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = h2.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    private static Set<String> columnNames(Connection h2, String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (ResultSet rs = h2.getMetaData().getColumns(null, "PUBLIC", table, "%")) {
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME"));
            }
        }
        return names;
    }

    private static long countRows(Connection h2, String table) throws SQLException {
        try (Statement stmt = h2.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void assertColumnType(Connection h2, String table, String column, int expectedJdbcType)
            throws SQLException {
        assertEquals(expectedJdbcType, columnMeta(h2, table, column).jdbcType,
                table + "." + column + ": JDBC-Typ");
    }

    private static void assertColumn(Connection h2, String table, String column,
                                     int expectedJdbcType, String expectedTypeName) throws SQLException {
        ColumnMeta meta = columnMeta(h2, table, column);
        assertEquals(expectedJdbcType, meta.jdbcType, table + "." + column + ": JDBC-Typ");
        assertEquals(expectedTypeName, meta.typeName, table + "." + column + ": TYPE_NAME");
    }

    /** H2 meldet DECIMAL/NUMERIC je nach Version unter beiden Codes -- beide sind hier korrekt. */
    private static void assertDecimalColumn(Connection h2, String table, String column,
                                            int expectedPrecision, int expectedScale) throws SQLException {
        ColumnMeta meta = columnMeta(h2, table, column);
        assertTrue(meta.jdbcType == Types.DECIMAL || meta.jdbcType == Types.NUMERIC,
                table + "." + column + ": erwartet DECIMAL/NUMERIC, war JDBC-Typ " + meta.jdbcType);
        assertEquals(expectedPrecision, meta.size, table + "." + column + ": Praezision");
        assertEquals(expectedScale, meta.digits, table + "." + column + ": Skala");
    }

    private static int columnSize(Connection h2, String table, String column) throws SQLException {
        return columnMeta(h2, table, column).size;
    }

    private static boolean isAutoIncrement(Connection h2, String table, String column) throws SQLException {
        return columnMeta(h2, table, column).autoIncrement;
    }

    private static ColumnMeta columnMeta(Connection h2, String table, String column) throws SQLException {
        try (ResultSet rs = h2.getMetaData().getColumns(null, "PUBLIC", table, column)) {
            if (!rs.next()) {
                throw new AssertionError("Spalte " + table + "." + column + " existiert nicht");
            }
            return new ColumnMeta(rs.getInt("DATA_TYPE"), rs.getString("TYPE_NAME"),
                    rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"),
                    "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT")));
        }
    }

    private static Set<String> primaryKeyColumns(Connection h2, String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (ResultSet rs = h2.getMetaData().getPrimaryKeys(null, "PUBLIC", table)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private static Set<String> constraintNames(Connection h2, String constraintType) throws SQLException {
        Set<String> names = new TreeSet<>();
        String sql = "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_SCHEMA = 'PUBLIC' AND CONSTRAINT_TYPE = ?";
        try (PreparedStatement ps = h2.prepareStatement(sql)) {
            ps.setString(1, constraintType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private static Set<String> indexNames(Connection h2) throws SQLException {
        Set<String> names = new TreeSet<>();
        for (String table : HibernateFixtureContract.TABLES) {
            try (ResultSet rs = h2.getMetaData().getIndexInfo(null, "PUBLIC", table, false, false)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    private record ColumnMeta(int jdbcType, String typeName, int size, int digits, boolean autoIncrement) {
    }
}
