package com.pixeldweller.migrape.db;

/** Kapselt die Unterschiede zwischen H2 (Quelle) und MariaDB (Ziel) bei Bezeichnern. */
public enum DbDialect {

    H2('"'),
    MARIADB('`');

    private final char quoteChar;

    DbDialect(char quoteChar) {
        this.quoteChar = quoteChar;
    }

    /** Quotet einen Bezeichner. Ein im Namen enthaltenes Quote-Zeichen wird verdoppelt --
     *  sowohl H2 als auch MariaDB escapen ihr Quote-Zeichen auf diese Weise. */
    public String quote(String identifier) {
        String doubled = String.valueOf(quoteChar) + quoteChar;
        return quoteChar + identifier.replace(String.valueOf(quoteChar), doubled) + quoteChar;
    }
}
