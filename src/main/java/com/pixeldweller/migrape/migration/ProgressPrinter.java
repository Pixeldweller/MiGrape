package com.pixeldweller.migrape.migration;

public final class ProgressPrinter {

    private long lastPrintedPercent = -1;

    public void update(String table, long copied, long total) {
        if (total <= 0) {
            return;
        }
        long percent = (copied * 100) / total;
        if (percent != lastPrintedPercent) {
            lastPrintedPercent = percent;
            System.out.printf("\r%-30s %,10d / %,10d (%3d%%)", table, copied, total, percent);
            if (percent == 100) {
                System.out.println();
            }
        }
    }

    public void finish(String table, long copied) {
        lastPrintedPercent = -1;
        System.out.printf("%-30s %,10d Zeilen kopiert%n", table, copied);
    }
}
