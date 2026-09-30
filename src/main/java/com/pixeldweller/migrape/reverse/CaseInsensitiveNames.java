package com.pixeldweller.migrape.reverse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ordnet H2-Namen (meist GROSS) den MariaDB-Namen zu, ohne auf die Schreibweise zu achten.
 *
 *  Unter Linux kann eine MariaDB-Datenbank zwei Tabellen enthalten, die sich nur in der
 *  Schreibweise unterscheiden (z.B. KUNDE aus einer Migration und kunde von Hibernate). Dann
 *  wird hier nicht geraten: {@link #candidates} liefert beide, und der Aufrufer bricht ab. */
final class CaseInsensitiveNames {

    private final Map<String, List<String>> byKey = new LinkedHashMap<>();

    CaseInsensitiveNames(Collection<String> names) {
        for (String name : names) {
            byKey.computeIfAbsent(key(name), k -> new ArrayList<>()).add(name);
        }
    }

    /** Alle Namen, die ohne Beachtung der Schreibweise gleich {@code name} sind. */
    List<String> candidates(String name) {
        return List.copyOf(byKey.getOrDefault(key(name), List.of()));
    }

    /** Alle Namen, deren Schluessel nicht in {@code matched} vorkommt. */
    List<String> without(Collection<String> matched) {
        List<String> keys = matched.stream().map(CaseInsensitiveNames::key).toList();
        List<String> rest = new ArrayList<>();
        byKey.forEach((key, names) -> {
            if (!keys.contains(key)) {
                rest.addAll(names);
            }
        });
        return rest;
    }

    private static String key(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
