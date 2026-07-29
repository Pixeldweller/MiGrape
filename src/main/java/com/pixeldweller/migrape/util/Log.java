package com.pixeldweller.migrape.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Minimalistisches Logging auf Konsole + migration.log, ohne externe Logging-Dependency. */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static PrintWriter fileWriter;

    private Log() {
    }

    public static synchronized void init(Path logFile) {
        try {
            fileWriter = new PrintWriter(Files.newBufferedWriter(logFile));
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
    }

    private static void write(String msg) {
        String line = "[" + LocalDateTime.now().format(TS) + "] " + msg;
        System.out.println(line);
        if (fileWriter != null) {
            fileWriter.println(line);
            fileWriter.flush();
        }
    }

    public static synchronized void close() {
        if (fileWriter != null) {
            fileWriter.close();
        }
    }
}
