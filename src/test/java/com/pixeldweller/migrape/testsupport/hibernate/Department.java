package com.pixeldweller.migrape.testsupport.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

/**
 * Wurzel des Modells. Deckt ab: IDENTITY-Schluessel (wird in MariaDB AUTO_INCREMENT),
 * benannter Unique-Constraint, benannter Sekundaerindex, DEFAULT-Werte (Zahl und String),
 * eine Versionsspalte fuer Optimistic Locking und ein selbstreferenzierender Fremdschluessel.
 *
 * Alle Tabellen-, Spalten- und Constraint-Namen sind explizit gesetzt: so haengt das
 * erzeugte Schema nicht an der Namensstrategie von Hibernate und die Migrationstests
 * koennen exakte Namen pruefen.
 */
@Entity
@Table(name = "DEPARTMENT",
        uniqueConstraints = @UniqueConstraint(name = "UQ_DEPARTMENT_NAME", columnNames = "NAME"),
        indexes = @Index(name = "IDX_DEPARTMENT_REGION", columnList = "REGION"))
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    public Long id;

    @Column(name = "NAME", length = 120, nullable = false)
    public String name;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'EMEA'")
    @Column(name = "REGION", length = 16, nullable = false)
    public Enums.Region region;

    @Column(name = "BUDGET", precision = 14, scale = 2)
    public BigDecimal budget;

    @ColumnDefault("1")
    @Column(name = "ACTIVE", nullable = false)
    public boolean active = true;

    @Version
    @Column(name = "VERSION", nullable = false)
    public int version;

    @ManyToOne
    @JoinColumn(name = "PARENT_ID", foreignKey = @ForeignKey(name = "FK_DEPARTMENT_PARENT"))
    public Department parent;

    public Department() {
    }

    public Department(String name, Enums.Region region, BigDecimal budget, boolean active, Department parent) {
        this.name = name;
        this.region = region;
        this.budget = budget;
        this.active = active;
        this.parent = parent;
    }
}
