package kit.penny.clientbus.server.crm;

import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.crm.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/crm")
public class CrmOperationController {
    private final CrmOperationService service;
    public CrmOperationController(CrmOperationService service) { this.service=service; }
    @PostMapping("/projects/{id}/appointments")
    public ResponseEntity<CrmOperationDto> create(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody CrmCreateAppointmentRequest request) { return accepted(service.create(id,key,request)); }
    @PostMapping("/appointments/{id}/move")
    public ResponseEntity<CrmOperationDto> move(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody CrmChangeAppointmentRequest request) { return accepted(service.change(id,key,request,false)); }
    @PostMapping("/appointments/{id}/cancel")
    public ResponseEntity<CrmOperationDto> cancel(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody CrmChangeAppointmentRequest request) { return accepted(service.change(id,key,request,true)); }
    @PostMapping("/projects/{id}/availability")
    public CrmAvailabilityDto availability(@PathVariable UUID id,@Valid @RequestBody CrmAvailabilityRequest request) { return service.availability(id,request); }
    @PostMapping("/appointments/{id}/availability")
    public CrmAvailabilityDto moveAvailability(@PathVariable UUID id,@Valid @RequestBody CrmMoveAvailabilityRequest request) { return service.moveAvailability(id,request); }
    @GetMapping("/operations/{id}") public CrmOperationDto get(@PathVariable UUID id) { return service.get(id); }
    @GetMapping("/projects/{id}/operations/by-key/{key}")
    public CrmOperationDto byKey(@PathVariable UUID id,@PathVariable UUID key) { return service.byKey(id,key); }
    @GetMapping("/projects/{id}/operations")
    public CrmOperationPageDto list(@PathVariable UUID id,@RequestParam(defaultValue="true") boolean own,
            @RequestParam(defaultValue="false") boolean active,@RequestParam(required=false) String cursor) { return service.list(id,own,active,cursor); }
    @GetMapping("/appointments/{id}/history")
    public CrmOperationHistoryPageDto history(@PathVariable UUID id,@RequestParam(defaultValue="true") boolean own,
            @RequestParam(required=false) String cursor) { return service.history(id,own,cursor); }
    @GetMapping("/projects/{id}/pending-creations")
    public CrmOperationPageDto pendingCreates(@PathVariable UUID id,@RequestParam(defaultValue="true") boolean own,
            @RequestParam(required=false) String cursor) { return service.pendingCreates(id,own,cursor); }
    @PostMapping("/operations/{id}/reconcile") public CrmOperationDto reconcile(@PathVariable UUID id) { return service.reconcile(id); }
    private static ResponseEntity<CrmOperationDto> accepted(CrmOperationDto operation) {
        return ResponseEntity.status(java.util.Set.of("CONFIRMED","REJECTED").contains(operation.status()) ? 200 : 202).body(operation);
    }
}
