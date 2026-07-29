package com.pixeldweller.migrape.db;

/** Kapselt die Unterschiede zwischen H2 (Quelle) und MariaDB (Ziel) bei Bezeichnern. */
public enum DbDialect {

    H2('"'),
    MARIADB('`');

    private final char quoteChar;

    DbDialect(char quoteChar) {
        this.quoteChar = quoteChar;
    }

    public String quote(String identifier) {
        return quoteChar + identifier + quoteChar;
    }
}
