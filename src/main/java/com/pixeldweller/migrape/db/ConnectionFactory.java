package com.pixeldweller.migrape.db;

import com.pixeldweller.migrape.MigrationConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;

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

    /** H2 als <em>Ziel</em>: die Datenbank muss bereits existieren. Ohne IFEXISTS wuerde H2 bei
     *  einem Tippfehler im Pfad still eine neue, leere Datei anlegen. */
    public Connection openExistingH2() throws SQLException {
        return DriverManager.getConnection(existingOnly(config.h2Url), config.h2User, config.h2Password);
    }

    static String existingOnly(String h2Url) {
        String upper = h2Url.toUpperCase(Locale.ROOT);
        if (upper.contains(";IFEXISTS=") || upper.startsWith("JDBC:H2:MEM:")) {
            return h2Url;
        }
        return h2Url + ";IFEXISTS=TRUE";
    }

    public Connection openMaria() throws SQLException {
        Connection conn = DriverManager.getConnection(config.mariaUrl, mariaProperties());
        conn.setAutoCommit(false);
        return conn;
    }

    /** Zugangsdaten plus die konfigurierten SSL-/Treiber-Optionen. Optionen, die zusaetzlich in
     *  maria.url stehen, haben beim Treiber Vorrang. */
    private Properties mariaProperties() {
        Properties props = new Properties();
        props.setProperty("user", config.mariaUser);
        props.setProperty("password", config.mariaPassword);
        config.mariaSsl.applyTo(props);
        return props;
    }
}
