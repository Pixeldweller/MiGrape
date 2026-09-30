package com.pixeldweller.migrape.db;

import java.util.Locale;

/** Schreibweise der Bezeichner (Tabellen, Spalten, Constraints, Indizes, Sequenzen) im Ziel.
 *
 *  H2 legt nicht gequotete Bezeichner in Grossbuchstaben ab -- auch wenn die Anwendung sie
 *  klein schreibt (z.B. {@code @Table(name = "kunde")}). Unter Linux vergleicht MariaDB
 *  Tabellennamen aber case-sensitiv ({@code lower_case_table_names=0}): eine 1:1 uebernommene
 *  Tabelle KUNDE findet die Anwendung dann nicht, und Hibernate legt mit {@code ddl-auto=update}
 *  eine leere Tabelle kunde daneben an. */
public enum IdentifierCase {

    /** Alles kleinschreiben -- passt zu den Standard-Namensstrategien von Hibernate/Spring. */
    LOWER,
    /** Alles grossschreiben, so wie H2 nicht gequotete Bezeichner fuehrt. */
    UPPER,
    /** Bezeichner genau so uebernehmen, wie H2 sie meldet. */
    PRESERVE;

    public String apply(String identifier) {
        return switch (this) {
            case LOWER -> identifier.toLowerCase(Locale.ROOT);
            case UPPER -> identifier.toUpperCase(Locale.ROOT);
            case PRESERVE -> identifier;
        };
    }

    public static IdentifierCase parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ungueltiger Wert fuer maria.identifier.case: '"
                    + value.trim() + "' (erlaubt: lower, upper, preserve)");
        }
    }
}
