package com.pixeldweller.migrape.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persistiert, welche Tabellen bereits vollstaendig migriert wurden (Tabellen-Granularitaet).
 *  Ein teilweise kopierter Tabelleninhalt wird bei Resume komplett neu kopiert (kein
 *  Zeilen-genaues Fortsetzen, da H2 keine stabile Zeilenreihenfolge garantiert). */
public final class MigrationState {

    private final Path stateFile;
    private final Properties done = new Properties();

    public MigrationState(Path stateFile) {
        this.stateFile = stateFile;
        load();
    }

    private void load() {
        if (Files.exists(stateFile)) {
            try (InputStream in = Files.newInputStream(stateFile)) {
                done.load(in);
            } catch (IOException e) {
                // Zustandsdatei ignorieren, falls beschaedigt -> Migration startet von vorn
            }
        }
    }

    public boolean isDone(String table) {
        return "DONE".equals(done.getProperty(table));
    }

    public void markDone(String table) {
        done.setProperty(table, "DONE");
        persist();
    }

    private void persist() {
        try (OutputStream out = Files.newOutputStream(stateFile)) {
            done.store(out, "MiGrape Migrationsstatus");
        } catch (IOException e) {
            System.err.println("Konnte Migrationsstatus nicht speichern: " + e.getMessage());
        }
    }

    public void clear() {
        done.clear();
        try {
            Files.deleteIfExists(stateFile);
        } catch (IOException ignored) {
        }
    }
}
