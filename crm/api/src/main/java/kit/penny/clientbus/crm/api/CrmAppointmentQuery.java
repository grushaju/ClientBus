package kit.penny.clientbus.crm.api;
import java.time.*;
import java.time.temporal.ChronoUnit;
/** Inclusive local dates; clients are scoped by proven external identity. */
public record CrmAppointmentQuery(LocalDate dateFrom, LocalDate dateTo, CrmClientReference client) {
    public CrmAppointmentQuery {
        if (dateFrom==null || dateTo==null || dateFrom.getYear()<2000 || dateTo.getYear()>2100
                || dateFrom.isAfter(dateTo) || ChronoUnit.DAYS.between(dateFrom,dateTo)>=31
                || client!=null && (!"PROJECT".equals(client.identityKind()) || client.externalId()==null
                    || client.externalId().isBlank() || client.externalId().length()>128))
            throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
    }
    public CrmAppointmentQuery(LocalDate from,LocalDate to) { this(from,to,null); }
}
