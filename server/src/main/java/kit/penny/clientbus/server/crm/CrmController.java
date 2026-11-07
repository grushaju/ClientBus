package kit.penny.clientbus.server.crm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.common.enums.CrmType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/crm")
@PreAuthorize("isAuthenticated()")
public class CrmController {
    private final CrmService service;
    private final CrmCatalogSync sync;
    private final CrmClientSync clients;
    private final CrmScheduleSync schedules;
    private final CrmAppointmentSync appointments;
    public CrmController(CrmService service, CrmCatalogSync sync, CrmClientSync clients, CrmScheduleSync schedules, CrmAppointmentSync appointments) {
        this.service = service; this.sync = sync; this.clients=clients; this.schedules=schedules; this.appointments=appointments;
    }
    @GetMapping("/types") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<CrmType> types() { return service.types(); }
    @GetMapping("/connections") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<CrmConnectionDto> connections() { return service.connections(); }
    @PostMapping("/connections") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<CrmConnectionDto> create(@Valid @RequestBody CreateCrmConnectionRequest request) {
        return ResponseEntity.status(201).body(service.createConnection(request));
    }
    @GetMapping("/connections/{id}") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CrmConnectionDto connection(@PathVariable UUID id) { return service.connection(id); }
    @PatchMapping("/connections/{id}") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CrmConnectionDto update(@PathVariable UUID id, @Valid @RequestBody UpdateCrmConnectionRequest request) {
        return service.updateConnection(id, request);
    }
    @PostMapping("/connections/{id}/verify") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CrmConnectionDto verify(@PathVariable UUID id) { return service.verify(id); }
    @DeleteMapping("/connections/{id}") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> deleteConnection(@PathVariable UUID id, @RequestParam @Min(0) long expectedVersion) {
        service.deleteConnection(id, expectedVersion);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/connections/{id}/projects") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<CrmProjectCheckDto> discover(@PathVariable UUID id,@RequestParam UUID workspaceId) {
        return service.availableProjects(id,workspaceId);
    }
    @PostMapping("/connections/{id}/project-checks") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CrmProjectCheckDto check(@PathVariable UUID id, @Valid @RequestBody CheckCrmProjectRequest request) {
        return service.check(id, request);
    }
    @GetMapping("/workspaces/{id}/projects")
    public List<CrmProjectDto> projects(@PathVariable UUID id, @RequestParam(required = false) CrmType provider) {
        return service.projects(id, provider);
    }
    @GetMapping("/projects") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<CrmProjectDto> organizationProjects() { return service.organizationProjects(); }
    @PostMapping("/workspaces/{id}/projects") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<CrmProjectDto> bind(@PathVariable UUID id, @Valid @RequestBody BindCrmProjectRequest request) {
        var result = service.bind(id, request); return ResponseEntity.status(result.created() ? 201 : 200).body(result.project());
    }
    @PatchMapping("/projects/{id}") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CrmProjectDto updateProject(@PathVariable UUID id, @Valid @RequestBody UpdateCrmProjectRequest request) {
        return service.updateProject(id, request);
    }
    @PutMapping("/projects/{id}/connection") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CrmProjectDto changeConnection(@PathVariable UUID id, @Valid @RequestBody ChangeCrmConnectionRequest request) {
        return service.changeConnection(id, request);
    }
    @PostMapping("/projects/{id}/detach") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CrmProjectDto detachProject(@PathVariable UUID id, @RequestParam @Min(0) long expectedVersion) {
        return service.detachProject(id, expectedVersion);
    }
    @GetMapping("/projects/{id}/masters")
    public CrmMasterPageDto masters(@PathVariable UUID id, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean unlinked,
            @RequestParam(defaultValue = "true") boolean includeNotObserved) {
        return service.masters(id, cursor, q, unlinked, includeNotObserved);
    }
    @GetMapping("/masters/{id}")
    public CrmMasterDto master(@PathVariable UUID id) { return service.master(id); }
    @GetMapping("/projects/{id}/employee-candidates")
    public List<CrmEmployeeCandidateDto> employeeCandidates(@PathVariable UUID id) { return service.employeeCandidates(id); }
    @PutMapping("/masters/{id}/employee-link")
    public CrmMasterDto link(@PathVariable UUID id, @Valid @RequestBody LinkCrmEmployeeRequest request) {
        return service.linkEmployee(id, request.employeeId(), request.expectedVersion());
    }
    @DeleteMapping("/masters/{id}/employee-link")
    public CrmMasterDto unlink(@PathVariable UUID id, @RequestParam @Min(0) long expectedVersion) {
        return service.linkEmployee(id, null, expectedVersion);
    }
    @PostMapping("/projects/{id}/refresh")
    public ResponseEntity<CrmSyncStateDto> refresh(@PathVariable UUID id, @Valid @RequestBody RefreshCrmRequest request) {
        service.requireProjectAccess(id);
        if ("CLIENTS".equals(request.dataset())) return ResponseEntity.accepted().body(clients.refresh(id));
        if ("WORK_INTERVALS".equals(request.dataset())) return ResponseEntity.accepted().body(
                schedules.refresh(id,request.masterId(),request.dateFrom(),request.dateTo()));
        if (Set.of("APPOINTMENTS","CLIENT_HISTORY").contains(request.dataset())) return ResponseEntity.accepted().body(
                appointments.refresh(id,request.dataset(),request.crmClientId(),request.dateFrom(),request.dateTo()));
        if (!Set.of("MASTERS", "SERVICES", "CATALOG").contains(request.dataset()))
            throw new CrmException(409, "CRM_CAPABILITY_UNVERIFIED", "Этот набор CRM ещё не реализован.");
        return ResponseEntity.accepted().body("MASTERS".equals(request.dataset()) ? sync.refresh(id) : sync.refresh(id, request.dataset()));
    }
    @GetMapping("/projects/{id}/sync-state")
    public List<CrmSyncStateDto> state(@PathVariable UUID id,@RequestParam(required=false) UUID masterId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required=false) String dataset,@RequestParam(required=false) UUID crmClientId) {
        if (dataset!=null) {
            if (!Set.of("APPOINTMENTS","CLIENT_HISTORY").contains(dataset) || masterId!=null) throw CrmException.invalid();
            new CrmAppointmentScope(dataset,crmClientId,dateFrom,dateTo);
            return List.of(appointments.state(id,dataset,crmClientId,dateFrom,dateTo));
        }
        if (crmClientId!=null) throw CrmException.invalid();
        boolean scheduleRequested=masterId != null || dateFrom != null || dateTo != null;
        if (scheduleRequested) new CrmScheduleScope(masterId,dateFrom,dateTo);
        var states=new ArrayList<>(List.of(sync.state(id),sync.state(id,"CATALOG"),clients.state(id)));
        if (scheduleRequested) states.add(schedules.state(id,masterId,dateFrom,dateTo));
        return states;
    }
}
