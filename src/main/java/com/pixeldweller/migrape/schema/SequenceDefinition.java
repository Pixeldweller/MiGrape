package com.pixeldweller.migrape.schema;

/** Eine H2-Sequenz, wie sie in MariaDB neu angelegt wird.
 *
 *  @param nextValue der Wert, den die Sequenz als naechstes liefern wuerde (H2: BASE_VALUE).
 *                   Damit setzt die Anwendung nach der Migration genau dort fort, wo sie in H2
 *                   aufgehoert hat, statt wieder bei 1 anzufangen und auf vergebene IDs zu treffen. */
public record SequenceDefinition(String name, long nextValue, long increment,
                                 long minValue, long maxValue, boolean cycle) {
}
