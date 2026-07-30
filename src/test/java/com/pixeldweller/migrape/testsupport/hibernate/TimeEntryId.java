package com.pixeldweller.migrape.testsupport.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** Zusammengesetzter Primaerschluessel aus drei Spalten, zwei davon gleichzeitig Teil eines
 *  Fremdschluessels. Der Migrator muss die Spaltenreihenfolge (KEY_SEQ) exakt uebernehmen. */
@Embeddable
public class TimeEntryId implements Serializable {

    @Column(name = "EMPLOYEE_ID")
    public Long employeeId;

    @Column(name = "PROJECT_CODE", length = 12)
    public String projectCode;

    @Column(name = "WORK_DAY")
    public LocalDate workDay;

    public TimeEntryId() {
    }

    public TimeEntryId(Long employeeId, String projectCode, LocalDate workDay) {
        this.employeeId = employeeId;
        this.projectCode = projectCode;
        this.workDay = workDay;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TimeEntryId other)) {
            return false;
        }
        return Objects.equals(employeeId, other.employeeId)
                && Objects.equals(projectCode, other.projectCode)
                && Objects.equals(workDay, other.workDay);
    }

    @Override
    public int hashCode() {
        return Objects.hash(employeeId, projectCode, workDay);
    }
}
