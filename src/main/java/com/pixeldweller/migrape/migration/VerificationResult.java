package com.pixeldweller.migrape.migration;

/** Ergebnis des Zeilenzahl-Vergleichs einer Tabelle.
 *  {@code problem} ist gesetzt, wenn eine der beiden Seiten nicht gelesen werden konnte
 *  (z.B. weil die Zieltabelle fehlt); die Zaehler sind dann -1. */
public record VerificationResult(String table, long sourceCount, long targetCount, String problem) {

    public VerificationResult(String table, long sourceCount, long targetCount) {
        this(table, sourceCount, targetCount, null);
    }

    public static VerificationResult failed(String table, String problem) {
        return new VerificationResult(table, -1, -1, problem);
    }

    public boolean ok() {
        return problem == null && sourceCount == targetCount;
    }
}
