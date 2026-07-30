package com.pixeldweller.migrape.testsupport.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/** Entity mit natuerlichem Schluessel: der Primaerschluessel ist ein VARCHAR, keine Identity.
 *  Damit haengt an PROJECT_MEMBER und TIME_ENTRY ein Fremdschluessel auf eine Textspalte. */
@Entity
@Table(name = "PROJECT")
public class Project {

    @Id
    @Column(name = "CODE", length = 12)
    public String code;

    @Column(name = "TITLE", length = 200, nullable = false)
    public String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", length = 16, nullable = false)
    public Enums.ProjectStatus status;

    @Column(name = "BUDGET", precision = 14, scale = 2)
    public BigDecimal budget;

    @Column(name = "DEADLINE")
    public LocalDate deadline;

    @ManyToOne
    @JoinColumn(name = "OWNER_DEPARTMENT_ID", foreignKey = @ForeignKey(name = "FK_PROJECT_DEPARTMENT"))
    public Department ownerDepartment;

    @ManyToMany(mappedBy = "projects")
    public Set<Employee> members = new LinkedHashSet<>();

    public Project() {
    }

    public Project(String code, String title, Enums.ProjectStatus status, BigDecimal budget,
                   LocalDate deadline, Department ownerDepartment) {
        this.code = code;
        this.title = title;
        this.status = status;
        this.budget = budget;
        this.deadline = deadline;
        this.ownerDepartment = ownerDepartment;
    }
}
