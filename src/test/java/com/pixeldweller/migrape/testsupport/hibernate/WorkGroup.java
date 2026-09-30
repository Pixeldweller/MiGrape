package com.pixeldweller.migrape.testsupport.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Der unangenehme Fall: die Tabelle heisst GROUP, die Spalten KEY und VALUE -- alles
 * reservierte Woerter in H2 <em>und</em> MariaDB. Ohne Quoting bricht bereits das SELECT
 * beim Kopieren.
 *
 * Zusaetzlich wird der Schluessel hier aus einer H2-SEQUENCE gezogen statt per IDENTITY:
 * die Spalte darf auf der Zielseite kein AUTO_INCREMENT haben, und die Sequenz muss mit ihrem
 * aktuellen Stand nach MariaDB mitkommen.
 */
@Entity
@Table(name = "`GROUP`")
public class WorkGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "group_seq")
    @SequenceGenerator(name = "group_seq", sequenceName = "GROUP_SEQ", allocationSize = 1)
    @Column(name = "ID")
    public Long id;

    @Column(name = "`KEY`", length = 50, nullable = false)
    public String key;

    @Column(name = "`VALUE`", length = 200)
    public String value;

    @ManyToOne
    @JoinColumn(name = "DEPARTMENT_ID", foreignKey = @ForeignKey(name = "FK_GROUP_DEPARTMENT"))
    public Department department;

    public WorkGroup() {
    }

    public WorkGroup(String key, String value, Department department) {
        this.key = key;
        this.value = value;
        this.department = department;
    }
}
