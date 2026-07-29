package com.pixeldweller.migrape.migration;

public record VerificationResult(String table, long sourceCount, long targetCount) {

    public boolean ok() {
        return sourceCount == targetCount;
    }
}
