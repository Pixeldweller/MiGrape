package com.pixeldweller.migrape.testsupport.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "TRUCK")
@PrimaryKeyJoinColumn(name = "VEHICLE_ID", foreignKey = @ForeignKey(name = "FK_TRUCK_VEHICLE"))
public class Truck extends Vehicle {

    @Column(name = "PAYLOAD_KG", precision = 8, scale = 2)
    public BigDecimal payloadKg;

    public Truck() {
    }

    public Truck(String plate, Department department, BigDecimal payloadKg) {
        super(plate, department);
        this.payloadKg = payloadKg;
    }
}
