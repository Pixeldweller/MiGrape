package com.pixeldweller.migrape.db;

import com.pixeldweller.migrape.MigrationConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Erzeugt einfache JDBC-Connections. Bewusst ohne Connection-Pool (HikariCP etc.),
 *  da nur ein sequenzieller Migrationslauf pro Tabelle noetig ist. */
public final class ConnectionFactory {

    private final MigrationConfig config;

    public ConnectionFactory(MigrationConfig config) {
        this.config = config;
    }

    public Connection openH2() throws SQLException {
        return DriverManager.getConnection(config.h2Url, config.h2User, config.h2Password);
    }

    public Connection openMaria() throws SQLException {
        Connection conn = DriverManager.getConnection(config.mariaUrl, config.mariaUser, config.mariaPassword);
        conn.setAutoCommit(false);
        return conn;
    }
}
