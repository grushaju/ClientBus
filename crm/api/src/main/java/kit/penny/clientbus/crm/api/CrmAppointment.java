package kit.penny.clientbus.crm.api;
import java.time.LocalDateTime;
import java.util.*;
/** A bounded normalized observation. Financial state belongs to a separate writer. */
public record CrmAppointment(String externalId, String kind, CrmClientReference client,
        Map<String,Object> clientSnapshot, LocalDateTime localStart, LocalDateTime localEnd,
        String appointmentState, String confirmationState, String attendanceState,
        Map<String,String> providerState, String comment, List<CrmAppointmentService> services,
        List<CrmAppointmentMaster> masters, boolean servicesComplete, boolean mastersComplete,
        Set<String> presentFields, String source) {
    public CrmAppointment {
        clientSnapshot=Map.copyOf(clientSnapshot); providerState=Map.copyOf(providerState);
        services=List.copyOf(services); masters=List.copyOf(masters); presentFields=Set.copyOf(presentFields);
    }
}
