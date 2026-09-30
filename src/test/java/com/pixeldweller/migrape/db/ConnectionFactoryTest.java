package com.pixeldweller.migrape.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionFactoryTest {

    @Test
    void existingH2UrlGetsIfExists() {
        assertEquals("jdbc:h2:file:C:/daten/app;IFEXISTS=TRUE",
                ConnectionFactory.existingOnly("jdbc:h2:file:C:/daten/app"));
        assertEquals("jdbc:h2:file:C:/daten/app;MODE=MySQL;IFEXISTS=TRUE",
                ConnectionFactory.existingOnly("jdbc:h2:file:C:/daten/app;MODE=MySQL"));
    }

    @Test
    void leavesExplicitSettingAndInMemoryUrlsAlone() {
        // Doppelt gesetzte Einstellungen weist H2 ab.
        assertEquals("jdbc:h2:file:C:/daten/app;ifexists=false",
                ConnectionFactory.existingOnly("jdbc:h2:file:C:/daten/app;ifexists=false"));
        assertEquals("jdbc:h2:mem:test", ConnectionFactory.existingOnly("jdbc:h2:mem:test"));
    }
}
