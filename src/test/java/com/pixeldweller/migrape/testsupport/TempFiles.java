package com.pixeldweller.migrape.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Temporaeres Arbeitsverzeichnis fuer Tests, die es schon in {@code @BeforeAll} brauchen.
 *  JUnit befuellt ein {@code @TempDir}-Instanzfeld erst danach, deshalb hier von Hand. */
public final class TempFiles {

    private TempFiles() {
    }

    public static Path createDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException("Temporaeres Verzeichnis nicht anlegbar", e);
        }
    }

    /** Loescht Verzeichnis samt Inhalt. Fehler beim Aufraeumen duerfen keinen Test kippen --
     *  unter Windows kann eine Datei noch kurz von einem Dateihandle gehalten werden. */
    public static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // beim naechsten Aufraeumen des Temp-Verzeichnisses
                }
            });
        } catch (IOException ignored) {
            // siehe oben
        }
    }
}
