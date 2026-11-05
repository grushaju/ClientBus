package kit.penny.clientbus.crm.api;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Inclusive local dates for one confirmed master; provider limits are not inferred. */
public record CrmWorkIntervalQuery(String externalMasterId, LocalDate dateFrom, LocalDate dateTo) {
    public CrmWorkIntervalQuery {
        if (externalMasterId == null || externalMasterId.isBlank() || externalMasterId.length() > 128
                || dateFrom == null || dateTo == null || dateFrom.getYear() < 2000 || dateTo.getYear() > 2100
                || dateFrom.isAfter(dateTo) || ChronoUnit.DAYS.between(dateFrom, dateTo) >= 31)
            throw new IllegalArgumentException("Invalid schedule scope");
    }
}
