package com.pixeldweller.migrape.migration;

import com.pixeldweller.migrape.util.Log;

/** Schreibt den Kopierfortschritt als sich selbst ueberschreibende Konsolenzeile.
 *  Laeuft ueber {@link Log}, damit Fortschritt und normale Log-Ausgaben sich nicht vermischen. */
public final class ProgressPrinter {

    private long lastPrintedPercent = -1;

    public void update(String table, long copied, long total) {
        if (total <= 0) {
            return;
        }
        long percent = (copied * 100) / total;
        if (percent == lastPrintedPercent) {
            return;
        }
        lastPrintedPercent = percent;
        Log.progress(String.format("%-30s %,10d / %,10d (%3d%%)", table, copied, total, percent));
    }

    /** Beendet die Fortschrittszeile. Die Erfolgsmeldung selbst schreibt der Aufrufer,
     *  damit sie auch in der Log-Datei landet. */
    public void finish() {
        lastPrintedPercent = -1;
        Log.endProgress();
    }
}
