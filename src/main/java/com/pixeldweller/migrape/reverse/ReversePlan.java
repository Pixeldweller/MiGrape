package com.pixeldweller.migrape.reverse;

import com.pixeldweller.migrape.schema.ColumnDefinition;
import com.pixeldweller.migrape.schema.SequenceDefinition;
import com.pixeldweller.migrape.schema.TableDefinition;
import com.pixeldweller.migrape.util.Log;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Zuordnung H2-Ziel &lt;-&gt; MariaDB-Quelle, bevor irgendetwas geschrieben wird.
 *
 *  Das H2-Schema gibt vor, was kopiert wird: jede H2-Tabelle sucht ihr MariaDB-Gegenstueck
 *  ohne Ruecksicht auf die Schreibweise. Abweichungen werden gemeldet, Mehrdeutigkeiten
 *  (zwei MariaDB-Tabellen, die sich nur in der Schreibweise unterscheiden) brechen den Lauf
 *  ab -- sonst waere es Zufall, welche der beiden Tabellen in H2 landet. */
final class ReversePlan {

    record ColumnMapping(ColumnDefinition h2Column, String mariaColumn) {
    }

    record TableMapping(TableDefinition h2Table, String mariaTable, List<ColumnMapping> columns) {
    }

    record SequenceMapping(SequenceDefinition h2Sequence, String mariaSequence) {
    }

    final List<TableMapping> tables = new ArrayList<>();
    /** H2-Tabellen ohne Gegenstueck in MariaDB -- sie bleiben unangetastet. */
    final List<String> missingTables = new ArrayList<>();
    final List<SequenceMapping> sequences = new ArrayList<>();

    private ReversePlan() {
    }

    static ReversePlan build(List<String> tableOrder, Map<String, TableDefinition> h2Tables,
                             List<SequenceDefinition> h2Sequences, MariaDbCatalog maria,
                             boolean tableFilterActive) throws SQLException {
        ReversePlan plan = new ReversePlan();
        List<String> ambiguous = new ArrayList<>();

        for (String name : tableOrder) {
            List<String> candidates = maria.tables.candidates(name);
            if (candidates.size() > 1) {
                ambiguous.add("Tabelle " + name + " -> " + candidates);
            } else if (candidates.isEmpty()) {
                plan.missingTables.add(name);
                Log.warn("Tabelle " + name + " gibt es in MariaDB nicht -- sie wird nicht befuellt");
            } else {
                plan.tables.add(mapColumns(h2Tables.get(name), candidates.get(0), maria));
            }
        }

        for (SequenceDefinition seq : h2Sequences) {
            List<String> candidates = maria.sequences.candidates(seq.name());
            if (candidates.size() > 1) {
                ambiguous.add("Sequenz " + seq.name() + " -> " + candidates);
            } else if (candidates.isEmpty()) {
                Log.warn("Sequenz " + seq.name() + " gibt es in MariaDB nicht -- ihr Stand in H2 "
                        + "bleibt unveraendert");
            } else {
                plan.sequences.add(new SequenceMapping(seq, candidates.get(0)));
            }
        }

        if (!ambiguous.isEmpty()) {
            throw new IllegalStateException("In MariaDB gibt es Objekte, die sich nur in der "
                    + "Gross-/Kleinschreibung unterscheiden -- welches davon nach H2 soll, ist nicht "
                    + "eindeutig. Bitte die ueberzaehligen in MariaDB entfernen:\n  "
                    + String.join("\n  ", ambiguous));
        }

        if (!tableFilterActive) {
            for (String table : maria.tables.without(h2Tables.keySet())) {
                Log.warn("MariaDB-Tabelle " + table + " hat kein Gegenstueck im H2-Schema -- "
                        + "ihre Daten werden NICHT kopiert");
            }
            List<String> sequenceNames = h2Sequences.stream().map(SequenceDefinition::name).toList();
            for (String seq : maria.sequences.without(sequenceNames)) {
                Log.warn("MariaDB-Sequenz " + seq + " hat kein Gegenstueck im H2-Schema");
            }
        }
        return plan;
    }

    private static TableMapping mapColumns(TableDefinition h2Table, String mariaTable,
                                           MariaDbCatalog maria) throws SQLException {
        List<String> mariaColumns = maria.columns(mariaTable);
        CaseInsensitiveNames byName = new CaseInsensitiveNames(mariaColumns);
        List<ColumnMapping> columns = new ArrayList<>();
        List<String> matched = new ArrayList<>();

        for (ColumnDefinition col : h2Table.orderedColumns()) {
            // MariaDB-Spaltennamen sind immer case-insensitiv, mehrere Treffer gibt es nicht.
            List<String> candidates = byName.candidates(col.name);
            if (candidates.isEmpty()) {
                Log.warn("Tabelle " + h2Table.name + ": Spalte " + col.name + " fehlt in MariaDB -- "
                        + "sie bleibt NULL bzw. bekommt ihren DEFAULT-Wert");
            } else {
                columns.add(new ColumnMapping(col, candidates.get(0)));
                matched.add(col.name);
            }
        }
        for (String extra : byName.without(matched)) {
            Log.warn("Tabelle " + h2Table.name + ": MariaDB-Spalte " + extra + " gibt es in H2 nicht -- "
                    + "ihre Werte werden NICHT kopiert");
        }
        if (columns.isEmpty()) {
            throw new IllegalStateException("Tabelle " + h2Table.name + ": keine einzige Spalte "
                    + "passt zu MariaDB-Tabelle " + mariaTable);
        }
        return new TableMapping(h2Table, mariaTable, columns);
    }
}
