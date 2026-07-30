package com.pixeldweller.migrape;

import com.pixeldweller.migrape.migration.MigrationService;
import com.pixeldweller.migrape.migration.MigrationState;
import com.pixeldweller.migrape.migration.VerificationResult;
import com.pixeldweller.migrape.util.Log;

import java.nio.file.Path;
import java.util.List;

public final class Main {

    public static void main(String[] args) {
        // Erstes Argument ist das Kommando -- ausser es ist ein Flag, dann gilt der Standard.
        String command = args.length > 0 && !args[0].startsWith("-") ? args[0] : "migrate";
        Path configPath = Path.of(argValue(args, "--config", "config.properties"));

        Log.init(Path.of("migration.log"));
        Log.info("MiGrape H2 -> MariaDB Migrator");
        Log.info("Kommando: " + command + " | Config: " + configPath);

        int exitCode = 0;
        try {
            MigrationConfig config = MigrationConfig.load(configPath);
            Log.info("MariaDB-SSL: " + config.mariaSsl.describe());
            MigrationService service = new MigrationService(config);
            MigrationState state = new MigrationState(MigrationService.defaultStateFile());

            switch (command) {
                case "schema" -> service.migrateSchema(true);
                case "data" -> {
                    state.clear();
                    service.migrateData(state, false);
                }
                case "resume" -> service.migrateData(state, true);
                case "verify" -> exitCode = printVerification(service.verify());
                case "migrate" -> {
                    state.clear();
                    service.migrateSchema(true);
                    service.migrateData(state, false);
                    exitCode = printVerification(service.verify());
                }
                default -> {
                    printUsage();
                    exitCode = 1;
                }
            }

            if (exitCode == 0) {
                Log.info("Fertig.");
            }
        } catch (Exception e) {
            Log.error("Migration abgebrochen", e);
            exitCode = 1;
        } finally {
            Log.close();
        }

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** @return 0 wenn alle Tabellen uebereinstimmen, sonst 1 */
    private static int printVerification(List<VerificationResult> results) {
        Log.info("---- Verifikation ----");
        boolean allOk = true;
        for (VerificationResult r : results) {
            if (!r.ok()) {
                allOk = false;
            }
            if (r.problem() != null) {
                Log.info(String.format("%-30s %-11s %s", r.table(), "FEHLER", r.problem()));
            } else {
                Log.info(String.format("%-30s %-11s H2=%d  MariaDB=%d",
                        r.table(), r.ok() ? "OK" : "ABWEICHUNG", r.sourceCount(), r.targetCount()));
            }
        }
        if (allOk) {
            Log.info("Alle Tabellen erfolgreich migriert.");
            return 0;
        }
        Log.warn("Es gibt Abweichungen, siehe oben.");
        return 1;
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
                  schema    Nur Schema (Tabellen, Schluessel, Indizes) auf MariaDB anlegen
                  data      Nur Daten kopieren, alle Tabellen neu (Schema muss existieren)
                  resume    Wie 'data', ueberspringt aber bereits als DONE markierte Tabellen
                  verify    Nur Zeilenzahlen zwischen H2 und MariaDB vergleichen

                Exit-Code 1 bei Fehlern oder Abweichungen in der Verifikation.
                Konfiguration ueber config.properties (siehe config.properties.example)
                """);
    }
}
