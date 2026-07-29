package com.pixeldweller.migrape.schema;

import com.pixeldweller.migrape.util.Log;

import java.util.*;

/** Sortiert Tabellen so, dass referenzierte Tabellen (FK-Ziel) vor abhaengigen Tabellen kommen.
 *  Zyklen werden erkannt und aufgeloest (Reihenfolge dann best-effort, mit Warnung). */
public final class TableOrderResolver {

    private TableOrderResolver() {
    }

    public static List<String> resolve(Map<String, TableDefinition> tables) {
        Map<String, Set<String>> dependsOn = new HashMap<>();
        for (TableDefinition t : tables.values()) {
            Set<String> deps = new LinkedHashSet<>();
            for (TableDefinition.ForeignKey fk : t.foreignKeys) {
                if (tables.containsKey(fk.referencedTable()) && !fk.referencedTable().equals(t.name)) {
                    deps.add(fk.referencedTable());
                }
            }
            dependsOn.put(t.name, deps);
        }

        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> inProgress = new LinkedHashSet<>();

        for (String table : tables.keySet()) {
            visit(table, dependsOn, visited, inProgress, result);
        }

        return result;
    }

    private static void visit(String table, Map<String, Set<String>> dependsOn,
                               Set<String> visited, Set<String> inProgress, List<String> result) {
        if (visited.contains(table)) {
            return;
        }
        if (inProgress.contains(table)) {
            Log.warn("Zyklische Fremdschluessel-Abhaengigkeit erkannt bei Tabelle '" + table + "', "
                    + "Reihenfolge wird best-effort aufgeloest (FK-Constraint ggf. spaeter separat pruefen)");
            return;
        }
        inProgress.add(table);
        for (String dep : dependsOn.getOrDefault(table, Set.of())) {
            visit(dep, dependsOn, visited, inProgress, result);
        }
        inProgress.remove(table);
        visited.add(table);
        result.add(table);
    }
}
