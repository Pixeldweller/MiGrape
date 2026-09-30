package com.pixeldweller.migrape.db;

import com.pixeldweller.migrape.MigrationConfig;
import com.pixeldweller.migrape.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentifierCaseTest {

    @Test
    void appliesConfiguredCase() {
        assertEquals("anfrage_mapping", IdentifierCase.LOWER.apply("ANFRAGE_MAPPING"));
        assertEquals("ANFRAGE_MAPPING", IdentifierCase.UPPER.apply("anfrage_mapping"));
        assertEquals("Anfrage_Mapping", IdentifierCase.PRESERVE.apply("Anfrage_Mapping"));
    }

    @Test
    void lowerIsTheDefault() throws Exception {
        MigrationConfig config = TestConfigs.forH2Only("jdbc:h2:mem:egal");
        assertEquals(IdentifierCase.LOWER, config.mariaIdentifierCase);
    }

    @Test
    void parsesConfigValueIgnoringCase() throws Exception {
        MigrationConfig config = TestConfigs.fromContent("""
                h2.url=jdbc:h2:mem:egal
                maria.url=jdbc:mariadb://localhost:3306/unbenutzt
                maria.user=root
                maria.identifier.case= Preserve
                """);
        assertEquals(IdentifierCase.PRESERVE, config.mariaIdentifierCase);
    }

    @Test
    void rejectsUnknownValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> IdentifierCase.parse("camel"));
        assertTrue(e.getMessage().contains("maria.identifier.case"), e.getMessage());
    }
}
