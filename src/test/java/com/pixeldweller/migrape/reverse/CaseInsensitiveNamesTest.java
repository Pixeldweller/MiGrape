package com.pixeldweller.migrape.reverse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Die Windows-MariaDB der Integrationstests kann keine zwei Tabellen anlegen, die sich nur in
 *  der Schreibweise unterscheiden -- unter Linux passiert genau das (Migration legt KUNDE an,
 *  Hibernate danach kunde). Deshalb wird die Zuordnung hier ohne Datenbank geprueft. */
class CaseInsensitiveNamesTest {

    private final CaseInsensitiveNames names = new CaseInsensitiveNames(
            List.of("anfragemappingeintrag", "ANFRAGEMAPPINGEINTRAG", "katalog_eintrag", "logeintrag"));

    @Test
    void findsUniqueNameRegardlessOfCase() {
        assertEquals(List.of("katalog_eintrag"), names.candidates("KATALOG_EINTRAG"));
        assertEquals(List.of(), names.candidates("FACHBEHOERDE"));
    }

    @Test
    void reportsBothNamesWhenOnlyTheCaseDiffers() {
        assertEquals(List.of("anfragemappingeintrag", "ANFRAGEMAPPINGEINTRAG"),
                names.candidates("AnfrageMappingEintrag"));
    }

    @Test
    void listsNamesWithoutCounterpart() {
        assertEquals(List.of("anfragemappingeintrag", "ANFRAGEMAPPINGEINTRAG", "logeintrag"),
                names.without(List.of("KATALOG_EINTRAG")));
    }
}
