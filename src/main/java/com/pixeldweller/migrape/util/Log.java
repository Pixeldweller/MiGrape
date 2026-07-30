package com.pixeldweller.migrape.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Minimalistisches Logging auf Konsole + migration.log, ohne externe Logging-Dependency.
 *  Die Log-Datei wird angehaengt (nicht ueberschrieben), damit bei einem Resume-Lauf das
 *  Protokoll des vorherigen Fehlversuchs erhalten bleibt. */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static PrintWriter fileWriter;
    /** true, solange eine per {@link #progress(String)} geschriebene Zeile noch nicht
     *  mit einem Zeilenumbruch abgeschlossen ist. */
    private static boolean progressLinePending;

    private Log() {
    }

    public static synchronized void init(Path logFile) {
        try {
            fileWriter = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        } catch (IOException e) {
            System.err.println("Konnte Log-Datei nicht oeffnen: " + e.getMessage());
        }
    }

    public static synchronized void info(String msg) {
        write(msg);
    }

    public static synchronized void warn(String msg) {
        write("WARNUNG: " + msg);
    }

    public static synchronized void error(String msg, Throwable t) {
        write("FEHLER: " + msg + (t != null ? " (" + t.getMessage() + ")" : ""));
        if (t != null && fileWriter != null) {
            t.printStackTrace(fileWriter);
            fileWriter.flush();
        }
    }

    /** Schreibt eine sich selbst ueberschreibende Fortschrittszeile -- nur auf die Konsole,
     *  nicht in die Log-Datei. */
    public static synchronized void progress(String msg) {
        System.out.print("\r" + msg);
        System.out.flush();
        progressLinePending = true;
    }

    /** Schliesst eine offene Fortschrittszeile ab, damit die naechste Ausgabe nicht darin landet. */
    public static synchronized void endProgress() {
        if (progressLinePending) {
            System.out.println();
            progressLinePending = false;
        }
    }

    private static void write(String msg) {
        endProgress();
        String line = "[" + LocalDateTime.now().format(TS) + "] " + msg;
        System.out.println(line);
        if (fileWriter != null) {
            fileWriter.println(line);
            fileWriter.flush();
        }
    }

    public static synchronized void close() {
        endProgress();
        if (fileWriter != null) {
            fileWriter.close();
            fileWriter = null;
        }
    }
}
