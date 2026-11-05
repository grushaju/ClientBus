package kit.penny.clientbus.common.dto.crm;
import jakarta.validation.constraints.*;
import kit.penny.clientbus.common.enums.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public record RefreshCrmRequest(@NotBlank String dataset, UUID masterId, LocalDate dateFrom, LocalDate dateTo, UUID crmClientId) {
    public RefreshCrmRequest(String dataset) { this(dataset, null, null, null, null); }
    public RefreshCrmRequest(String dataset,UUID masterId,LocalDate dateFrom,LocalDate dateTo) { this(dataset,masterId,dateFrom,dateTo,null); }
    @AssertTrue
    public boolean isWorkIntervalScopeValid() {
        return !"WORK_INTERVALS".equals(dataset) || masterId != null && validPeriod(dateFrom, dateTo);
    }
    @AssertTrue
    public boolean isAppointmentScopeValid() {
        return switch (dataset==null ? "" : dataset) {
            case "APPOINTMENTS" -> masterId==null && crmClientId==null && validPeriod(dateFrom,dateTo);
            case "CLIENT_HISTORY" -> masterId==null && crmClientId!=null && validPeriod(dateFrom,dateTo);
            default -> true;
        };
    }
    public static boolean validPeriod(LocalDate from, LocalDate to) {
        return from != null && to != null && from.getYear() >= 2000 && to.getYear() <= 2100
                && !from.isAfter(to) && ChronoUnit.DAYS.between(from, to) < 31;
    }
}
