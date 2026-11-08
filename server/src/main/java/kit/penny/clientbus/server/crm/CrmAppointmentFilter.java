package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.dto.crm.RefreshCrmRequest;
import java.time.LocalDate;
import java.util.*;

public record CrmAppointmentFilter(LocalDate dateFrom, LocalDate dateTo, UUID crmClientId, UUID masterId,
        String kind, String appointmentState, String confirmationState, String attendanceState, String paymentState, String externalClientId) {
    public CrmAppointmentFilter(LocalDate from,LocalDate to,UUID client,UUID master,String kind,String state,String confirmation,String attendance,String payment) {
        this(from,to,client,master,kind,state,confirmation,attendance,payment,null);
    }
    public CrmAppointmentFilter {
        if (!RefreshCrmRequest.validPeriod(dateFrom,dateTo)) throw CrmException.invalid();
        if (externalClientId!=null && (externalClientId.isBlank() || externalClientId.length()>128 || crmClientId!=null)) throw CrmException.invalid();
        check(kind,"APPOINTMENT","RESERVATION","BLOCK","UNKNOWN");
        check(appointmentState,"SCHEDULED","CANCELLED","DELETED","UNKNOWN");
        check(confirmationState,"UNCONFIRMED","CONFIRMED","UNKNOWN");
        check(attendanceState,"NOT_RECORDED","ARRIVED","NO_SHOW","UNKNOWN");
        check(paymentState,"UNPAID","PARTIALLY_PAID","PAID","UNKNOWN");
    }
    private static void check(String value,String... allowed) { if (value!=null && !Set.of(allowed).contains(value)) throw CrmException.invalid(); }
}
