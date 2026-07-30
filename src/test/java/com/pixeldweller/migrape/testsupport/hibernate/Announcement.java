package com.pixeldweller.migrape.testsupport.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Entity mit von Hibernate erzeugten Zeitstempeln.
 *
 * Interessant fuer die Migration aus zwei Gruenden: die Werte stehen nirgends im Code, sondern
 * entstehen beim Schreiben -- und die Spalten muessen auf der Zielseite so beschaffen sein, dass
 * Hibernate sie weiter befuellen kann. Testreihe 3 aendert deshalb auf der migrierten MariaDB
 * eine Zeile und prueft, ob UPDATED_AT nachzieht und CREATED_AT stehen bleibt.
 *
 * Die beiden Zeitstempel benutzen bewusst verschiedene Java-Typen: LocalDateTime ist zonenlos,
 * Instant ein Zeitpunkt. In H2 landen beide in TIMESTAMP(6), in MariaDB in DATETIME(6) -- aber
 * <em>nicht</em> im selben Bezugssystem: Hibernate schreibt LocalDateTime als lokale Wanduhrzeit
 * und Instant nach UTC. In einer Datenbank ohne zonenbehafteten Typ stehen die beiden Spalten
 * damit um den Zonenoffset auseinander und duerfen in SQL nicht direkt verglichen werden -- ein
 * Vergleich ergibt nur ueber Hibernate ein richtiges Ergebnis oder innerhalb derselben Spalte.
 */
@Entity
@Table(name = "ANNOUNCEMENT")
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    public Long id;

    @Column(name = "TITLE", length = 200, nullable = false)
    public String title;

    /** Wird einmal beim Einfuegen gesetzt und danach nie wieder angefasst. */
    @CreationTimestamp
    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    public LocalDateTime createdAt;

    /** Wird bei jedem Schreibvorgang neu gesetzt. */
    @UpdateTimestamp
    @Column(name = "UPDATED_AT", nullable = false)
    public Instant updatedAt;

    @ManyToOne
    @JoinColumn(name = "DEPARTMENT_ID", foreignKey = @ForeignKey(name = "FK_ANNOUNCEMENT_DEPARTMENT"))
    public Department department;

    public Announcement() {
    }

    public Announcement(String title, Department department) {
        this.title = title;
        this.department = department;
    }
}
