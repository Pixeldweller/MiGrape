package com.pixeldweller.migrape.testsupport;

import com.pixeldweller.migrape.MigrationConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Baut MigrationConfig-Instanzen fuer Tests, ohne dass jeder Test eine eigene
 *  Properties-Datei schreiben muss. */
public final class TestConfigs {

    private TestConfigs() {
    }

    /** Config fuer Tests, die nur die H2-Seite brauchen. Die MariaDB-Werte sind Platzhalter. */
    public static MigrationConfig forH2Only(String h2Url) throws IOException {
        return write("""
                h2.url=%s
                h2.user=sa
                h2.password=
                maria.url=jdbc:mariadb://localhost:3306/unbenutzt
                maria.user=root
                maria.password=
                """.formatted(h2Url));
    }

    public static MigrationConfig forMigration(String h2Url, String mariaUrl, int batchSize) throws IOException {
        return write("""
                h2.url=%s
                h2.user=sa
                h2.password=
                maria.url=%s
                maria.user=root
                maria.password=
                batch.size=%d
                fetch.size=%d
                """.formatted(h2Url, mariaUrl, batchSize, batchSize));
    }

    /** Config aus einem frei zusammengesetzten config.properties-Inhalt. */
    public static MigrationConfig fromContent(String content) throws IOException {
        return write(content);
    }

    private static MigrationConfig write(String content) throws IOException {
        Path file = Files.createTempFile("migrape-test-config", ".properties");
        file.toFile().deleteOnExit();
        Files.writeString(file, content);
        return MigrationConfig.load(file);
    }
}
