package com.pixeldweller.migrape;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class MigrationConfig {

    public final String h2Url;
    public final String h2User;
    public final String h2Password;

    public final String mariaUrl;
    public final String mariaUser;
    public final String mariaPassword;

    public final int batchSize;
    public final int fetchSize;

    /** Falls gesetzt: nur diese Tabellen migrieren (Komma-getrennt), sonst alle. */
    public final String[] tableFilter;

    private MigrationConfig(Properties p) {
        this.h2Url = require(p, "h2.url");
        this.h2User = p.getProperty("h2.user", "sa");
        this.h2Password = p.getProperty("h2.password", "");

        this.mariaUrl = require(p, "maria.url");
        this.mariaUser = require(p, "maria.user");
        this.mariaPassword = p.getProperty("maria.password", "");

        this.batchSize = Integer.parseInt(p.getProperty("batch.size", "2000"));
        this.fetchSize = Integer.parseInt(p.getProperty("fetch.size", "2000"));

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
