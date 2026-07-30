package com.pixeldweller.migrape.testsupport.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Eingebetteter Wertetyp: die Spalten landen ohne eigene Tabelle in EMPLOYEE.
 *  COUNTRY_CODE ist bewusst CHAR(2) und nicht VARCHAR -- fixe Laenge ist ein eigener
 *  Zweig im TypeMapper. */
@Embeddable
public class Address {

    @Column(name = "STREET", length = 200)
    public String street;

    @Column(name = "CITY", length = 100)
    public String city;

    @Column(name = "POSTAL_CODE", length = 10)
    public String postalCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "COUNTRY_CODE", length = 2)
    public String countryCode;

    public Address() {
    }

    public Address(String street, String city, String postalCode, String countryCode) {
        this.street = street;
        this.city = city;
        this.postalCode = postalCode;
        this.countryCode = countryCode;
    }
}
