package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.dto.crm.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@PreAuthorize("isAuthenticated()")
public class CrmAppointmentController {
    private final CrmAppointmentReader reader;
    public CrmAppointmentController(CrmAppointmentReader reader) { this.reader=reader; }
    @GetMapping("/crm/projects/{projectId}/appointments")
    public CrmAppointmentPageDto appointments(@PathVariable UUID projectId,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required=false) UUID crmClientId,@RequestParam(required=false) UUID masterId,
            @RequestParam(required=false) String kind,@RequestParam(required=false) String appointmentState,
            @RequestParam(required=false) String confirmationState,@RequestParam(required=false) String attendanceState,
            @RequestParam(required=false) String paymentState,@RequestParam(required=false) String cursor) {
        return reader.appointments(projectId,new CrmAppointmentFilter(dateFrom,dateTo,crmClientId,masterId,kind,appointmentState,confirmationState,attendanceState,paymentState),cursor);
    }
    @GetMapping("/crm/appointments/{id}")
    public CrmAppointmentDto appointment(@PathVariable UUID id) { return reader.appointment(id); }
    @GetMapping("/clients/{clientId}/crm/appointments")
    public CrmAppointmentPageDto history(@PathVariable UUID clientId,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required=false) UUID workspaceId,@RequestParam(required=false) String kind,
            @RequestParam(required=false) String appointmentState,@RequestParam(required=false) String confirmationState,
            @RequestParam(required=false) String attendanceState,@RequestParam(required=false) String paymentState,
            @RequestParam(required=false) String cursor) {
        return reader.history(clientId,workspaceId,new CrmAppointmentFilter(dateFrom,dateTo,null,null,kind,appointmentState,confirmationState,attendanceState,paymentState),cursor);
    }
}
