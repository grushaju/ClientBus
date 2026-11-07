package kit.penny.clientbus.crm.api;

import java.time.LocalDate;

/** Identity and day to re-read from the provider before following a sale reference. */
public record CrmFinanceQuery(String externalAppointmentId, LocalDate date, CrmClientReference client) {
    public CrmFinanceQuery {
        if (externalAppointmentId == null || !externalAppointmentId.matches("[a-zA-Z0-9_-]{1,128}") || date == null)
            throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
    }
}
