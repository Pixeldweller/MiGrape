package com.pixeldweller.migrape.testsupport.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "CAR")
@PrimaryKeyJoinColumn(name = "VEHICLE_ID", foreignKey = @ForeignKey(name = "FK_CAR_VEHICLE"))
public class Car extends Vehicle {

    @Column(name = "SEATS")
    public Integer seats;

    public Car() {
    }

    public Car(String plate, Department department, Integer seats) {
        super(plate, department);
        this.seats = seats;
    }
}
