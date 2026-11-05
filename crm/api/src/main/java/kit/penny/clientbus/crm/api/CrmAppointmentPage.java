package kit.penny.clientbus.crm.api;
import java.time.Instant;
import java.util.List;
public record CrmAppointmentPage(List<CrmAppointment> items, String nextCursor, Instant observedAt,
        CrmReadResult.Completeness completeness, String externalProjectId, CrmAppointmentQuery scope) {
    public CrmAppointmentPage { items=List.copyOf(items); }
}
