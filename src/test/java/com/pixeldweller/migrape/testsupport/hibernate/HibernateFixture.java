package com.pixeldweller.migrape.testsupport.hibernate;

import com.pixeldweller.migrape.testsupport.HibernateFixtureContract;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Baut die Hibernate-Laufzeit fuer das Fixture-Modell und befuellt eine Datenbank damit.
 *
 * Bewusst programmatisch konfiguriert (keine persistence.xml, kein hibernate.cfg.xml): so ist
 * im Test sichtbar, welche Einstellungen das erzeugte Schema beeinflussen. Die
 * Property-Schluessel stehen als Literale da, damit sie unabhaengig von API-Umbenennungen
 * in Hibernate gueltig bleiben.
 */
public final class HibernateFixture {

    /** Alle Entities des Modells -- ohne Klassenpfad-Scan, damit nichts unbemerkt dazukommt. */
    public static final List<Class<?>> ENTITIES = List.of(
            Department.class, Employee.class, Project.class, TimeEntry.class,
            WorkGroup.class, Vehicle.class, Car.class, Truck.class, Announcement.class);

    private HibernateFixture() {
    }

    /** Hibernate auf der H2-Fixture. */
    public static SessionFactory openSessionFactory(String jdbcUrl, boolean createSchema) {
        return open("org.h2.Driver", jdbcUrl, "sa", "", "org.hibernate.dialect.H2Dialect",
                createSchema);
    }

    /** Hibernate auf der bereits migrierten MariaDB -- dieselben Entities, anderes DBMS.
     *  Das Schema kommt hier ausschliesslich vom Migrator, Hibernate darf nichts anlegen.
     *  Der Dialekt wird bewusst nicht gesetzt: Hibernate soll ihn samt Serverversion aus den
     *  JDBC-Metadaten ermitteln, so wie in einer echten Anwendung. */
    public static SessionFactory openMariaDbSessionFactory(String jdbcUrl, String user, String password) {
        return open("org.mariadb.jdbc.Driver", jdbcUrl, user, password, null, false);
    }

    private static SessionFactory open(String driver, String jdbcUrl, String user, String password,
                                       String dialect, boolean createSchema) {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.connection.driver_class", driver);
        cfg.setProperty("hibernate.connection.url", jdbcUrl);
        cfg.setProperty("hibernate.connection.username", user);
        cfg.setProperty("hibernate.connection.password", password);
        cfg.setProperty("hibernate.connection.pool_size", "1");
        if (dialect != null) {
            cfg.setProperty("hibernate.dialect", dialect);
        }
        cfg.setProperty("hibernate.hbm2ddl.auto", createSchema ? "create" : "none");
        cfg.setProperty("hibernate.show_sql", "false");
        // Bewusst KEIN hibernate.jdbc.time_zone: die Einstellung schaltet die Bindung der
        // Zeit-Typen auf Calendar-Basis um und rechnet damit auch LocalTime/LocalDateTime in die
        // gesetzte Zone -- aus 07:30 wuerde in der Datenbank 06:30. Ohne die Einstellung landen
        // zonenlose Werte wortwoertlich in der Spalte, und OffsetDateTime behaelt in H2
        // (TIMESTAMP WITH TIME ZONE) seinen Offset.
        for (Class<?> entity : ENTITIES) {
            cfg.addAnnotatedClass(entity);
        }
        return cfg.buildSessionFactory();
    }

    /** Erzeugt Schema und Daten in einer neuen H2-Datei und gibt die .mv.db-Datei zurueck. */
    public static Path createFileDatabase(Path directory) {
        Path mvDb = directory.resolve(HibernateFixtureContract.DB_BASE_NAME + ".mv.db");
        try (SessionFactory sessionFactory =
                     openSessionFactory(HibernateFixtureContract.h2Url(mvDb), true)) {
            insertData(sessionFactory);
        }
        return mvDb;
    }

    /** Schreibt den kompletten Fixture-Datensatz. Reihenfolge: Eltern vor Kindern, damit die
     *  Fremdschluessel schon beim Schreiben in H2 gueltig sind. */
    public static void insertData(SessionFactory sessionFactory) {
        sessionFactory.inTransaction(session -> {
            Department root = new Department(HibernateFixtureContract.DEPT_ROOT_NAME,
                    Enums.Region.EMEA, HibernateFixtureContract.DEPT_ROOT_BUDGET, true, null);
            Department child = new Department(HibernateFixtureContract.DEPT_CHILD_NAME,
                    Enums.Region.APAC, null, false, root);
            session.persist(root);
            session.persist(child);

            Project project1 = new Project(HibernateFixtureContract.PROJECT1_CODE,
                    HibernateFixtureContract.PROJECT1_TITLE, Enums.ProjectStatus.ACTIVE,
                    new BigDecimal("99999.99"), LocalDate.of(2025, 12, 31), root);
            // Zweites Projekt bewusst ohne Budget, Deadline und Abteilung.
            Project project2 = new Project(HibernateFixtureContract.PROJECT2_CODE,
                    HibernateFixtureContract.PROJECT2_TITLE, Enums.ProjectStatus.PLANNED,
                    null, null, null);
            session.persist(project1);
            session.persist(project2);

            Employee emp1 = fullyPopulatedEmployee(child);
            emp1.projects.add(project1);
            emp1.projects.add(project2);
            session.persist(emp1);

            // Zweite Zeile: jede nullbare Spalte bleibt NULL.
            Employee emp2 = new Employee(HibernateFixtureContract.EMP2_FIRST_NAME,
                    HibernateFixtureContract.EMP2_LAST_NAME, root,
                    Enums.EmploymentType.CONTRACTOR, false);
            emp2.manager = emp1;
            session.persist(emp2);

            Employee emp3 = new Employee(HibernateFixtureContract.EMP3_FIRST_NAME,
                    HibernateFixtureContract.EMP3_LAST_NAME, root,
                    Enums.EmploymentType.PART_TIME, true);
            emp3.manager = emp1;
            emp3.hireDate = LocalDate.of(2023, 9, 15);
            emp3.salary = new BigDecimal("4100.00");
            emp3.securityLevel = Enums.SecurityLevel.INTERNAL;
            emp3.skills.add("Docker");
            emp3.projects.add(project1);
            session.persist(emp3);

            session.flush();

            session.persist(new TimeEntry(emp1, project1, HibernateFixtureContract.TIME_ENTRY1_DAY,
                    HibernateFixtureContract.TIME_ENTRY1_HOURS, "Analyse & Konzept", true));
            session.persist(new TimeEntry(emp1, project2, LocalDate.of(2024, 11, 5),
                    new BigDecimal("2.25"), null, false));
            session.persist(new TimeEntry(emp3, project1, LocalDate.of(2024, 11, 5),
                    new BigDecimal("8.00"), "Umsetzung", true));

            session.persist(new WorkGroup(HibernateFixtureContract.GROUP1_KEY,
                    HibernateFixtureContract.GROUP1_VALUE, root));
            session.persist(new WorkGroup(HibernateFixtureContract.GROUP2_KEY, null, null));

            session.persist(new Car(HibernateFixtureContract.CAR_PLATE, root,
                    HibernateFixtureContract.CAR_SEATS));
            session.persist(new Truck(HibernateFixtureContract.TRUCK_PLATE, child,
                    HibernateFixtureContract.TRUCK_PAYLOAD_KG));

            // CREATED_AT und UPDATED_AT setzt Hibernate selbst.
            session.persist(new Announcement(
                    HibernateFixtureContract.ANNOUNCEMENT1_TITLE_ON_INSERT, root));
            session.persist(new Announcement(HibernateFixtureContract.ANNOUNCEMENT2_TITLE, null));
        });

        touchFirstAnnouncement(sessionFactory);
    }

    /** Aendert die erste Ankuendigung in einer zweiten Transaktion. Erst dadurch enthaelt die
     *  Fixture eine Zeile, deren UPDATED_AT spaeter liegt als ihr CREATED_AT -- ohne diesen
     *  Schritt waeren beide Spalten praktisch gleich und der Unterschied nicht pruefbar. */
    private static void touchFirstAnnouncement(SessionFactory sessionFactory) {
        pauseBriefly();
        sessionFactory.inTransaction(session -> {
            Announcement announcement = session
                    .createQuery("from Announcement where title = :title", Announcement.class)
                    .setParameter("title", HibernateFixtureContract.ANNOUNCEMENT1_TITLE_ON_INSERT)
                    .getSingleResult();
            announcement.title = HibernateFixtureContract.ANNOUNCEMENT1_TITLE;
        });
    }

    /** Kurz warten, damit der neue Zeitstempel auch bei grober Uhrauflösung messbar spaeter liegt. */
    private static void pauseBriefly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Employee fullyPopulatedEmployee(Department department) {
        Employee emp = new Employee(HibernateFixtureContract.EMP1_FIRST_NAME,
                HibernateFixtureContract.EMP1_LAST_NAME, department,
                Enums.EmploymentType.FULL_TIME, true);
        emp.rank = HibernateFixtureContract.EMP1_RANK;
        emp.externalId = UUID.fromString(HibernateFixtureContract.EMP1_EXTERNAL_ID);
        emp.hireDate = HibernateFixtureContract.EMP1_HIRE_DATE;
        emp.lastLogin = LocalDateTime.of(2024, 11, 5, 8, 15, 30, 123_456_000);
        emp.createdAt = Instant.parse("2019-04-01T06:00:00Z");
        emp.contractSignedAt = OffsetDateTime.of(2019, 3, 25, 14, 30, 0, 0, ZoneOffset.ofHours(2));
        emp.shiftStart = HibernateFixtureContract.EMP1_SHIFT_START;
        emp.weeklyHours = Duration.ofNanos(HibernateFixtureContract.EMP1_WEEKLY_HOURS_NANOS);
        emp.salary = HibernateFixtureContract.EMP1_SALARY;
        emp.bonusFactor = HibernateFixtureContract.EMP1_BONUS_FACTOR;
        emp.score = HibernateFixtureContract.EMP1_SCORE;
        emp.accuracy = HibernateFixtureContract.EMP1_ACCURACY;
        emp.level = HibernateFixtureContract.EMP1_LEVEL;
        emp.seniority = HibernateFixtureContract.EMP1_SENIORITY;
        emp.vacationDays = HibernateFixtureContract.EMP1_VACATION_DAYS;
        emp.badgeNumber = HibernateFixtureContract.EMP1_BADGE_NUMBER;
        emp.gender = HibernateFixtureContract.EMP1_GENDER;
        emp.securityLevel = Enums.SecurityLevel.SECRET;
        emp.notes = HibernateFixtureContract.EMP1_NOTES;
        emp.photo = HibernateFixtureContract.EMP1_PHOTO;
        emp.fingerprint = HibernateFixtureContract.EMP1_FINGERPRINT;
        emp.address = new Address("Hauptstraße 1", HibernateFixtureContract.EMP1_CITY, "80331",
                HibernateFixtureContract.EMP1_COUNTRY_CODE);
        emp.skills.addAll(HibernateFixtureContract.EMP1_SKILLS);
        return emp;
    }
}
