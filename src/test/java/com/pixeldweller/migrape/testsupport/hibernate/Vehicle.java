package com.pixeldweller.migrape.testsupport.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** JOINED-Vererbung: VEHICLE haelt die gemeinsamen Spalten, CAR und TRUCK je eine eigene
 *  Tabelle, deren Primaerschluessel gleichzeitig Fremdschluessel auf VEHICLE.ID ist. */
@Entity
@Table(name = "VEHICLE")
@Inheritance(strategy = InheritanceType.JOINED)
public abstract class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    public Long id;

    @Column(name = "PLATE", length = 16, nullable = false)
    public String plate;

    @ManyToOne
    @JoinColumn(name = "DEPARTMENT_ID", foreignKey = @ForeignKey(name = "FK_VEHICLE_DEPARTMENT"))
    public Department department;

    protected Vehicle() {
    }

    protected Vehicle(String plate, Department department) {
        this.plate = plate;
        this.department = department;
    }
}
