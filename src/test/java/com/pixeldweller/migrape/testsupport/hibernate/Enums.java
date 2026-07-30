package com.pixeldweller.migrape.testsupport.hibernate;

/** Die Enums des Fixture-Modells. Zusammen in einer Datei, weil sie nur Beiwerk zu den
 *  Entities sind. REGION/EMPLOYMENT_TYPE/STATUS werden als String gemappt (VARCHAR),
 *  SECURITY_LEVEL als Ordinalwert (TINYINT) -- beide Wege sollen die Migration ueberleben. */
public final class Enums {

    private Enums() {
    }

    public enum Region {
        EMEA, AMERICAS, APAC
    }

    public enum EmploymentType {
        FULL_TIME, PART_TIME, CONTRACTOR
    }

    /** Bewusst als ORDINAL gemappt: landet in H2 als TINYINT, nicht als Text. */
    public enum SecurityLevel {
        OPEN, INTERNAL, SECRET
    }

    public enum ProjectStatus {
        PLANNED, ACTIVE, DONE
    }
}
