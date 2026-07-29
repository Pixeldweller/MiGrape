package com.pixeldweller.migrape;

import com.pixeldweller.migrape.migration.MigrationService;
import com.pixeldweller.migrape.migration.MigrationState;
import com.pixeldweller.migrape.migration.VerificationResult;
import com.pixeldweller.migrape.util.Log;

import java.nio.file.Path;
import java.util.List;

public final class Main {

    public static void main(String[] args) {
        String command = args.length > 0 ? args[0] : "migrate";
        Path configPath = Path.of(argValue(args, "--config", "config.properties"));

        Log.init(Path.of("migration.log"));
        Log.info("MiGrape H2 -> MariaDB Migrator");
        Log.info("Kommando: " + command + " | Config: " + configPath);

        try {
            MigrationConfig config = MigrationConfig.load(configPath);
            MigrationService service = new MigrationService(config);
            MigrationState state = new MigrationState(MigrationService.defaultStateFile());

            switch (command) {
                case "schema" -> service.migrateSchema(true);
                case "data" -> service.migrateData(state);
                case "resume" -> service.migrateData(state);
                case "verify" -> printVerification(service.verify());
                case "migrate" -> {
                    state.clear();
                    service.migrateSchema(true);
                    service.migrateData(state);
                    printVerification(service.verify());
                }
                default -> {
                    printUsage();
                    System.exit(1);
                }
            }

            Log.info("Fertig.");
        } catch (Exception e) {
            Log.error("Migration abgebrochen", e);
            System.exit(1);
        } finally {
            Log.close();
        }
    }

    private static void printVerification(List<VerificationResult> results) {
        Log.info("---- Verifikation ----");
        boolean allOk = true;
        for (VerificationResult r : results) {
            String status = r.ok() ? "OK" : "ABWEICHUNG";
            if (!r.ok()) {
                allOk = false;
            }
            Log.info(String.format("%-30s %-11s H2=%d  MariaDB=%d", r.table(), status, r.sourceCount(), r.targetCount()));
        }
        Log.info(allOk ? "Alle Tabellen erfolgreich migriert." : "ACHTUNG: Es gibt Abweichungen, siehe oben.");
    }

    private static String argValue(String[] args, String flag, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static void printUsage() {
        System.out.println("""
                Verwendung: java -jar migrape.jar <kommando> [--config <pfad>]

                Kommandos:
                  migrate   Schema anlegen, Daten kopieren, verifizieren (Standard, setzt Status zurueck)
                  schema    Nur Schema (Tabellen + Foreign Keys) auf MariaDB anlegen
                  data      Nur Daten kopieren (Schema muss existieren, nutzt migration.state)
                  resume    Wie 'data', ueberspringt bereits als DONE markierte Tabellen
                  verify    Nur Zeilenzahlen zwischen H2 und MariaDB vergleichen

                Konfiguration ueber config.properties (siehe config.properties.example)
                """);
    }
}
