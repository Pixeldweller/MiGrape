package com.pixeldweller.migrape.hibernate;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.pixeldweller.migrape.MigrationConfig;
import com.pixeldweller.migrape.migration.MigrationService;
import com.pixeldweller.migrape.migration.MigrationState;
import com.pixeldweller.migrape.migration.VerificationResult;
import com.pixeldweller.migrape.testsupport.HibernateFixtureContract;
import com.pixeldweller.migrape.testsupport.TempFiles;
import com.pixeldweller.migrape.testsupport.TestConfigs;
import com.pixeldweller.migrape.testsupport.hibernate.Announcement;
import com.pixeldweller.migrape.testsupport.hibernate.Car;
import com.pixeldweller.migrape.testsupport.hibernate.Department;
import com.pixeldweller.migrape.testsupport.hibernate.Employee;
import com.pixeldweller.migrape.testsupport.hibernate.Enums;
import com.pixeldweller.migrape.testsupport.hibernate.HibernateFixture;
import com.pixeldweller.migrape.testsupport.hibernate.Project;
import com.pixeldweller.migrape.testsupport.hibernate.TimeEntry;
import com.pixeldweller.migrape.testsupport.hibernate.TimeEntryId;
import com.pixeldweller.migrape.testsupport.hibernate.Truck;
import com.pixeldweller.migrape.testsupport.hibernate.Vehicle;
import com.pixeldweller.migrape.testsupport.hibernate.WorkGroup;
import org.hibernate.Session;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Testreihe 3 von 3: die migrierte MariaDB wieder mit Hibernate.
 *
 * Reihe 1 hat die H2-Datenbank aus Entities erzeugt, Reihe 2 hat sie per JDBC nach MariaDB
 * migriert und Zelle fuer Zelle verglichen. Hier kommt die Frage, die den Kunden wirklich
 * interessiert: laesst sich das migrierte Schema mit <em>demselben</em> Entity-Modell
 * weiterbetreiben, und stehen dieselben Werte drin wie beim Einfuegen?
 *
 * Hibernate bekommt dafuer {@code hbm2ddl.auto=none} -- das Schema stammt ausschliesslich vom
 * Migrator. Was hier laedt, laedt auch in der Anwendung.
 *
 * Der eine Punkt, an dem die Migration die Semantik veraendert, ist eigens festgehalten:
 * {@link #timeZoneOffsetIsLostAsDocumented()}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigratedSchemaWithHibernateTest {

    private static final String MARIA_DB_NAME = "migrape_hibernate_roundtrip";

    private Path workDir;
    private DB embeddedMariaDb;
    private SessionFactory sessionFactory;

    @BeforeAll
    void migrateFixtureAndOpenHibernateOnMariaDb() throws Exception {
        workDir = TempFiles.createDirectory("migrape-hibernate-roundtrip");

        Path fixture = HibernateFixtureContract.fixtureFile();
        if (!Files.isRegularFile(fixture)) {
            fail("Fixture-Datenbank fehlt: " + fixture + System.lineSeparator()
                    + "Sie wird von HibernateEntityFixtureTest erzeugt -- diesen Test einmal "
                    + "ausfuehren und die Datei einchecken.");
        }
        Path workingCopy = workDir.resolve(HibernateFixtureContract.DB_BASE_NAME + ".mv.db");
        Files.copy(fixture, workingCopy, StandardCopyOption.REPLACE_EXISTING);

        DBConfigurationBuilder builder = DBConfigurationBuilder.newBuilder();
        builder.setPort(0);
        builder.setDataDir(new File(Files.createTempDirectory("migrape-roundtrip-mariadb").toString()));
        embeddedMariaDb = DB.newEmbeddedDB(builder.build());
        embeddedMariaDb.start();
        embeddedMariaDb.createDB(MARIA_DB_NAME);
        String mariaUrl = builder.getURL(MARIA_DB_NAME);

        MigrationConfig config = TestConfigs.forMigration(
                HibernateFixtureContract.h2Url(workingCopy), mariaUrl, 100);
        MigrationService service = new MigrationService(config);
        service.migrateSchema(true);
        service.migrateData(new MigrationState(workDir.resolve("roundtrip.state")), false);
        for (VerificationResult result : service.verify()) {
            assertTrue(result.ok(), "Migration bereits fehlgeschlagen bei " + result.table());
        }

        // Ab hier kein JDBC mehr: nur noch das Entity-Modell aus Testreihe 1.
        sessionFactory = HibernateFixture.openMariaDbSessionFactory(mariaUrl, "root", "");
    }

    @AfterAll
    void closeEverything() throws Exception {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
        if (embeddedMariaDb != null) {
            embeddedMariaDb.stop();
        }
        TempFiles.deleteRecursively(workDir);
    }

    // ---- Skalare Werte ----

    @Test
    @DisplayName("Die voll befuellte Zeile kommt ueber alle Typen unveraendert aus MariaDB zurueck")
    void fullyPopulatedEmployeeLoadsUnchanged() {
        sessionFactory.inTransaction(session -> {
            Employee emp = employeeByLastName(session, HibernateFixtureContract.EMP1_LAST_NAME);

            assertEquals(HibernateFixtureContract.EMP1_FIRST_NAME, emp.firstName);
            assertEquals(HibernateFixtureContract.EMP1_RANK, emp.rank, "Spalte mit reserviertem Namen");
            assertEquals(UUID.fromString(HibernateFixtureContract.EMP1_EXTERNAL_ID), emp.externalId);
            assertEquals(HibernateFixtureContract.EMP1_HIRE_DATE, emp.hireDate);
            assertEquals(LocalDateTime.of(2024, 11, 5, 8, 15, 30, 123_456_000), emp.lastLogin,
                    "Mikrosekunden muessen die Migration ueberlebt haben");
            assertEquals(HibernateFixtureContract.EMP1_SHIFT_START, emp.shiftStart);
            assertEquals(HibernateFixtureContract.EMP1_WEEKLY_HOURS_NANOS, emp.weeklyHours.toNanos());
            assertEquals(0, HibernateFixtureContract.EMP1_SALARY.compareTo(emp.salary));
            assertEquals(HibernateFixtureContract.EMP1_BONUS_FACTOR, emp.bonusFactor, 1e-12);
            assertEquals(HibernateFixtureContract.EMP1_SCORE, emp.score, 1e-6f);
            assertEquals(HibernateFixtureContract.EMP1_ACCURACY, emp.accuracy, 1e-6f);
            assertEquals(HibernateFixtureContract.EMP1_LEVEL, emp.level.byteValue());
            assertEquals(HibernateFixtureContract.EMP1_SENIORITY, emp.seniority.shortValue());
            assertEquals(HibernateFixtureContract.EMP1_VACATION_DAYS, emp.vacationDays.intValue());
            assertEquals(HibernateFixtureContract.EMP1_BADGE_NUMBER, emp.badgeNumber.longValue());
            assertEquals(HibernateFixtureContract.EMP1_GENDER, emp.gender.charValue());
            assertTrue(emp.active);
            assertEquals(Enums.EmploymentType.FULL_TIME, emp.employmentType,
                    "Enum als Text -- in MariaDB ein nativer ENUM-Typ");
            assertEquals(Enums.SecurityLevel.SECRET, emp.securityLevel, "Enum als Ordinalwert");
            assertEquals(HibernateFixtureContract.EMP1_NOTES, emp.notes, "CLOB > 65535 Byte");
            assertArrayEquals(HibernateFixtureContract.EMP1_PHOTO, emp.photo);
            assertArrayEquals(HibernateFixtureContract.EMP1_FINGERPRINT, emp.fingerprint);
            assertEquals("Hauptstraße 1", emp.address.street, "eingebetteter Wertetyp");
            assertEquals(HibernateFixtureContract.EMP1_CITY, emp.address.city);
            assertEquals(HibernateFixtureContract.EMP1_COUNTRY_CODE, emp.address.countryCode,
                    "CHAR(2) darf keine aufgefuellten Leerzeichen mitbringen");
        });
    }

    @Test
    @DisplayName("Die Zeile ohne Werte kommt ueberall als NULL zurueck, nicht als Standardwert")
    void nullEmployeeLoadsAsNull() {
        sessionFactory.inTransaction(session -> {
            Employee emp = employeeByLastName(session, HibernateFixtureContract.EMP2_LAST_NAME);

            assertNull(emp.rank);
            assertNull(emp.externalId);
            assertNull(emp.hireDate);
            assertNull(emp.lastLogin);
            assertNull(emp.createdAt);
            assertNull(emp.contractSignedAt);
            assertNull(emp.shiftStart);
            assertNull(emp.weeklyHours);
            assertNull(emp.salary);
            assertNull(emp.bonusFactor);
            assertNull(emp.score);
            assertNull(emp.accuracy);
            assertNull(emp.level);
            assertNull(emp.seniority);
            assertNull(emp.vacationDays);
            assertNull(emp.badgeNumber);
            assertNull(emp.gender);
            assertNull(emp.securityLevel);
            assertNull(emp.notes);
            assertNull(emp.photo);
            assertNull(emp.fingerprint);
            assertFalse(emp.active);
            assertEquals(Enums.EmploymentType.CONTRACTOR, emp.employmentType);
            assertTrue(emp.skills.isEmpty());
            assertTrue(emp.projects.isEmpty());
        });
    }

    // ---- Beziehungen ----

    @Test
    @DisplayName("Fremdschluessel, Selbstreferenzen und Collections sind ueber Hibernate navigierbar")
    void associationsAndCollectionsAreIntact() {
        sessionFactory.inTransaction(session -> {
            Employee emp1 = employeeByLastName(session, HibernateFixtureContract.EMP1_LAST_NAME);

            assertEquals(HibernateFixtureContract.DEPT_CHILD_NAME, emp1.department.name);
            assertEquals(HibernateFixtureContract.DEPT_ROOT_NAME, emp1.department.parent.name,
                    "selbstreferenzierender Fremdschluessel der Abteilung");
            assertNull(emp1.department.parent.parent);
            assertEquals(0, HibernateFixtureContract.DEPT_ROOT_BUDGET
                    .compareTo(emp1.department.parent.budget));
            assertNull(emp1.department.budget, "Kind-Abteilung hat bewusst kein Budget");
            assertEquals(Enums.Region.APAC, emp1.department.region);
            assertFalse(emp1.department.active);

            assertEquals(Set.copyOf(HibernateFixtureContract.EMP1_SKILLS), Set.copyOf(emp1.skills),
                    "Element-Collection ohne Primaerschluessel");
            assertEquals(Set.of(HibernateFixtureContract.PROJECT1_CODE,
                            HibernateFixtureContract.PROJECT2_CODE),
                    emp1.projects.stream().map(p -> p.code).collect(Collectors.toSet()),
                    "Many-to-Many ueber die Verknuepfungstabelle");

            Employee emp2 = employeeByLastName(session, HibernateFixtureContract.EMP2_LAST_NAME);
            assertNotNull(emp2.manager, "selbstreferenzierender Fremdschluessel des Mitarbeiters");
            assertEquals(HibernateFixtureContract.EMP1_LAST_NAME, emp2.manager.lastName);

            // Gegenrichtung der Many-to-Many-Beziehung
            Project project1 = session.find(Project.class, HibernateFixtureContract.PROJECT1_CODE);
            assertEquals(HibernateFixtureContract.PROJECT1_TITLE, project1.title);
            assertEquals(Enums.ProjectStatus.ACTIVE, project1.status);
            assertEquals(2, project1.members.size(), "Mitarbeiter 1 und 3 sind zugeordnet");
            assertEquals(HibernateFixtureContract.DEPT_ROOT_NAME, project1.ownerDepartment.name);

            Project project2 = session.find(Project.class, HibernateFixtureContract.PROJECT2_CODE);
            assertNull(project2.budget);
            assertNull(project2.deadline);
            assertNull(project2.ownerDepartment);
        });
    }

    @Test
    @DisplayName("Der zusammengesetzte Schluessel laesst sich weiterhin als Id ansprechen")
    void compositeKeyEntityCanBeLoadedById() {
        sessionFactory.inTransaction(session -> {
            Employee emp1 = employeeByLastName(session, HibernateFixtureContract.EMP1_LAST_NAME);

            TimeEntry entry = session.find(TimeEntry.class, new TimeEntryId(emp1.id,
                    HibernateFixtureContract.PROJECT1_CODE, HibernateFixtureContract.TIME_ENTRY1_DAY));

            assertNotNull(entry, "Zeitbuchung ueber den dreispaltigen Schluessel nicht gefunden");
            assertEquals(0, HibernateFixtureContract.TIME_ENTRY1_HOURS.compareTo(entry.hours));
            assertTrue(entry.billable);
            assertEquals("Analyse & Konzept", entry.description);
            assertEquals(HibernateFixtureContract.EMP1_LAST_NAME, entry.employee.lastName);
            assertEquals(HibernateFixtureContract.PROJECT1_TITLE, entry.project.title);

            assertEquals(3, session.createQuery("from TimeEntry", TimeEntry.class)
                    .getResultList().size());
        });
    }

    @Test
    @DisplayName("JOINED-Vererbung laedt weiterhin polymorph")
    void joinedInheritanceStillResolvesSubclasses() {
        sessionFactory.inTransaction(session -> {
            List<Vehicle> vehicles = session
                    .createQuery("from Vehicle order by plate", Vehicle.class).getResultList();

            assertEquals(2, vehicles.size());
            Car car = assertInstanceOf(Car.class, vehicles.get(0));
            assertEquals(HibernateFixtureContract.CAR_PLATE, car.plate);
            assertEquals(HibernateFixtureContract.CAR_SEATS, car.seats.intValue());
            assertEquals(HibernateFixtureContract.DEPT_ROOT_NAME, car.department.name);

            Truck truck = assertInstanceOf(Truck.class, vehicles.get(1));
            assertEquals(HibernateFixtureContract.TRUCK_PLATE, truck.plate);
            assertEquals(0, HibernateFixtureContract.TRUCK_PAYLOAD_KG.compareTo(truck.payloadKg));
        });
    }

    @Test
    @DisplayName("Die Entity auf Tabelle GROUP mit den Spalten KEY und VALUE laedt")
    void reservedWordEntityLoads() {
        sessionFactory.inTransaction(session -> {
            List<WorkGroup> groups = session.createQuery("from WorkGroup", WorkGroup.class).getResultList();
            assertEquals(2, groups.size());

            WorkGroup withValue = groups.stream()
                    .filter(g -> HibernateFixtureContract.GROUP1_KEY.equals(g.key))
                    .findFirst().orElseThrow();
            assertEquals(HibernateFixtureContract.GROUP1_VALUE, withValue.value);
            assertEquals(HibernateFixtureContract.DEPT_ROOT_NAME, withValue.department.name);

            WorkGroup withoutValue = groups.stream()
                    .filter(g -> HibernateFixtureContract.GROUP2_KEY.equals(g.key))
                    .findFirst().orElseThrow();
            assertNull(withoutValue.value);
            assertNull(withoutValue.department);
        });
    }

    // ---- Weiterarbeiten, nicht nur lesen ----

    @Test
    @DisplayName("In das migrierte Schema laesst sich weiter schreiben (AUTO_INCREMENT vergibt IDs)")
    void migratedSchemaIsWritable() {
        Long newId = sessionFactory.fromTransaction(session -> {
            Department department = session
                    .createQuery("from Department where parent is null", Department.class)
                    .getSingleResult();

            Employee neu = new Employee("Nach", "Migration", department,
                    Enums.EmploymentType.PART_TIME, true);
            neu.skills.add("MariaDB");
            session.persist(neu);
            session.flush();

            assertNotNull(neu.id, "AUTO_INCREMENT muss eine ID vergeben haben");
            return neu.id;
        });

        sessionFactory.inTransaction(session -> {
            Employee geladen = session.find(Employee.class, newId);
            assertEquals("Migration", geladen.lastName);
            assertEquals(List.of("MariaDB"), geladen.skills);
            // Aufraeumen, damit die anderen Pruefungen den Datenbestand unveraendert vorfinden.
            session.remove(geladen);
        });

        sessionFactory.inTransaction(session ->
                assertNull(session.find(Employee.class, newId), "Testzeile wurde nicht entfernt"));
    }

    @Test
    @DisplayName("Sequenz-IDs setzen nach der Migration fort, statt mit vorhandenen zu kollidieren")
    void sequenceGeneratedIdsContinueAfterMigration() {
        Long maxExistingId = sessionFactory.fromTransaction(session -> session
                .createQuery("select max(g.id) from WorkGroup g", Long.class).getSingleResult());

        Long newId = sessionFactory.fromTransaction(session -> {
            WorkGroup group = new WorkGroup("nach.migration", "ja", null);
            session.persist(group);
            session.flush();
            return group.id;
        });

        assertTrue(newId > maxExistingId, "GROUP_SEQ muss nach " + maxExistingId
                + " weiterzaehlen, lieferte aber " + newId);

        sessionFactory.inTransaction(session -> session.remove(session.find(WorkGroup.class, newId)));
    }

    @Test
    @DisplayName("Erzeugte Zeitstempel bleiben erhalten und werden weiter fortgeschrieben")
    void generatedTimestampsSurviveAndKeepWorking() {
        Long id = sessionFactory.fromTransaction(session -> {
            Announcement announcement = announcementByTitle(session,
                    HibernateFixtureContract.ANNOUNCEMENT1_TITLE);

            // Aus der Migration mitgebracht: die Zeile wurde in H2 nach dem Einfuegen geaendert.
            assertNotNull(announcement.createdAt);
            assertNotNull(announcement.updatedAt);
            assertTrue(localTimeOf(announcement.updatedAt).isAfter(announcement.createdAt),
                    "UPDATED_AT muss auch nach der Migration nach CREATED_AT liegen");
            assertEquals(HibernateFixtureContract.DEPT_ROOT_NAME, announcement.department.name);
            return announcement.id;
        });

        LocalDateTime createdBefore = sessionFactory.fromTransaction(session ->
                session.find(Announcement.class, id).createdAt);
        Instant updatedBefore = sessionFactory.fromTransaction(session ->
                session.find(Announcement.class, id).updatedAt);

        // Jetzt auf der migrierten Datenbank aendern: @UpdateTimestamp muss nachziehen,
        // @CreationTimestamp stehen bleiben (die Spalte ist als updatable=false gemappt).
        sessionFactory.inTransaction(session -> {
            Announcement announcement = session.find(Announcement.class, id);
            announcement.title = HibernateFixtureContract.ANNOUNCEMENT1_TITLE + " (verschoben)";
        });

        sessionFactory.inTransaction(session -> {
            Announcement announcement = session.find(Announcement.class, id);
            assertEquals(createdBefore, announcement.createdAt,
                    "CREATED_AT darf sich bei einer Aenderung nicht bewegen");
            assertTrue(announcement.updatedAt.isAfter(updatedBefore),
                    "UPDATED_AT (" + announcement.updatedAt + ") haette nachziehen muessen, war "
                            + updatedBefore);

            announcement.title = HibernateFixtureContract.ANNOUNCEMENT1_TITLE;
        });
    }

    // ---- Der eine Punkt, an dem sich die Bedeutung aendert ----

    @Test
    @DisplayName("TIMESTAMP WITH TIME ZONE verliert bei der Migration seinen Offset")
    void timeZoneOffsetIsLostAsDocumented() {
        sessionFactory.inTransaction(session -> {
            Employee emp = employeeByLastName(session, HibernateFixtureContract.EMP1_LAST_NAME);

            // Eingefuegt wurde 2019-03-25T14:30+02:00. H2 haelt den Offset fest, MariaDB kennt
            // keinen zonenbehafteten Typ -- der Migrator legt den Zeitpunkt deshalb als UTC ab
            // (siehe Warnung im Migrationslog). Der Zeitpunkt ist also erhalten, die Anwendung
            // liest ihn danach aber in der Zeitzone der JVM statt in UTC.
            assertEquals(LocalDateTime.of(2019, 3, 25, 12, 30), emp.contractSignedAt.toLocalDateTime(),
                    "14:30+02:00 liegt als 12:30 in der Spalte");
            assertNotNull(emp.createdAt);
        });
    }

    private static Employee employeeByLastName(Session session, String lastName) {
        return session.createQuery("from Employee where lastName = :name", Employee.class)
                .setParameter("name", lastName)
                .getSingleResult();
    }

    private static Announcement announcementByTitle(Session session, String title) {
        return session.createQuery("from Announcement where title = :title", Announcement.class)
                .setParameter("title", title)
                .getSingleResult();
    }

    /** UPDATED_AT ist ein Instant, CREATED_AT eine zonenlose Zeit -- fuer den Vergleich wird der
     *  Zeitpunkt in dieselbe Darstellung gebracht. */
    private static LocalDateTime localTimeOf(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
