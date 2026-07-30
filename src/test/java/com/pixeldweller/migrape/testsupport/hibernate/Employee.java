package com.pixeldweller.migrape.testsupport.hibernate;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Die Typ-Sammelstelle des Modells: jede Spalte hier steht fuer einen eigenen Zweig im
 * TypeMapper (Zahlen in allen Groessen, DECIMAL mit Skala, Datum/Zeit mit und ohne Zone,
 * Duration, UUID, CHAR, VARCHAR, CLOB, BLOB, VARBINARY, Enum als Text und als Ordinalwert).
 *
 * Ausserdem: zwei Fremdschluessel (einer davon selbstreferenzierend), ein eingebetteter
 * Wertetyp, eine Element-Collection (Tabelle ohne Primaerschluessel!), eine Many-to-Many-
 * Verknuepfungstabelle und eine Spalte mit reserviertem Namen (RANK).
 *
 * Alle nullbaren Felder sind Wrapper-Typen, damit die zweite Zeile im Fixture ueberall NULL
 * haben kann.
 */
@Entity
@Table(name = "EMPLOYEE",
        uniqueConstraints = @UniqueConstraint(name = "UQ_EMPLOYEE_EXTERNAL_ID", columnNames = "EXTERNAL_ID"),
        indexes = {
                @Index(name = "IDX_EMPLOYEE_LAST_NAME", columnList = "LAST_NAME"),
                @Index(name = "IDX_EMPLOYEE_DEPT_ACTIVE", columnList = "DEPARTMENT_ID, ACTIVE")
        })
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    public Long id;

    @Column(name = "FIRST_NAME", length = 80, nullable = false)
    public String firstName;

    @Column(name = "LAST_NAME", length = 80, nullable = false)
    public String lastName;

    /** RANK ist in MariaDB ab 10.2 ein reserviertes Wort -- die Backticks weisen Hibernate an,
     *  den Namen im DDL zu quoten. */
    @Column(name = "`RANK`", length = 20)
    public String rank;

    @Column(name = "EXTERNAL_ID")
    public UUID externalId;

    @Column(name = "HIRE_DATE")
    public LocalDate hireDate;

    @Column(name = "LAST_LOGIN")
    public LocalDateTime lastLogin;

    @Column(name = "CREATED_AT")
    public Instant createdAt;

    /** Mit Zone: H2 speichert TIMESTAMP WITH TIME ZONE, MariaDB kennt das nicht --
     *  der Wert muss als UTC-Zeitpunkt ankommen. */
    @Column(name = "CONTRACT_SIGNED_AT")
    public OffsetDateTime contractSignedAt;

    @Column(name = "SHIFT_START")
    public LocalTime shiftStart;

    /** Hibernate speichert Duration als Nanosekunden in einer NUMERIC(21)-Spalte. */
    @Column(name = "WEEKLY_HOURS")
    public Duration weeklyHours;

    @Column(name = "SALARY", precision = 12, scale = 2)
    public BigDecimal salary;

    @Column(name = "BONUS_FACTOR")
    public Double bonusFactor;

    /** Hibernate bildet Float auf H2 FLOAT ab -- und das ist in H2 8 Byte breit (gemeldet als
     *  DOUBLE PRECISION). Auf MariaDB FLOAT abzubilden wuerde die Genauigkeit halbieren. */
    @Column(name = "SCORE")
    public Float score;

    /** Der Gegenprobe halber ausdruecklich das 4-Byte-REAL von H2. */
    @JdbcTypeCode(SqlTypes.REAL)
    @Column(name = "ACCURACY")
    public Float accuracy;

    @Column(name = "LEVEL")
    public Byte level;

    @Column(name = "SENIORITY")
    public Short seniority;

    @Column(name = "VACATION_DAYS")
    public Integer vacationDays;

    @Column(name = "BADGE_NUMBER")
    public Long badgeNumber;

    @Column(name = "GENDER")
    public Character gender;

    @Column(name = "ACTIVE", nullable = false)
    public boolean active;

    @Enumerated(EnumType.STRING)
    @Column(name = "EMPLOYMENT_TYPE", length = 32, nullable = false)
    public Enums.EmploymentType employmentType;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "SECURITY_LEVEL")
    public Enums.SecurityLevel securityLevel;

    @Lob
    @Column(name = "NOTES")
    public String notes;

    @Lob
    @Column(name = "PHOTO")
    public byte[] photo;

    /** Ohne @Lob und mit fixer Laenge: VARBINARY, nicht BLOB. */
    @Column(name = "FINGERPRINT", length = 64)
    public byte[] fingerprint;

    @Embedded
    public Address address;

    @ManyToOne(optional = false)
    @JoinColumn(name = "DEPARTMENT_ID", nullable = false,
            foreignKey = @ForeignKey(name = "FK_EMPLOYEE_DEPARTMENT"))
    public Department department;

    @ManyToOne
    @JoinColumn(name = "MANAGER_ID", foreignKey = @ForeignKey(name = "FK_EMPLOYEE_MANAGER"))
    public Employee manager;

    /** Element-Collection als Bag (List ohne Ordnungsspalte): eigene Tabelle mit Fremdschluessel,
     *  aber ohne Primaerschluessel -- eine Menge haette Hibernate mit einem zusammengesetzten
     *  Primaerschluessel abgedeckt. Tabellen ohne Primaerschluessel muss der Migrator koennen. */
    @ElementCollection
    @CollectionTable(name = "EMPLOYEE_SKILL",
            joinColumns = @JoinColumn(name = "EMPLOYEE_ID",
                    foreignKey = @ForeignKey(name = "FK_SKILL_EMPLOYEE")))
    @Column(name = "SKILL", length = 40, nullable = false)
    public List<String> skills = new ArrayList<>();

    @ManyToMany
    @JoinTable(name = "PROJECT_MEMBER",
            joinColumns = @JoinColumn(name = "EMPLOYEE_ID",
                    foreignKey = @ForeignKey(name = "FK_MEMBER_EMPLOYEE")),
            inverseJoinColumns = @JoinColumn(name = "PROJECT_CODE",
                    foreignKey = @ForeignKey(name = "FK_MEMBER_PROJECT")))
    public Set<Project> projects = new LinkedHashSet<>();

    public Employee() {
    }

    public Employee(String firstName, String lastName, Department department,
                    Enums.EmploymentType employmentType, boolean active) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.department = department;
        this.employmentType = employmentType;
        this.active = active;
    }
}
