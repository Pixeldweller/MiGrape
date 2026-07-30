package com.pixeldweller.migrape.testsupport.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Zusammengesetzter Schluessel plus CHECK-Constraint. Der CHECK wird bewusst nicht migriert
 *  (H2-Ausdruecke sind nicht allgemein nach MariaDB uebertragbar) -- der Migrator muss das
 *  protokollieren statt still zu scheitern. */
@Entity
@Table(name = "TIME_ENTRY")
@Check(name = "CK_TIME_ENTRY_HOURS", constraints = "HOURS > 0")
public class TimeEntry {

    @EmbeddedId
    public TimeEntryId id;

    @MapsId("employeeId")
    @ManyToOne(optional = false)
    @JoinColumn(name = "EMPLOYEE_ID", foreignKey = @ForeignKey(name = "FK_TIME_ENTRY_EMPLOYEE"))
    public Employee employee;

    @MapsId("projectCode")
    @ManyToOne(optional = false)
    @JoinColumn(name = "PROJECT_CODE", foreignKey = @ForeignKey(name = "FK_TIME_ENTRY_PROJECT"))
    public Project project;

    @Column(name = "HOURS", precision = 5, scale = 2, nullable = false)
    public BigDecimal hours;

    @Column(name = "DESCRIPTION", length = 500)
    public String description;

    @Column(name = "BILLABLE", nullable = false)
    public boolean billable;

    public TimeEntry() {
    }

    public TimeEntry(Employee employee, Project project, LocalDate workDay, BigDecimal hours,
                     String description, boolean billable) {
        this.id = new TimeEntryId(employee.id, project.code, workDay);
        this.employee = employee;
        this.project = project;
        this.hours = hours;
        this.description = description;
        this.billable = billable;
    }
}
