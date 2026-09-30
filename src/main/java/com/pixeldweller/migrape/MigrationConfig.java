package com.pixeldweller.migrape;

import com.pixeldweller.migrape.db.IdentifierCase;
import com.pixeldweller.migrape.db.MariaDbSslConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class MigrationConfig {

    public final String h2Url;
    public final String h2User;
    public final String h2Password;
    /** Zu migrierendes H2-Schema. null = Schema der Verbindung (bei H2 ueblicherweise PUBLIC). */
    public final String h2Schema;

    public final String mariaUrl;
    public final String mariaUser;
    public final String mariaPassword;
    /** SSL/TLS- und weitere Treiber-Optionen fuer die MariaDB-Verbindung. */
    public final MariaDbSslConfig mariaSsl;
    /** Schreibweise der Bezeichner in MariaDB, Standard: {@link IdentifierCase#LOWER}. */
    public final IdentifierCase mariaIdentifierCase;

    public final int batchSize;
    public final int fetchSize;

    /** Falls gesetzt: nur diese Tabellen migrieren (Komma-getrennt), sonst alle. */
    public final String[] tableFilter;

    private MigrationConfig(Properties p) {
        this.h2Url = require(p, "h2.url");
        this.h2User = p.getProperty("h2.user", "sa");
        this.h2Password = p.getProperty("h2.password", "");
        this.h2Schema = optional(p, "h2.schema");

        this.mariaUrl = require(p, "maria.url");
        this.mariaUser = require(p, "maria.user");
        this.mariaPassword = p.getProperty("maria.password", "");
        this.mariaSsl = MariaDbSslConfig.from(p);
        String identifierCase = optional(p, "maria.identifier.case");
        this.mariaIdentifierCase = identifierCase == null
                ? IdentifierCase.LOWER : IdentifierCase.parse(identifierCase);

        this.batchSize = positiveInt(p, "batch.size", 2000);
        this.fetchSize = positiveInt(p, "fetch.size", 2000);

        String filter = p.getProperty("tables", "").trim();
        this.tableFilter = filter.isEmpty() ? null : filter.split("\\s*,\\s*");
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Fehlender Pflichtwert in config.properties: " + key);
        }
        return v.trim();
    }

    private static String optional(Properties p, String key) {
        String v = p.getProperty(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static int positiveInt(Properties p, String key, int defaultValue) {
        String raw = p.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Ungueltiger Zahlenwert fuer " + key + " in config.properties: '" + raw.trim() + "'");
        }
        if (value <= 0) {
            throw new IllegalArgumentException(key + " muss groesser als 0 sein (war: " + value + ")");
        }
        return value;
    }

    public static MigrationConfig load(Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        return new MigrationConfig(props);
    }

    public boolean isTableIncluded(String tableName) {
        if (tableFilter == null) {
            return true;
        }
        for (String t : tableFilter) {
            if (t.equalsIgnoreCase(tableName)) {
                return true;
            }
        }
        return false;
    }
}
