package kit.penny.clientbus.crm.api;
import java.time.Instant;
import java.util.List;
public record CrmAppointmentPage(List<CrmAppointment> items, String nextCursor, Instant observedAt,
        CrmReadResult.Completeness completeness,String externalProjectId,CrmAppointmentQuery scope,CrmJournalObservation observation) {
    public CrmAppointmentPage(List<CrmAppointment> items,String nextCursor,Instant observedAt,CrmReadResult.Completeness completeness,String project,CrmAppointmentQuery scope){this(items,nextCursor,observedAt,completeness,project,scope,null);}
    public CrmAppointmentPage { items=List.copyOf(items); }
}
